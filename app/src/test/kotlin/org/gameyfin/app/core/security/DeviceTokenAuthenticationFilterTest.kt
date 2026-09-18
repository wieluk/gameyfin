package org.gameyfin.app.core.security

import io.mockk.*
import jakarta.servlet.FilterChain
import org.gameyfin.app.core.Role
import org.gameyfin.app.users.devicetokens.DeviceToken
import org.gameyfin.app.users.devicetokens.DeviceTokenService
import org.gameyfin.app.users.entities.User
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import kotlin.test.*

class DeviceTokenAuthenticationFilterTest {

    private lateinit var service: DeviceTokenService
    private lateinit var filter: DeviceTokenAuthenticationFilter
    private lateinit var chain: FilterChain

    private val admin = User(id = 1L, username = "alice", email = "a@example.com", enabled = true, roles = listOf(Role.ADMIN))

    @BeforeEach
    fun setup() {
        service = mockk()
        filter = DeviceTokenAuthenticationFilter(service)
        chain = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
        clearAllMocks()
    }

    private fun request(authorization: String?) = MockHttpServletRequest("POST", "/connect/GameEndpoint/getAll").apply {
        authorization?.let { addHeader("Authorization", it) }
    }

    @Test
    fun `passes requests without a bearer token through untouched`() {
        val request = request(null)
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, chain)

        verify(exactly = 1) { chain.doFilter(request, response) }
        verify(exactly = 0) { service.authenticate(any()) }
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `authenticates a valid token with user rights only`() {
        every { service.authenticate("gyf_good") } returns DeviceToken(id = 7L, user = admin, name = "PC", tokenHash = "h")
        var seen: Any? = null
        every { chain.doFilter(any(), any()) } answers { seen = SecurityContextHolder.getContext().authentication }

        filter.doFilter(request("Bearer gyf_good"), MockHttpServletResponse(), chain)

        val auth = assertIs<DeviceTokenAuthentication>(seen)
        assertEquals("alice", auth.name)
        assertEquals(7L, auth.tokenId)
        assertTrue(auth.isAuthenticated)
        assertEquals(setOf(Role.Names.USER), auth.authorities.map { it.authority }.toSet())
        assertFalse(auth.isAdmin())
    }

    @Test
    fun `rejects an invalid token even when a session is present`() {
        every { service.authenticate("gyf_bad") } returns null
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("alice", null, emptyList())
        val response = MockHttpServletResponse()

        filter.doFilter(request("Bearer gyf_bad"), response, chain)

        assertEquals(401, response.status)
        assertEquals("Bearer", response.getHeader("WWW-Authenticate"))
        verify(exactly = 0) { chain.doFilter(any(), any()) }
    }

    @Test
    fun `rejects a token whose user has no roles`() {
        val roleless = User(id = 2L, username = "bob", email = "b@example.com", enabled = true)
        every { service.authenticate("gyf_x") } returns DeviceToken(id = 8L, user = roleless, name = "PC", tokenHash = "h")
        val response = MockHttpServletResponse()

        filter.doFilter(request("Bearer gyf_x"), response, chain)

        assertEquals(401, response.status)
    }

    @Test
    fun `bearer matcher ignores other schemes and empty tokens`() {
        assertTrue(DeviceTokenAuthenticationFilter.bearerRequests.matches(request("Bearer gyf_a")))
        assertTrue(DeviceTokenAuthenticationFilter.bearerRequests.matches(request("bearer gyf_a")))
        assertFalse(DeviceTokenAuthenticationFilter.bearerRequests.matches(request("Basic YWxpY2U6cGFzcw==")))
        assertFalse(DeviceTokenAuthenticationFilter.bearerRequests.matches(request("Bearer   ")))
        assertFalse(DeviceTokenAuthenticationFilter.bearerRequests.matches(request(null)))
    }

    @Test
    fun `a bearer token that is not ours is left to cookie login`() {
        val foreign = "Bearer eyJhbGciOiJIUzI1NiJ9.e30.signature"
        val request = request(foreign)
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, chain)

        verify(exactly = 1) { chain.doFilter(request, response) }
        verify(exactly = 0) { service.authenticate(any()) }
        assertEquals(200, response.status)
        assertNull(SecurityContextHolder.getContext().authentication)
        assertFalse(DeviceTokenAuthenticationFilter.bearerRequests.matches(request(foreign)))
    }
}
