package org.gameyfin.app.saves

import org.gameyfin.app.games.entities.Game
import org.gameyfin.app.saves.entities.GameSave
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface GameSaveRepository : JpaRepository<GameSave, Long> {

    fun findByUserIdOrderByCreatedAtDescIdDesc(userId: Long): List<GameSave>

    fun findByUserIdAndGameIdOrderByCreatedAtDescIdDesc(userId: Long, gameId: Long): List<GameSave>

    fun findFirstByUserIdAndGameIdOrderByCreatedAtDescIdDesc(userId: Long, gameId: Long): GameSave?

    @Query("SELECT COALESCE(SUM(s.contentLength), 0) FROM GameSave s WHERE s.user.id = :userId")
    fun totalBytesForUser(@Param("userId") userId: Long): Long

    fun findByGameIsNull(): List<GameSave>

    @Query(
        value = "SELECT DISTINCT GAME_ID FROM GAME_ORIGINAL_IDS WHERE ORIGINAL_IDS_KEY = :pluginId AND ORIGINAL_IDS = :originalId",
        nativeQuery = true
    )
    fun findGameIdsByProviderId(@Param("pluginId") pluginId: String, @Param("originalId") originalId: String): List<Long>

    @Query("SELECT g FROM Game g WHERE g.id = :id")
    fun findGameById(@Param("id") id: Long): Game?
}
