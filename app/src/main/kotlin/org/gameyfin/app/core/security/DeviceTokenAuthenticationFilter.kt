package org.gameyfin.app.core.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.gameyfin.app.core.Role
import org.gameyfin.app.users.devicetokens.DeviceTokenService
import org.springframework.http.HttpHeaders
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.User
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.web.filter.OncePerRequestFilter

class DeviceTokenAuthenticationFilter(
    private val deviceTokenService: DeviceTokenService
) : OncePerRequestFilter() {

    companion object {
        private const val BEARER = "Bearer "

        fun bearerToken(request: HttpServletRequest): String? =
            request.getHeader(HttpHeaders.AUTHORIZATION)
                ?.takeIf { it.regionMatches(0, BEARER, 0, BEARER.length, ignoreCase = true) }
                ?.substring(BEARER.length)
                ?.trim()
                ?.ifEmpty { null }

        // Other bearer headers, e.g. from a reverse proxy, are left to the cookie login
        fun deviceToken(request: HttpServletRequest): String? =
            bearerToken(request)?.takeIf { it.startsWith(DeviceTokenService.PREFIX) }

        val bearerRequests = RequestMatcher { deviceToken(it) != null }
    }

    // Downloads and error pages redispatch without a session, so authenticate again
    override fun shouldNotFilterAsyncDispatch(): Boolean = false

    override fun shouldNotFilterErrorDispatch(): Boolean = false

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val secret = deviceToken(request) ?: return chain.doFilter(request, response)

        val token = deviceTokenService.authenticate(secret)
        if (token == null || token.user.roles.isEmpty()) {
            // Not sendError, which would redirect to the login page
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            return
        }

        // Never admin rights, even for admin accounts
        val principal = User.withUsername(token.user.username)
            .password("")
            .authorities(Role.Names.USER)
            .build()

        val strategy = SecurityContextHolder.getContextHolderStrategy()
        val context = strategy.createEmptyContext()
        context.authentication = DeviceTokenAuthentication(principal, token.id!!)
        strategy.context = context
        chain.doFilter(request, response)
    }
}
