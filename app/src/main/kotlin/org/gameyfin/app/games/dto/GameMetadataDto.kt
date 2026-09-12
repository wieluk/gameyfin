package org.gameyfin.app.games.dto

import com.fasterxml.jackson.annotation.JsonInclude

interface GameMetadataDto {
    val fileSize: Long
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class GameMetadataUserDto(
    override val fileSize: Long,
    /** Plugin id to that provider's own id, e.g. the Steam AppID. Public catalogue identifiers. */
    val originalIds: Map<String, String>? = null
) : GameMetadataDto

@JsonInclude(JsonInclude.Include.NON_NULL)
data class GameMetadataAdminDto(
    val path: String?,
    override val fileSize: Long,
    val fields: Map<String, GameFieldMetadataDto>?,
    val originalIds: Map<String, String>?,
    val downloadCount: Int,
    val matchConfirmed: Boolean
) : GameMetadataDto
