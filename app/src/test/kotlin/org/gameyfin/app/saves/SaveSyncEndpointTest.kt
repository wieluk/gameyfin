package org.gameyfin.app.saves

import com.vaadin.hilla.exception.EndpointException
import io.mockk.*
import org.gameyfin.app.games.entities.Game
import org.gameyfin.app.saves.entities.GameSave
import org.gameyfin.app.saves.entities.SavePlatform
import org.gameyfin.app.users.entities.User
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SaveSyncEndpointTest {

    private lateinit var gameSaveService: GameSaveService
    private lateinit var endpoint: SaveSyncEndpoint

    private val alice = User(id = 1L, username = "alice", email = "alice@example.com")
    private val bob = User(id = 2L, username = "bob", email = "bob@example.com")

    @BeforeEach
    fun setup() {
        gameSaveService = mockk()
        endpoint = SaveSyncEndpoint(gameSaveService)

        every { gameSaveService.enabled() } returns true
        every { gameSaveService.currentUser() } returns alice
        every { gameSaveService.delete(any()) } just runs
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        clearAllMocks()
    }

    private fun save(id: Long, owner: User = alice) = GameSave(
        id = id,
        user = owner,
        game = mockk<Game> { every { this@mockk.id } returns 42L; every { title } returns "Celeste" },
        contentId = "blob-$id",
        contentLength = 1024L,
        contentHash = "a".repeat(64),
        platform = SavePlatform.WINDOWS,
        createdAt = Instant.parse("2026-01-01T00:00:00Z")
    )

    /** Makes the given saves resolvable and manageable by the signed-in user. */
    private fun manageable(vararg saves: GameSave) {
        every { gameSaveService.byIds(saves.map { it.id!! }) } returns saves.toList()
        saves.forEach { every { gameSaveService.canManage(it, alice) } returns true }
    }

    @Test
    fun `getMySaves reads the signed-in user's saves and never takes a user id`() {
        every { gameSaveService.listForUser(1L) } returns listOf(save(1L))

        val result = endpoint.getMySaves()

        assertEquals(listOf(1L), result.map { it.id })
        verify { gameSaveService.listForUser(1L) }
    }

    @Test
    fun `getQuotaBytes reports the configured ceiling`() {
        every { gameSaveService.quotaBytes() } returns 4096L

        assertEquals(4096L, endpoint.getQuotaBytes())
    }

    @Test
    fun `deleteSaves removes every listed version`() {
        val own = listOf(save(1L), save(2L))
        manageable(*own.toTypedArray())

        endpoint.deleteSaves(listOf(1L, 2L))

        verify { gameSaveService.delete(own) }
    }

    @Test
    fun `deleteSaves refuses a save belonging to someone else and deletes nothing`() {
        val mine = save(1L)
        val theirs = save(9L, owner = bob)
        every { gameSaveService.byIds(listOf(1L, 9L)) } returns listOf(mine, theirs)
        every { gameSaveService.canManage(mine, alice) } returns true
        every { gameSaveService.canManage(theirs, alice) } returns false

        assertFailsWith<EndpointException> { endpoint.deleteSaves(listOf(1L, 9L)) }
        verify(exactly = 0) { gameSaveService.delete(any()) }
    }

    @Test
    fun `deleteSaves lets a stronger role clear up someone else's save`() {
        val theirs = save(9L, owner = bob)
        manageable(theirs)

        endpoint.deleteSaves(listOf(9L))

        verify { gameSaveService.delete(listOf(theirs)) }
    }

    @Test
    fun `a missing save is reported the same way as one that is not yours`() {
        every { gameSaveService.byIds(listOf(9L)) } returns emptyList()

        val error = assertFailsWith<EndpointException> { endpoint.deleteSaves(listOf(9L)) }
        assertTrue(error.message!!.contains("not found"))
        verify(exactly = 0) { gameSaveService.delete(any()) }
    }

    @Test
    fun `setLocked marks a version exempt from pruning`() {
        val own = save(1L)
        manageable(own)
        every { gameSaveService.setLocked(own, true) } just runs

        endpoint.setLocked(1L, true)

        verify { gameSaveService.setLocked(own, true) }
    }

    @Test
    fun `every method except isEnabled refuses while the feature is off`() {
        every { gameSaveService.enabled() } returns false

        assertEquals(false, endpoint.isEnabled())
        assertFailsWith<EndpointException> { endpoint.getMySaves() }
        assertFailsWith<EndpointException> { endpoint.getQuotaBytes() }
        assertFailsWith<EndpointException> { endpoint.deleteSaves(listOf(1L)) }
        assertFailsWith<EndpointException> { endpoint.setLocked(1L, true) }
        assertFailsWith<EndpointException> { endpoint.getSavesForUser(2L) }

        verify(exactly = 0) { gameSaveService.listForUser(any()) }
        verify(exactly = 0) { gameSaveService.delete(any()) }
    }
}
