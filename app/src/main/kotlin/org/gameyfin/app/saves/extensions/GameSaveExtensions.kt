package org.gameyfin.app.saves.extensions

import org.gameyfin.app.saves.dto.GameSaveDto
import org.gameyfin.app.saves.entities.GameSave

fun GameSave.toDto(): GameSaveDto {
    return GameSaveDto(
        id = this.id!!,
        gameId = this.game.id!!,
        gameTitle = this.game.title,
        sizeBytes = this.contentLength,
        contentHash = this.contentHash,
        platform = this.platform,
        installationId = this.installationId,
        deviceName = this.deviceName,
        ludusaviTitle = this.ludusaviTitle,
        locked = this.locked,
        createdAt = this.createdAt
    )
}
