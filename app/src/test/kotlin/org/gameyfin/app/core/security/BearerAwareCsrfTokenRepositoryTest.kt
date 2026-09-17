package org.gameyfin.app.core.security

import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BearerAwareCsrfTokenRepositoryTest {

    private val repository = BearerAwareCsrfTokenRepository()

    @Test
    fun `bearer requests get a token without a session`() {
        val request = MockHttpServletRequest().apply { addHeader("Authorization", "Bearer gyf_x") }
        val token = repository.generateToken(request)

        repository.saveToken(token, request, MockHttpServletResponse())

        assertNull(request.getSession(false))
        assertNull(repository.loadToken(request))
    }

    @Test
    fun `other requests keep the session token`() {
        val request = MockHttpServletRequest()
        val token = repository.generateToken(request)

        repository.saveToken(token, request, MockHttpServletResponse())

        assertNotNull(request.getSession(false))
        assertEquals(token.token, repository.loadToken(request)?.token)
    }
}
