package org.gameyfin.app.core.security

import com.vaadin.hilla.exception.EndpointException
import org.gameyfin.app.core.Role
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder

fun getCurrentAuth(): Authentication? {
    return SecurityContextHolder.getContext().authentication
}

fun isCurrentUserAdmin(): Boolean {
    return getCurrentAuth()?.isAdmin() ?: false
}

fun isDeviceTokenAuth(): Boolean {
    return getCurrentAuth() is DeviceTokenAuthentication
}

fun requireSessionAuth() {
    if (isDeviceTokenAuth()) throw EndpointException("Requires signing in on the web")
}

fun Authentication.isAdmin(): Boolean {
    return this.authorities.any { it.authority == Role.Names.ADMIN || it.authority == Role.Names.SUPERADMIN }
}