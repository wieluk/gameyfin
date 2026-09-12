package org.gameyfin.app.saves

import org.gameyfin.app.games.entities.Game
import org.gameyfin.app.saves.entities.GameSave
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface GameSaveRepository : JpaRepository<GameSave, Long> {

    fun findByUserIdOrderByCreatedAtDescIdDesc(userId: Long): List<GameSave>

    fun findByUserIdAndGameIdOrderByCreatedAtDescIdDesc(userId: Long, gameId: Long): List<GameSave>

    fun findFirstByUserIdAndGameIdOrderByCreatedAtDescIdDesc(userId: Long, gameId: Long): GameSave?

    @Query("SELECT COALESCE(SUM(s.contentLength), 0) FROM GameSave s WHERE s.user.id = :userId")
    fun totalBytesForUser(@Param("userId") userId: Long): Long

    // GAME.PATH is unique, so the path identifies the game
    @Modifying
    @Query("UPDATE GameSave s SET s.game = :game WHERE s.game IS NULL AND s.gamePath = :path")
    fun relinkOrphans(@Param("game") game: Game, @Param("path") path: String): Int
}
