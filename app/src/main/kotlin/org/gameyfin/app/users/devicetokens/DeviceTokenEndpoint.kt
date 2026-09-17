package org.gameyfin.app.users.devicetokens

import com.vaadin.hilla.Endpoint
import com.vaadin.hilla.exception.EndpointException
import jakarta.annotation.security.PermitAll
import org.gameyfin.app.core.security.DeviceTokenAuthentication
import org.gameyfin.app.core.security.getCurrentAuth
import org.gameyfin.app.core.security.requireSessionAuth
import org.gameyfin.app.users.UserService
import org.gameyfin.app.users.entities.User
import org.springframework.security.oauth2.core.oidc.user.OidcUser

@Endpoint
@PermitAll
class DeviceTokenEndpoint(
    private val deviceTokenService: DeviceTokenService,
    private val userService: UserService
) {

    // Web session only, so a stolen token cannot mint new ones
    fun create(name: String): String {
        requireSessionAuth()
        return try {
            deviceTokenService.create(currentUser(), name)
        } catch (e: IllegalStateException) {
            throw EndpointException(e.message)
        }
    }

    fun getAll(): List<DeviceTokenDto> {
        requireSessionAuth()
        return deviceTokenService.list(currentUser()).map { it.toDto() }
    }

    fun revoke(id: Long) {
        requireSessionAuth()
        if (!deviceTokenService.revoke(currentUser(), id)) throw EndpointException("Device not found")
    }

    fun revokeCurrent() {
        val auth = getCurrentAuth() as? DeviceTokenAuthentication
            ?: throw EndpointException("Not signed in with a device token")
        deviceTokenService.revoke(currentUser(), auth.tokenId)
    }

    private fun currentUser(): User {
        val auth = getCurrentAuth() ?: throw EndpointException("Unknown user")
        val principal = auth.principal
        val user = if (principal is OidcUser) {
            userService.findByOidcProviderId(principal.subject)
        } else {
            userService.getByUsername(auth.name)
        }
        return user ?: throw EndpointException("Unknown user")
    }
}
