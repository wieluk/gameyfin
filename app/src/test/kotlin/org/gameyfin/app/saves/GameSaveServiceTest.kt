package org.gameyfin.app.saves

import io.mockk.*
import org.gameyfin.app.config.ConfigProperties
import org.gameyfin.app.config.ConfigService
import org.gameyfin.app.core.events.GameDeletedEvent
import org.gameyfin.app.core.events.UserDeletedEvent
import org.gameyfin.app.games.entities.Game
import org.gameyfin.app.saves.entities.GameSave
import org.gameyfin.app.saves.entities.SavePlatform
import org.gameyfin.app.users.UserService
import org.gameyfin.app.users.entities.User
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.transaction.PlatformTransactionManager
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameSaveServiceTest {

    @TempDir
    lateinit var storageRoot: Path

    private lateinit var repository: GameSaveRepository
    private lateinit var userService: UserService
    private lateinit var config: ConfigService
    private lateinit var service: GameSaveService

    private val user = User(id = 1L, username = "alice", email = "alice@example.com")
    private val bob = User(id = 2L, username = "bob", email = "bob@example.com")
    private val game = mockk<Game> {
        every { id } returns 42L
        every { title } returns "Celeste"
    }

    // A minimal but genuine zip local file header, which is all the magic-byte check reads
    private val archive = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(60) { it.toByte() }
    private val archiveHash = sha256(archive)

    @BeforeEach
    fun setup() {
        repository = mockk()
        userService = mockk()
        config = mockk()

        every { config.get(ConfigProperties.SaveSync.Enabled) } returns true
        every { config.get(ConfigProperties.SaveSync.MaxSizeMb) } returns 500
        every { config.get(ConfigProperties.SaveSync.MaxVersionsPerGame) } returns 10
        every { config.get(ConfigProperties.SaveSync.MaxTotalPerUserMb) } returns 10240

        every { repository.totalBytesForUser(1L) } returns 0L
        every { repository.save(any()) } answers { firstArg<GameSave>().also { it.id = 100L } }
        every { repository.deleteAll(any<Iterable<GameSave>>()) } just runs

        service = GameSaveService(repository, userService, config, mockk(relaxed = true), storageRoot.toString())
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        clearAllMocks()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun archiveOf(save: GameSave): Path =
        storageRoot.resolve("saves/${save.user.id}/${save.game.id}/${save.contentId}")

    /** An already stored version, with its archive on disk. */
    private fun existing(
        id: Long,
        hash: String = "deadbeef",
        locked: Boolean = false,
        size: Long = 100L,
        owner: User = user,
        createdAt: Instant = Instant.parse("2026-01-01T00:00:00Z")
    ) = GameSave(
        id = id,
        user = owner,
        game = game,
        contentId = "blob-$id",
        contentLength = size,
        contentHash = hash,
        platform = SavePlatform.WINDOWS,
        locked = locked,
        createdAt = createdAt
    ).also {
        archiveOf(it).parent.createDirectories()
        archiveOf(it).writeBytes(ByteArray(1))
    }

    private fun versions(vararg saves: GameSave) {
        every { repository.findByUserIdAndGameIdOrderByCreatedAtDescIdDesc(1L, 42L) } returns saves.toList()
        every { repository.findFirstByUserIdAndGameIdOrderByCreatedAtDescIdDesc(1L, 42L) } returns saves.firstOrNull()
    }

    private fun upload(
        bytes: ByteArray = archive,
        hash: String = archiveHash,
        baseSaveId: Long? = null,
        force: Boolean = false
    ) = service.store(
        user, game, bytes.inputStream(),
        SaveUploadMetadata(declaredHash = hash, baseSaveId = baseSaveId, force = force)
    )

    private fun storedFiles(userId: Long = 1L, gameId: Long = 42L): List<Path> =
        storageRoot.resolve("saves/$userId/$gameId").toFile().listFiles()?.map { it.toPath() } ?: emptyList()

    @Test
    fun `store rejects everything while the feature is disabled`() {
        every { config.get(ConfigProperties.SaveSync.Enabled) } returns false

        assertIs<StoreResult.Disabled>(upload())
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `store accepts the first version for a game`() {
        versions()

        val result = upload()

        assertIs<StoreResult.Stored>(result)
        assertEquals(archiveHash, result.save.contentHash)
        assertEquals(archive.size.toLong(), result.save.contentLength)
        assertTrue(archiveOf(result.save).exists())
    }

    @Test
    fun `store short-circuits when the newest version has identical content`() {
        versions(existing(1L, hash = archiveHash))

        val result = upload(baseSaveId = 1L)

        assertIs<StoreResult.Unchanged>(result)
        assertEquals(1L, result.existing.id)
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `store reports a conflict when the client based on a stale version`() {
        val newest = existing(2L)
        versions(newest, existing(1L))

        val result = upload(baseSaveId = 1L)

        assertIs<StoreResult.Conflict>(result)
        assertEquals(2L, result.latest.id)
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `store accepts a stale base when forced and keeps the losing version`() {
        versions(existing(2L), existing(1L))

        val result = upload(baseSaveId = 1L, force = true)

        assertIs<StoreResult.Stored>(result)
        verify(exactly = 0) { repository.deleteAll(any<Iterable<GameSave>>()) }
    }

    @Test
    fun `store does not report a conflict when the server has no version at all`() {
        versions()

        assertIs<StoreResult.Stored>(upload(baseSaveId = 999L))
    }

    @Test
    fun `store rejects a payload whose bytes do not match the declared hash`() {
        versions()

        val result = upload(hash = "0".repeat(64))

        assertIs<StoreResult.HashMismatch>(result)
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `store rejects a payload that is not a zip archive`() {
        versions()
        val notAZip = "<!doctype html><script>alert(1)</script>".toByteArray()

        val result = service.store(
            user, game, notAZip.inputStream(),
            SaveUploadMetadata(declaredHash = sha256(notAZip))
        )

        assertIs<StoreResult.NotAnArchive>(result)
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `store rejects a payload over the size limit without buffering it`() {
        every { config.get(ConfigProperties.SaveSync.MaxSizeMb) } returns 0
        versions()

        assertIs<StoreResult.TooLarge>(upload())
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `a rejected upload leaves no archive behind`() {
        versions()

        assertIs<StoreResult.HashMismatch>(upload(hash = "0".repeat(64)))

        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun `a failed transaction leaves no archive behind`() {
        versions()
        every { repository.save(any()) } throws IllegalStateException("database gone")

        runCatching { upload() }

        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun `store rejects an upload that would exceed the per-user quota`() {
        every { config.get(ConfigProperties.SaveSync.MaxTotalPerUserMb) } returns 1
        every { repository.totalBytesForUser(1L) } returns 1024L * 1024L
        versions()

        assertIs<StoreResult.QuotaExceeded>(upload())
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `quota accounts for the versions retention is about to reclaim`() {
        every { config.get(ConfigProperties.SaveSync.MaxTotalPerUserMb) } returns 1
        every { config.get(ConfigProperties.SaveSync.MaxVersionsPerGame) } returns 1
        // At the limit, but the existing version will be pruned once the new one lands
        every { repository.totalBytesForUser(1L) } returns 1024L * 1024L
        versions(existing(1L, size = 1024L * 1024L))

        assertIs<StoreResult.Stored>(upload(baseSaveId = 1L))
    }

    @Test
    fun `retention prunes the oldest unlocked versions and deletes their archives`() {
        every { config.get(ConfigProperties.SaveSync.MaxVersionsPerGame) } returns 2
        val stored = existing(100L, hash = archiveHash)
        val keep = existing(3L)
        val locked = existing(2L, locked = true)
        val oldest = existing(1L)
        // The quota check sees the three existing versions; pruning runs after the save, so it
        // also sees the new one
        every { repository.findByUserIdAndGameIdOrderByCreatedAtDescIdDesc(1L, 42L) } returnsMany
                listOf(listOf(keep, locked, oldest), listOf(stored, keep, locked, oldest))
        every { repository.findFirstByUserIdAndGameIdOrderByCreatedAtDescIdDesc(1L, 42L) } returns keep

        val deleted = slot<Iterable<GameSave>>()
        every { repository.deleteAll(capture(deleted)) } just runs

        val result = upload(baseSaveId = 3L)

        assertIs<StoreResult.Stored>(result)
        // Two unlocked slots: the new version and `keep`. `oldest` goes, the locked one stays.
        assertEquals(listOf(1L), deleted.captured.map { it.id })
        assertFalse(archiveOf(oldest).exists())
        assertTrue(archiveOf(locked).exists())
        assertTrue(archiveOf(keep).exists())
    }

    @Test
    fun `delete removes the archive alongside the row`() {
        val save = existing(7L)

        service.delete(listOf(save))

        verify { repository.deleteAll(listOf(save)) }
        assertFalse(archiveOf(save).exists())
    }

    @Test
    fun `store drops an installation id that is not a uuid`() {
        versions()

        val result = service.store(
            user, game, archive.inputStream(),
            SaveUploadMetadata(declaredHash = archiveHash, installationId = "../../etc/passwd")
        )

        assertIs<StoreResult.Stored>(result)
        assertNull(result.save.installationId)
    }

    @Test
    fun `store truncates oversized device names rather than letting the database reject them`() {
        versions()

        val result = service.store(
            user, game, archive.inputStream(),
            SaveUploadMetadata(declaredHash = archiveHash, deviceName = "d".repeat(5000))
        )

        assertIs<StoreResult.Stored>(result)
        assertEquals(255, result.save.deviceName?.length)
    }

    @Test
    fun `deleting a user takes their archives with it`() {
        val mine = existing(1L)
        val theirs = existing(2L, owner = bob)

        service.onUserDeleted(UserDeletedEvent(this, user, "http://localhost"))

        assertFalse(archiveOf(mine).exists())
        assertTrue(archiveOf(theirs).exists())
    }

    @Test
    fun `deleting a game takes every user's archives for it with it`() {
        val mine = existing(1L)
        val theirs = existing(2L, owner = bob)
        val otherGame = mockk<Game> { every { id } returns 7L }
        val unrelated = GameSave(
            id = 3L, user = user, game = otherGame, contentId = "blob-3",
            contentLength = 1L, contentHash = "abc", platform = SavePlatform.WINDOWS
        ).also {
            archiveOf(it).parent.createDirectories()
            archiveOf(it).writeBytes(ByteArray(1))
        }

        service.onGameDeleted(GameDeletedEvent(this, game))

        assertFalse(archiveOf(mine).exists())
        assertFalse(archiveOf(theirs).exists())
        assertTrue(archiveOf(unrelated).exists())
    }

    @Test
    fun `an owner may manage their own save and a stronger role may manage someone else's`() {
        val own = existing(1L)
        val foreign = existing(2L, owner = bob)
        every { userService.canManage(bob) } returns true

        assertTrue(service.canManage(own, user))
        assertTrue(service.canManage(foreign, user))
    }

    @Test
    fun `an equal or weaker role may not manage someone else's save`() {
        val foreign = existing(2L, owner = bob)
        every { userService.canManage(bob) } returns false

        assertFalse(service.canManage(foreign, user))
    }
}
