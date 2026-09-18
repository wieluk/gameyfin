package org.gameyfin.app.core.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.csrf.CsrfTokenRepository
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository

// Saving the CSRF token would open a session on every bearer request
class BearerAwareCsrfTokenRepository(
    private val delegate: CsrfTokenRepository = HttpSessionCsrfTokenRepository()
) : CsrfTokenRepository {

    override fun generateToken(request: HttpServletRequest): CsrfToken = delegate.generateToken(request)

    override fun saveToken(token: CsrfToken?, request: HttpServletRequest, response: HttpServletResponse) {
        if (!DeviceTokenAuthenticationFilter.bearerRequests.matches(request)) delegate.saveToken(token, request, response)
    }

    override fun loadToken(request: HttpServletRequest): CsrfToken? =
        if (DeviceTokenAuthenticationFilter.bearerRequests.matches(request)) null else delegate.loadToken(request)
}
