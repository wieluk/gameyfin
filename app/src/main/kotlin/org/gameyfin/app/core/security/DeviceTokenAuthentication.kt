package org.gameyfin.app.core.security

import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.userdetails.UserDetails

class DeviceTokenAuthentication(
    private val user: UserDetails,
    val tokenId: Long
) : AbstractAuthenticationToken(user.authorities) {

    init {
        super.setAuthenticated(true)
    }

    override fun getCredentials(): Any? = null

    override fun getPrincipal(): Any = user
}
