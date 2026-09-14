package org.gameyfin.app.db

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Configuration
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import javax.sql.DataSource
import kotlin.test.assertEquals

/**
 * The save table moved from a Flyway migration (V2.4.0.3) to a Liquibase changeset.
 * Databases that already have it must keep it, new ones must get it.
 */
class GameSaveMigrationTest {

    @Configuration
    @EnableAutoConfiguration
    class Config

    private val contextRunner = WebApplicationContextRunner()
        .withUserConfiguration(Config::class.java)
        .withPropertyValues("spring.liquibase.change-log=classpath:/db/changelog/db.changelog-master.yaml")

    @Test
    fun `a new database gets the save table`() {
        val dbPath = Files.createTempDirectory("h2-new").resolve("testdb").toAbsolutePath()

        contextRunner.withPropertyValues(
            "spring.datasource.url=jdbc:h2:file:$dbPath",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
        ).run { context ->
            require(context.startupFailure == null) { "Context failed to start: ${context.startupFailure}" }
            val dataSource = context.getBean<DataSource>()
            assertEquals(0, count(dataSource, "SELECT COUNT(*) FROM GAME_SAVE"))
            assertEquals("EXECUTED", execType(dataSource))
        }
    }

    @Test
    fun `a database migrated by Flyway keeps its saves`() {
        val dir = Files.createTempDirectory("h2-flyway")
        existingDatabase().copyTo(dir.resolve("gameyfin_db.mv.db").toFile())
        val url = "jdbc:h2:file:${dir.resolve("gameyfin_db").toAbsolutePath()}"

        DriverManager.getConnection(url, "gfadmin", "gameyfin").use { connection ->
            connection.createStatement().use { stmt ->
                stmt.execute(FLYWAY_V2_4_0_3)
                stmt.execute("INSERT INTO USERS (ID, USERNAME, ENABLED, EMAIL_CONFIRMED) VALUES (9001, 'flyway', TRUE, TRUE)")
                stmt.execute(
                    "INSERT INTO GAME_SAVE (ID, CREATED_AT, USER_ID, GAME_PATH, CONTENT_ID, CONTENT_LENGTH, " +
                            "CONTENT_HASH, PLATFORM) VALUES (1, CURRENT_TIMESTAMP, 9001, '/games/Celeste', 'content', 1, " +
                            "'${"a".repeat(64)}', 'WINDOWS')"
                )
            }
        }

        contextRunner.withPropertyValues(
            "spring.datasource.url=$url",
            "spring.datasource.username=gfadmin",
            "spring.datasource.password=gameyfin",
        ).run { context ->
            require(context.startupFailure == null) { "Context failed to start: ${context.startupFailure}" }
            val dataSource = context.getBean<DataSource>()
            assertEquals(1, count(dataSource, "SELECT COUNT(*) FROM GAME_SAVE"))
            assertEquals("MARK_RAN", execType(dataSource))
        }
    }

    private fun existingDatabase(): File =
        listOf("src/test/resources/testdb/gameyfin_db.mv.db", "app/src/test/resources/testdb/gameyfin_db.mv.db")
            .map(::File).firstOrNull { it.exists() }
            ?: error("Existing H2 database file not found")

    private fun count(dataSource: DataSource, sql: String): Int =
        dataSource.connection.use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { it.next(); it.getInt(1) } } }

    private fun execType(dataSource: DataSource): String =
        dataSource.connection.use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT EXECTYPE FROM DATABASECHANGELOG WHERE ID = 'fork-save-sync-game-save'")
                    .use { it.next(); it.getString(1) }
            }
        }

    companion object {
        /** The former Flyway migration, as fork installations ran it. */
        private val FLYWAY_V2_4_0_3 = """
            CREATE SEQUENCE GAME_SAVE_SEQ INCREMENT BY 50;
            CREATE TABLE GAME_SAVE
            (
                ID              BIGINT                   NOT NULL PRIMARY KEY,
                CREATED_AT      TIMESTAMP WITH TIME ZONE NOT NULL,
                USER_ID         BIGINT                   NOT NULL,
                GAME_ID         BIGINT,
                GAME_TITLE      CHARACTER VARYING(255),
                GAME_PATH       CHARACTER VARYING(255)   NOT NULL,
                GAME_PROVIDER_IDS CHARACTER VARYING(1024),
                CONTENT_ID      CHARACTER VARYING(255)   NOT NULL,
                CONTENT_LENGTH  BIGINT                   NOT NULL,
                CONTENT_HASH    CHARACTER VARYING(64)    NOT NULL,
                PLATFORM        CHARACTER VARYING(255)   NOT NULL,
                INSTALLATION_ID CHARACTER VARYING(255),
                DEVICE_NAME     CHARACTER VARYING(255),
                LUDUSAVI_TITLE  CHARACTER VARYING(255),
                LOCKED          BOOLEAN                  NOT NULL DEFAULT FALSE,
                CONSTRAINT FK_GAME_SAVE_USER FOREIGN KEY (USER_ID) REFERENCES USERS ON DELETE CASCADE,
                CONSTRAINT FK_GAME_SAVE_GAME FOREIGN KEY (GAME_ID) REFERENCES GAME ON DELETE SET NULL
            );
            CREATE INDEX IDX_GAME_SAVE_USER_GAME_CREATED ON GAME_SAVE (USER_ID, GAME_ID, CREATED_AT DESC);
            CREATE INDEX IDX_GAME_SAVE_USER_GAME_HASH ON GAME_SAVE (USER_ID, GAME_ID, CONTENT_HASH);
        """.trimIndent()
    }
}
