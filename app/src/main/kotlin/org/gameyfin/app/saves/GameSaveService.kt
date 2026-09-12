package org.gameyfin.app.saves

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gameyfin.app.config.ConfigProperties
import org.gameyfin.app.config.ConfigService
import org.gameyfin.app.core.events.GameDeletedEvent
import org.gameyfin.app.core.events.UserDeletedEvent
import org.gameyfin.app.core.security.getCurrentAuth
import org.gameyfin.app.games.entities.Game
import org.gameyfin.app.saves.entities.GameSave
import org.gameyfin.app.saves.entities.SavePlatform
import org.gameyfin.app.users.UserService
import org.gameyfin.app.users.entities.User
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.InputStream
import java.nio.file.Path
import java.security.MessageDigest
import java.util.*
import kotlin.io.path.*

private val log = KotlinLogging.logger {}

sealed interface StoreResult {
    data class Stored(val save: GameSave) : StoreResult
    data class Unchanged(val existing: GameSave) : StoreResult
    data class Conflict(val latest: GameSave) : StoreResult
    data object Disabled : StoreResult
    data object TooLarge : StoreResult
    data object QuotaExceeded : StoreResult
    data object HashMismatch : StoreResult
    data object NotAnArchive : StoreResult
}

// Client-supplied, so every field is untrusted
data class SaveUploadMetadata(
    val declaredHash: String,
    val platform: SavePlatform = SavePlatform.UNKNOWN,
    val installationId: String? = null,
    val deviceName: String? = null,
    val ludusaviTitle: String? = null,
    val baseSaveId: Long? = null,
    val force: Boolean = false
)

@Service
class GameSaveService(
    private val gameSaveRepository: GameSaveRepository,
    private val userService: UserService,
    private val config: ConfigService,
    transactionManager: PlatformTransactionManager,
    @param:Value($$"${spring.content.fs.filesystem-root:./data/}") storageRoot: String
) {

    companion object {
        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        private const val MAX_TEXT_LENGTH = 255
        private const val BYTES_PER_MB = 1024L * 1024L
    }

    // Grouped by user and game so deleting either can drop a whole directory
    private val savesRoot: Path = Path(storageRoot, "saves")
    private val transaction = TransactionTemplate(transactionManager)

    fun enabled(): Boolean = config.get(ConfigProperties.SaveSync.Enabled)!!

    fun quotaBytes(): Long = config.get(ConfigProperties.SaveSync.MaxTotalPerUserMb)!! * BYTES_PER_MB

    fun list(userId: Long, gameId: Long): List<GameSave> =
        gameSaveRepository.findByUserIdAndGameIdOrderByCreatedAtDescIdDesc(userId, gameId)

    fun listForUser(userId: Long): List<GameSave> = gameSaveRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId)

    fun byId(saveId: Long): GameSave? = gameSaveRepository.findByIdOrNull(saveId)

    fun byIds(saveIds: Collection<Long>): List<GameSave> = gameSaveRepository.findAllById(saveIds)

    fun currentUser(): User? = getCurrentAuth()?.let { userService.getByUsername(it.name) }

    // Deleting and locking only; reading someone else's save stays owner-only
    fun canManage(save: GameSave, user: User): Boolean = save.user.id == user.id || userService.canManage(save.user)

    fun archivePath(save: GameSave): Path? {
        val archive = archiveFile(save)
        if (!archive.exists()) {
            log.warn { "Save ${save.id} has no archive on disk" }
            return null
        }
        return archive
    }

    // Streams and validates outside the transaction so a slow upload never holds a DB connection
    fun store(user: User, game: Game, input: InputStream, metadata: SaveUploadMetadata): StoreResult {
        if (!enabled()) return StoreResult.Disabled

        val file = archiveFile(user.id!!, game.id!!, UUID.randomUUID().toString())
        var result: StoreResult? = null
        try {
            file.parent.createDirectories()
            val hash = streamAndHash(input, file, maxSizeBytes())
            result = when {
                hash == null -> StoreResult.TooLarge
                !hash.equals(metadata.declaredHash.trim(), ignoreCase = true) -> StoreResult.HashMismatch
                !looksLikeZip(file) -> StoreResult.NotAnArchive
                else -> transaction.execute { persist(user, game, file, hash, metadata) }!!
            }
            return result
        } finally {
            if (result !is StoreResult.Stored) file.deleteIfExists()
        }
    }

    @Transactional
    fun delete(saves: Collection<GameSave>) {
        if (saves.isEmpty()) return
        gameSaveRepository.deleteAll(saves)
        deleteAfterCommit(saves.map { archiveFile(it) })
        log.info { "Deleted saves ${saves.map { it.id }}" }
    }

    fun setLocked(save: GameSave, locked: Boolean) {
        save.locked = locked
        gameSaveRepository.save(save)
    }

    // Rows cascade in the database, archives on disk do not
    @TransactionalEventListener(fallbackExecution = true)
    fun onUserDeleted(event: UserDeletedEvent) {
        savesRoot.resolve("${event.user.id}").toFile().deleteRecursively()
    }

    @TransactionalEventListener(fallbackExecution = true)
    fun onGameDeleted(event: GameDeletedEvent) {
        savesRoot.toFile().listFiles()?.forEach { it.resolve("${event.game.id}").deleteRecursively() }
    }

    private fun persist(user: User, game: Game, file: Path, hash: String, metadata: SaveUploadMetadata): StoreResult {
        val userId = user.id!!
        val gameId = game.id!!

        val latest = gameSaveRepository.findFirstByUserIdAndGameIdOrderByCreatedAtDescIdDesc(userId, gameId)
        if (latest != null && latest.contentHash == hash) return StoreResult.Unchanged(latest)
        // A client that never saw the latest version would overwrite another device's progress
        if (latest != null && !metadata.force && latest.id != metadata.baseSaveId) return StoreResult.Conflict(latest)

        val size = file.fileSize()
        if (exceedsQuota(userId, gameId, size)) return StoreResult.QuotaExceeded

        val saved = gameSaveRepository.save(
            GameSave(
                user = user,
                game = game,
                contentId = file.name,
                contentLength = size,
                contentHash = hash,
                platform = metadata.platform,
                installationId = normalizeInstallationId(metadata.installationId),
                deviceName = metadata.deviceName?.take(MAX_TEXT_LENGTH),
                ludusaviTitle = metadata.ludusaviTitle?.take(MAX_TEXT_LENGTH)
            )
        )
        prune(userId, gameId)
        log.info { "Stored save ${saved.id} for user $userId, game $gameId ($size bytes)" }
        return StoreResult.Stored(saved)
    }

    private fun maxSizeBytes(): Long = config.get(ConfigProperties.SaveSync.MaxSizeMb)!! * BYTES_PER_MB

    private fun maxVersionsPerGame(): Int = config.get(ConfigProperties.SaveSync.MaxVersionsPerGame)!!

    // Locked versions take no slot, so pinning one never squeezes out recent history
    private fun prune(userId: Long, gameId: Long) {
        delete(list(userId, gameId).filterNot { it.locked }.drop(maxVersionsPerGame()))
    }

    // Versions about to be pruned don't count, or a user at the limit could never upload again
    private fun exceedsQuota(userId: Long, gameId: Long, incoming: Long): Boolean {
        val reclaimable = list(userId, gameId).filterNot { it.locked }
            .drop(maxVersionsPerGame() - 1)
            .sumOf { it.contentLength }
        return gameSaveRepository.totalBytesForUser(userId) + incoming - reclaimable > quotaBytes()
    }

    // The filesystem can't roll back, so files go only once the rows are gone for good
    private fun deleteAfterCommit(files: List<Path>) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            files.forEach { it.deleteIfExists() }
            return
        }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = files.forEach { it.deleteIfExists() }
        })
    }

    private fun archiveFile(save: GameSave): Path = archiveFile(save.user.id!!, save.game.id!!, save.contentId)

    private fun archiveFile(userId: Long, gameId: Long, contentId: String): Path =
        savesRoot.resolve("$userId").resolve("$gameId").resolve(contentId)

    // Null once the stream exceeds maxSize
    private fun streamAndHash(input: InputStream, target: Path, maxSize: Long): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        target.outputStream().use { output ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                total += read
                if (total > maxSize) return null
                digest.update(buffer, 0, read)
                output.write(buffer, 0, read)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun looksLikeZip(path: Path): Boolean =
        path.inputStream().use { it.readNBytes(ZIP_MAGIC.size) }.contentEquals(ZIP_MAGIC)

    // Anything but a UUID is dropped rather than stored and rendered later
    private fun normalizeInstallationId(value: String?): String? =
        value?.let { runCatching { UUID.fromString(it.trim()).toString() }.getOrNull() }
}
