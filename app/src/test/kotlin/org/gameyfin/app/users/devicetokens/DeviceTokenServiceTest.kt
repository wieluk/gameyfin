package org.gameyfin.app.users.devicetokens

import io.mockk.*
import org.gameyfin.app.core.Role
import org.gameyfin.app.users.entities.User
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import kotlin.test.*

class DeviceTokenServiceTest {

    private lateinit var repository: DeviceTokenRepository
    private lateinit var service: DeviceTokenService

    private val user = User(id = 1L, username = "alice", email = "a@example.com", enabled = true, roles = listOf(Role.USER))

    @BeforeEach
    fun setup() {
        repository = mockk()
        service = DeviceTokenService(repository)
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `create stores only the hash of a random secret`() {
        val saved = slot<DeviceToken>()
        every { repository.countByUser(user) } returns 0
        every { repository.save(capture(saved)) } answers { saved.captured }

        val secret = service.create(user, "  Steam Deck  ")

        assertTrue(secret.startsWith(DeviceTokenService.PREFIX))
        assertTrue(secret.length >= DeviceTokenService.PREFIX.length + 43)
        assertEquals(DeviceTokenService.hash(secret), saved.captured.tokenHash)
        assertFalse(saved.captured.tokenHash.contains(secret))
        assertEquals("Steam Deck", saved.captured.name)
        assertNotEquals(secret, service.create(user, "Other"))
    }

    @Test
    fun `create caps name length and falls back for blank names`() {
        val saved = mutableListOf<DeviceToken>()
        every { repository.countByUser(user) } returns 0
        every { repository.save(capture(saved)) } answers { firstArg() }

        service.create(user, "x".repeat(500))
        service.create(user, "   ")

        assertEquals(DeviceTokenService.MAX_NAME_LENGTH, saved[0].name.length)
        assertEquals("Unnamed device", saved[1].name)
    }

    @Test
    fun `create refuses beyond the per user limit`() {
        every { repository.countByUser(user) } returns DeviceTokenService.MAX_PER_USER.toLong()

        assertThrows<IllegalStateException> { service.create(user, "One too many") }
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `authenticate rejects secrets without the prefix without a lookup`() {
        assertNull(service.authenticate("not-a-device-token"))
        verify(exactly = 0) { repository.findByTokenHash(any()) }
    }

    @Test
    fun `authenticate rejects unknown secrets`() {
        every { repository.findByTokenHash(any()) } returns null

        assertNull(service.authenticate("gyf_unknown"))
    }

    @Test
    fun `authenticate rejects tokens of disabled users`() {
        val disabled = User(id = 2L, username = "bob", email = "b@example.com", enabled = false)
        every { repository.findByTokenHash(DeviceTokenService.hash("gyf_x")) } returns
                DeviceToken(id = 5L, user = disabled, name = "PC", tokenHash = DeviceTokenService.hash("gyf_x"))

        assertNull(service.authenticate("gyf_x"))
    }

    @Test
    fun `authenticate records last use at most hourly`() {
        val stale = DeviceToken(id = 5L, user = user, name = "PC", tokenHash = "h", lastUsedAt = Instant.now().minus(Duration.ofDays(1)))
        val fresh = DeviceToken(id = 6L, user = user, name = "PC", tokenHash = "h", lastUsedAt = Instant.now())
        every { repository.findByTokenHash(DeviceTokenService.hash("gyf_stale")) } returns stale
        every { repository.findByTokenHash(DeviceTokenService.hash("gyf_fresh")) } returns fresh
        every { repository.save(any()) } answers { firstArg() }

        assertSame(stale, service.authenticate("gyf_stale"))
        assertSame(fresh, service.authenticate("gyf_fresh"))

        verify(exactly = 1) { repository.save(stale) }
        verify(exactly = 0) { repository.save(fresh) }
    }

    @Test
    fun `revoke is scoped to the owner`() {
        every { repository.deleteByIdAndUser(5L, user) } returns 0

        assertFalse(service.revoke(user, 5L))
    }
}
