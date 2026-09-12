package org.gameyfin.app.saves.dto

import org.gameyfin.app.saves.entities.SavePlatform
import java.time.Instant

data class GameSaveDto(
    val id: Long,
    val gameId: Long,
    val gameTitle: String?,
    val sizeBytes: Long,
    val contentHash: String,
    val platform: SavePlatform,
    val installationId: String?,
    val deviceName: String?,
    val ludusaviTitle: String?,
    val locked: Boolean,
    val createdAt: Instant?
)
