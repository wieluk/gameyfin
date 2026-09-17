package org.gameyfin.app.users.devicetokens

import com.vaadin.hilla.exception.EndpointException
import io.mockk.*
import org.gameyfin.app.core.Role
import org.gameyfin.app.core.security.DeviceTokenAuthentication
import org.gameyfin.app.users.UserService
import org.gameyfin.app.users.entities.User
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.User as SpringUser
import kotlin.test.assertEquals

class DeviceTokenEndpointTest {

    private lateinit var service: DeviceTokenService
    private lateinit var userService: UserService
    private lateinit var endpoint: DeviceTokenEndpoint

    private val user = User(id = 1L, username = "alice", email = "a@example.com", enabled = true, roles = listOf(Role.USER))

    @BeforeEach
    fun setup() {
        service = mockk()
        userService = mockk()
        endpoint = DeviceTokenEndpoint(service, userService)
        every { userService.getByUsername("alice") } returns user
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
        clearAllMocks()
    }

    private fun signInWithSession() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("alice", null, emptyList())
    }

    private fun signInWithToken(tokenId: Long) {
        val principal = SpringUser.withUsername("alice").password("").authorities(Role.Names.USER).build()
        SecurityContextHolder.getContext().authentication = DeviceTokenAuthentication(principal, tokenId)
    }

    @Test
    fun `create works from a web session`() {
        signInWithSession()
        every { service.create(user, "Deck") } returns "gyf_secret"

        assertEquals("gyf_secret", endpoint.create("Deck"))
    }

    @Test
    fun `a device token cannot create, list or revoke tokens`() {
        signInWithToken(3L)

        assertThrows<EndpointException> { endpoint.create("Another") }
        assertThrows<EndpointException> { endpoint.getAll() }
        assertThrows<EndpointException> { endpoint.revoke(4L) }
        verify(exactly = 0) { service.create(any(), any()) }
        verify(exactly = 0) { service.revoke(any(), any()) }
    }

    @Test
    fun `revokeCurrent revokes only the calling token`() {
        signInWithToken(3L)
        every { service.revoke(user, 3L) } returns true

        endpoint.revokeCurrent()

        verify(exactly = 1) { service.revoke(user, 3L) }
    }

    @Test
    fun `revokeCurrent needs a device token`() {
        signInWithSession()

        assertThrows<EndpointException> { endpoint.revokeCurrent() }
    }

    @Test
    fun `revoke reports tokens that are not the caller's as missing`() {
        signInWithSession()
        every { service.revoke(user, 99L) } returns false

        assertThrows<EndpointException> { endpoint.revoke(99L) }
    }
}
