package org.gameyfin.app.saves

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
}
