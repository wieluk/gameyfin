package org.gameyfin.app.users.devicetokens

import java.time.Instant

data class DeviceTokenDto(
    val id: Long,
    val name: String,
    val createdAt: Instant,
    val lastUsedAt: Instant?
)

fun DeviceToken.toDto() = DeviceTokenDto(
    id = id!!,
    name = name,
    createdAt = createdAt!!,
    lastUsedAt = lastUsedAt
)
