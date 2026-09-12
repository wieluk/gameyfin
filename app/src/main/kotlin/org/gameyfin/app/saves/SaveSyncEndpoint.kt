package org.gameyfin.app.saves

import com.vaadin.hilla.Endpoint
import com.vaadin.hilla.exception.EndpointException
import jakarta.annotation.security.PermitAll
import jakarta.annotation.security.RolesAllowed
import org.gameyfin.app.core.Role
import org.gameyfin.app.saves.dto.GameSaveDto
import org.gameyfin.app.saves.entities.GameSave
import org.gameyfin.app.saves.extensions.toDto
import org.gameyfin.app.users.entities.User

// Metadata and management for the Cloud Saves UI; archive bytes move over SaveSyncController
@Endpoint
@PermitAll
class SaveSyncEndpoint(
    private val gameSaveService: GameSaveService
) {

    fun isEnabled(): Boolean = gameSaveService.enabled()

    fun getQuotaBytes(): Long {
        requireEnabled()
        return gameSaveService.quotaBytes()
    }

    fun getMySaves(): List<GameSaveDto> {
        requireEnabled()
        return gameSaveService.listForUser(currentUser().id!!).map { it.toDto() }
    }

    fun deleteSaves(saveIds: List<Long>) {
        requireEnabled()
        gameSaveService.delete(manageable(saveIds))
    }

    fun setLocked(saveId: Long, locked: Boolean) {
        requireEnabled()
        gameSaveService.setLocked(manageable(listOf(saveId)).single(), locked)
    }

    @RolesAllowed(Role.Names.ADMIN)
    fun getSavesForUser(userId: Long): List<GameSaveDto> {
        requireEnabled()
        return gameSaveService.listForUser(userId).map { it.toDto() }
    }

    private fun requireEnabled() {
        if (!gameSaveService.enabled()) throw EndpointException("Save sync is disabled")
    }

    private fun currentUser(): User = gameSaveService.currentUser() ?: throw EndpointException("Unknown user")

    // All or nothing, and a foreign save is reported like a missing one so ids cannot be probed
    private fun manageable(saveIds: List<Long>): List<GameSave> {
        val user = currentUser()
        val saves = gameSaveService.byIds(saveIds)
        if (saves.size != saveIds.distinct().size || saves.any { !gameSaveService.canManage(it, user) }) {
            throw EndpointException("Save not found")
        }
        return saves
    }
}
