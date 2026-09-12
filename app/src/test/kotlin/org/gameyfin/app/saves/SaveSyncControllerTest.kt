package org.gameyfin.app.saves

import io.mockk.*
import org.gameyfin.app.games.GameService
import org.gameyfin.app.games.entities.Game
import org.gameyfin.app.saves.entities.GameSave
import org.gameyfin.app.saves.entities.SavePlatform
import org.gameyfin.app.users.entities.User
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SaveSyncControllerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var gameSaveService: GameSaveService
    private lateinit var gameService: GameService
    private lateinit var controller: SaveSyncController

    private val alice = User(id = 1L, username = "alice", email = "alice@example.com")
    private val bob = User(id = 2L, username = "bob", email = "bob@example.com")
    private val game = mockk<Game> {
        every { id } returns 42L
        every { title } returns "Celeste"
    }

    @BeforeEach
    fun setup() {
        gameSaveService = mockk()
        gameService = mockk()
        controller = SaveSyncController(gameSaveService, gameService)

        every { gameSaveService.enabled() } returns true
        every { gameSaveService.currentUser() } returns alice
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        clearAllMocks()
    }

    private fun save(id: Long, owner: User = alice, gameId: Long = 42L): GameSave {
        val owned = mockk<Game> { every { this@mockk.id } returns gameId; every { title } returns "Celeste" }
        return GameSave(
            id = id,
            user = owner,
            game = owned,
            contentId = "blob-$id",
            contentLength = 1024L,
            contentHash = "a".repeat(64),
            platform = SavePlatform.WINDOWS,
            createdAt = Instant.parse("2026-01-01T00:00:00Z")
        )
    }

    private fun upload(
        body: InputStream = byteArrayOf(0x50, 0x4B, 0x03, 0x04).inputStream(),
        baseSaveId: Long? = null,
        force: Boolean? = null
    ) = controller.upload(
        gameId = 42L, body = body, contentHash = "a".repeat(64), baseSaveId = baseSaveId,
        installationId = null, deviceName = null, platform = "WINDOWS", ludusaviTitle = null, force = force
    )

    @Test
    fun `every route reports 405 while the feature is disabled`() {
        every { gameSaveService.enabled() } returns false

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, controller.listVersions(42L).statusCode)
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, controller.downloadVersion(42L, 1L).statusCode)
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, controller.deleteVersion(42L, 1L).statusCode)
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, upload().statusCode)
    }

    @Test
    fun `listVersions returns only the caller's versions`() {
        every { gameSaveService.list(1L, 42L) } returns listOf(save(1L), save(2L))

        val response = controller.listVersions(42L)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(listOf(1L, 2L), response.body?.map { it.id })
        verify { gameSaveService.list(1L, 42L) }
    }

    @Test
    fun `a caller without a session is rejected`() {
        every { gameSaveService.currentUser() } returns null

        assertEquals(HttpStatus.UNAUTHORIZED, controller.listVersions(42L).statusCode)
    }

    @Test
    fun `someone else's save is reported as missing, not as forbidden`() {
        every { gameSaveService.byId(9L) } returns save(9L, owner = bob)

        assertEquals(HttpStatus.NOT_FOUND, controller.downloadVersion(42L, 9L).statusCode)
    }

    @Test
    fun `an admin cannot download someone else's save either`() {
        val bobsSave = save(9L, owner = bob)
        every { gameSaveService.byId(9L) } returns bobsSave
        every { gameSaveService.canManage(bobsSave, alice) } returns true

        assertEquals(HttpStatus.NOT_FOUND, controller.downloadVersion(42L, 9L).statusCode)
    }

    @Test
    fun `a save belonging to another game is not found`() {
        every { gameSaveService.byId(9L) } returns save(9L, gameId = 99L)

        assertEquals(HttpStatus.NOT_FOUND, controller.downloadVersion(42L, 9L).statusCode)
    }

    @Test
    fun `downloads are served as an opaque attachment`() {
        val file = tempDir.resolve("blob-1")
        Files.write(file, byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        val own = save(1L)
        every { gameSaveService.byId(1L) } returns own
        every { gameSaveService.archivePath(own) } returns file

        val response = controller.downloadVersion(42L, 1L)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("application/octet-stream", response.headers.contentType.toString())
        assertEquals("nosniff", response.headers.getFirst("X-Content-Type-Options"))
        assertTrue(response.headers.getFirst(HttpHeaders.CONTENT_DISPOSITION)!!.startsWith("attachment"))
    }

    @Test
    fun `a row whose archive has vanished is reported as not found rather than as an error`() {
        val own = save(1L)
        every { gameSaveService.byId(1L) } returns own
        every { gameSaveService.archivePath(own) } returns null

        assertEquals(HttpStatus.NOT_FOUND, controller.downloadVersion(42L, 1L).statusCode)
    }

    @Test
    fun `a stored upload answers 201 with the new version`() {
        every { gameService.getById(42L) } returns game
        every { gameSaveService.store(alice, game, any(), any()) } returns StoreResult.Stored(save(5L))

        val response = upload()

        assertEquals(HttpStatus.CREATED, response.statusCode)
    }

    @Test
    fun `identical content answers 204 without creating a version`() {
        every { gameService.getById(42L) } returns game
        every { gameSaveService.store(alice, game, any(), any()) } returns StoreResult.Unchanged(save(5L))

        assertEquals(HttpStatus.NO_CONTENT, upload().statusCode)
    }

    @Test
    fun `a stale base answers 409 carrying the version the client is missing`() {
        every { gameService.getById(42L) } returns game
        every { gameSaveService.store(alice, game, any(), any()) } returns StoreResult.Conflict(save(7L))

        val response = upload(baseSaveId = 3L)

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        val body = response.body as SaveSyncController.ConflictResponse
        assertEquals(7L, body.remote.id)
        assertEquals(3L, body.baseSaveId)
    }

    @Test
    fun `an oversized or over-quota upload answers 413`() {
        every { gameService.getById(42L) } returns game
        every { gameSaveService.store(alice, game, any(), any()) } returns StoreResult.TooLarge
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, upload().statusCode)

        every { gameSaveService.store(alice, game, any(), any()) } returns StoreResult.QuotaExceeded
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, upload().statusCode)
    }

    @Test
    fun `a bad hash or a non-archive answers 400`() {
        every { gameService.getById(42L) } returns game
        every { gameSaveService.store(alice, game, any(), any()) } returns StoreResult.HashMismatch
        assertEquals(HttpStatus.BAD_REQUEST, upload().statusCode)

        every { gameSaveService.store(alice, game, any(), any()) } returns StoreResult.NotAnArchive
        assertEquals(HttpStatus.BAD_REQUEST, upload().statusCode)
    }

    @Test
    fun `uploading to an unknown game is not found`() {
        every { gameService.getById(42L) } throws IllegalArgumentException("Game with id 42 not found")

        assertEquals(HttpStatus.NOT_FOUND, upload().statusCode)
    }

    @Test
    fun `an unparseable platform header degrades to UNKNOWN rather than failing`() {
        every { gameService.getById(42L) } returns game
        val metadata = slot<SaveUploadMetadata>()
        every { gameSaveService.store(alice, game, any(), capture(metadata)) } returns StoreResult.Stored(save(5L))

        controller.upload(
            gameId = 42L, body = byteArrayOf(0x50, 0x4B, 0x03, 0x04).inputStream(), contentHash = "a".repeat(64),
            baseSaveId = null, installationId = null, deviceName = null, platform = "SteamDeck",
            ludusaviTitle = null, force = null
        )

        assertEquals(SavePlatform.UNKNOWN, metadata.captured.platform)
    }

    @Test
    fun `a user cannot delete someone else's save but a stronger role can`() {
        val bobsSave = save(9L, owner = bob)
        every { gameSaveService.byId(9L) } returns bobsSave
        every { gameSaveService.canManage(bobsSave, alice) } returns false
        assertEquals(HttpStatus.NOT_FOUND, controller.deleteVersion(42L, 9L).statusCode)

        every { gameSaveService.canManage(bobsSave, alice) } returns true
        every { gameSaveService.delete(listOf(bobsSave)) } just runs

        assertEquals(HttpStatus.NO_CONTENT, controller.deleteVersion(42L, 9L).statusCode)
        verify { gameSaveService.delete(listOf(bobsSave)) }
    }
}
