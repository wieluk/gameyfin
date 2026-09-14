package org.gameyfin.app.saves

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gameyfin.app.config.ConfigProperties
import org.gameyfin.app.config.ConfigService
import org.gameyfin.app.core.events.GameCreatedEvent
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
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.InputStream
import java.nio.file.Path
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
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

// Client input, untrusted
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
        private const val MAX_PROVIDER_IDS_LENGTH = 1024
        private const val BYTES_PER_MB = 1024L * 1024L
    }

    // Per user, not per game, because saves outlive their game
    private val savesRoot: Path = Path(storageRoot, "saves")
    private val transaction = TransactionTemplate(transactionManager)

    // Listeners run after commit and need their own transaction
    private val newTransaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    // One upload per user at a time, so the conflict and quota checks can't race (single process)
    private val uploadLocks = ConcurrentHashMap<Long, ReentrantLock>()

    fun enabled(): Boolean = config.get(ConfigProperties.SaveSync.Enabled)!!

    fun quotaBytes(): Long = config.get(ConfigProperties.SaveSync.MaxTotalPerUserMb)!! * BYTES_PER_MB

    fun list(userId: Long, gameId: Long): List<GameSave> =
        gameSaveRepository.findByUserIdAndGameIdOrderByCreatedAtDescIdDesc(userId, gameId)

    fun listForUser(userId: Long): List<GameSave> = gameSaveRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId)

    fun byId(saveId: Long): GameSave? = gameSaveRepository.findByIdOrNull(saveId)

    fun byIds(saveIds: Collection<Long>): List<GameSave> = gameSaveRepository.findAllById(saveIds)

    fun currentUser(): User? = getCurrentAuth()?.let { userService.getByUsername(it.name) }

    // The owner, or a role above the owner's
    fun canManage(save: GameSave, user: User): Boolean = save.user.id == user.id || userService.canManage(save.user)

    fun archivePath(save: GameSave): Path? {
        val archive = archiveFile(save)
        if (!archive.exists()) {
            log.warn { "Save ${save.id} has no archive on disk" }
            return null
        }
        return archive
    }

    // Streams outside the transaction so a slow upload doesn't hold a DB connection
    fun store(user: User, game: Game, input: InputStream, metadata: SaveUploadMetadata): StoreResult {
        if (!enabled()) return StoreResult.Disabled

        val file = archiveFile(user.id!!, UUID.randomUUID().toString())
        var result: StoreResult? = null
        try {
            file.parent.createDirectories()
            val hash = streamAndHash(input, file, maxSizeBytes())
            result = when {
                hash == null -> StoreResult.TooLarge
                !hash.equals(metadata.declaredHash.trim(), ignoreCase = true) -> StoreResult.HashMismatch
                !looksLikeZip(file) -> StoreResult.NotAnArchive
                else -> uploadLocks.computeIfAbsent(user.id!!) { ReentrantLock() }.withLock {
                    transaction.execute { persist(user, game, file, hash, metadata) }!!
                }
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

    // Rows cascade, files on disk don't
    @TransactionalEventListener(fallbackExecution = true)
    fun onUserDeleted(event: UserDeletedEvent) {
        savesRoot.resolve("${event.user.id}").toFile().deleteRecursively()
    }

    // A game re-added at the same path (remounted share) or with the same provider id (new folder) gets its saves back
    @TransactionalEventListener(fallbackExecution = true)
    fun onGameCreated(event: GameCreatedEvent) {
        val game = event.game
        val providerIds = providerIds(game)
        newTransaction.executeWithoutResult {
            val matching = gameSaveRepository.findByGameIsNull().filter { save ->
                save.gamePath == game.metadata.path || parseProviderIds(save.gameProviderIds).any { it in providerIds }
            }
            relink(matching, game)
        }
    }

    // Scans add the new copy of a game before deleting the old one, so onGameCreated can't catch that case
    @TransactionalEventListener(fallbackExecution = true)
    fun onGameDeleted(event: GameDeletedEvent) {
        newTransaction.executeWithoutResult {
            gameSaveRepository.findByGameIsNull()
                .filter { it.gamePath == event.game.metadata.path }
                .groupBy { it.gameProviderIds }
                .forEach { (providerIds, saves) -> findReplacement(providerIds)?.let { relink(saves, it) } }
        }
    }

    // Null unless exactly one game matches, e.g. not when two editions share an id
    private fun findReplacement(providerIds: String?): Game? =
        parseProviderIds(providerIds)
            .flatMap { gameSaveRepository.findGameIdsByProviderId(it.substringBefore('='), it.substringAfter('=')) }
            .distinct()
            .singleOrNull()
            ?.let { gameSaveRepository.findGameById(it) }

    private fun relink(saves: List<GameSave>, game: Game) {
        if (saves.isEmpty()) return
        saves.forEach {
            it.game = game
            it.gamePath = game.metadata.path
            it.gameProviderIds = encodeProviderIds(game)
        }
        gameSaveRepository.saveAll(saves)
        log.info { "Relinked saves ${saves.map { it.id }} to game ${game.id} at ${game.metadata.path}" }
    }

    private fun providerIds(game: Game): Set<String> =
        game.metadata.originalIds.map { (plugin, id) -> "${plugin.pluginId}=$id" }.toSet()

    private fun encodeProviderIds(game: Game): String? =
        providerIds(game).sorted().joinToString("\n").takeIf { it.isNotEmpty() && it.length <= MAX_PROVIDER_IDS_LENGTH }

    private fun parseProviderIds(raw: String?): Set<String> =
        raw?.lines()?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    private fun persist(user: User, game: Game, file: Path, hash: String, metadata: SaveUploadMetadata): StoreResult {
        val userId = user.id!!
        val gameId = game.id!!

        val latest = gameSaveRepository.findFirstByUserIdAndGameIdOrderByCreatedAtDescIdDesc(userId, gameId)
        if (latest != null && latest.contentHash == hash) return StoreResult.Unchanged(latest)
        // Otherwise a newer save from another device would be overwritten
        if (latest != null && !metadata.force && latest.id != metadata.baseSaveId) return StoreResult.Conflict(latest)

        val size = file.fileSize()
        if (exceedsQuota(userId, gameId, size)) return StoreResult.QuotaExceeded

        val saved = gameSaveRepository.save(
            GameSave(
                user = user,
                game = game,
                gameTitle = game.title?.take(MAX_TEXT_LENGTH),
                gamePath = game.metadata.path,
                gameProviderIds = encodeProviderIds(game),
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

    // Locked versions don't use a slot
    private fun prune(userId: Long, gameId: Long) {
        delete(list(userId, gameId).filterNot { it.locked }.drop(maxVersionsPerGame()))
    }

    // Ignore versions this upload prunes, or a full quota would block every upload
    private fun exceedsQuota(userId: Long, gameId: Long, incoming: Long): Boolean {
        val reclaimable = list(userId, gameId).filterNot { it.locked }
            .drop(maxVersionsPerGame() - 1)
            .sumOf { it.contentLength }
        return gameSaveRepository.totalBytesForUser(userId) + incoming - reclaimable > quotaBytes()
    }

    // Files can't roll back, so delete them after commit
    private fun deleteAfterCommit(files: List<Path>) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            files.forEach { it.deleteIfExists() }
            return
        }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = files.forEach { it.deleteIfExists() }
        })
    }

    private fun archiveFile(save: GameSave): Path = archiveFile(save.user.id!!, save.contentId)

    private fun archiveFile(userId: Long, contentId: String): Path = savesRoot.resolve("$userId").resolve(contentId)

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

    private fun normalizeInstallationId(value: String?): String? =
        value?.let { runCatching { UUID.fromString(it.trim()).toString() }.getOrNull() }
}
