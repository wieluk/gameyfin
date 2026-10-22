package org.gameyfin.app.users.devicetokens

import org.gameyfin.app.users.entities.User
import org.springframework.data.jpa.repository.JpaRepository

interface DeviceTokenRepository : JpaRepository<DeviceToken, Long> {
    fun findByTokenHash(tokenHash: String): DeviceToken?
    fun findAllByUserOrderByCreatedAtDesc(user: User): List<DeviceToken>
    fun countByUser(user: User): Long
    fun deleteByIdAndUser(id: Long, user: User): Long
    fun deleteAllByUser(user: User): Long
}
