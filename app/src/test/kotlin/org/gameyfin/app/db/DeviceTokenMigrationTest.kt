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
 * The device token table moved from a Flyway migration (V2.4.1.1) to a Liquibase changeset.
 * Databases that already have it must keep it, new ones must get it.
 */
class DeviceTokenMigrationTest {

    @Configuration
    @EnableAutoConfiguration
    class Config

    private val contextRunner = WebApplicationContextRunner()
        .withUserConfiguration(Config::class.java)
        .withPropertyValues("spring.liquibase.change-log=classpath:/db/changelog/db.changelog-master.yaml")

    @Test
    fun `a new database gets the device token table`() {
        val dbPath = Files.createTempDirectory("h2-new").resolve("testdb").toAbsolutePath()

        contextRunner.withPropertyValues(
            "spring.datasource.url=jdbc:h2:file:$dbPath",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
        ).run { context ->
            require(context.startupFailure == null) { "Context failed to start: ${context.startupFailure}" }
            val dataSource = context.getBean<DataSource>()
            assertEquals(0, count(dataSource, "SELECT COUNT(*) FROM DEVICE_TOKEN"))
            assertEquals("EXECUTED", execType(dataSource))
        }
    }

    @Test
    fun `a database migrated by Flyway keeps its device tokens`() {
        val dir = Files.createTempDirectory("h2-flyway")
        existingDatabase().copyTo(dir.resolve("gameyfin_db.mv.db").toFile())
        val url = "jdbc:h2:file:${dir.resolve("gameyfin_db").toAbsolutePath()}"

        DriverManager.getConnection(url, "gfadmin", "gameyfin").use { connection ->
            connection.createStatement().use { stmt ->
                stmt.execute(FLYWAY_V2_4_1_1)
                stmt.execute("INSERT INTO USERS (ID, USERNAME, ENABLED, EMAIL_CONFIRMED) VALUES (9001, 'flyway', TRUE, TRUE)")
                stmt.execute(
                    "INSERT INTO DEVICE_TOKEN (ID, USER_ID, NAME, TOKEN_HASH, CREATED_AT) " +
                            "VALUES (1, 9001, 'Desktop', '${"a".repeat(64)}', CURRENT_TIMESTAMP)"
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
            assertEquals(1, count(dataSource, "SELECT COUNT(*) FROM DEVICE_TOKEN"))
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
                s.executeQuery("SELECT EXECTYPE FROM DATABASECHANGELOG WHERE ID = 'fork-device-tokens'")
                    .use { it.next(); it.getString(1) }
            }
        }

    companion object {
        /** The former Flyway migration, as fork installations ran it. */
        private val FLYWAY_V2_4_1_1 = """
            CREATE SEQUENCE DEVICE_TOKEN_SEQ INCREMENT BY 50;
            CREATE TABLE DEVICE_TOKEN
            (
                ID           BIGINT                   NOT NULL PRIMARY KEY,
                USER_ID      BIGINT                   NOT NULL,
                NAME         CHARACTER VARYING(100)   NOT NULL,
                TOKEN_HASH   CHARACTER VARYING(64)    NOT NULL,
                CREATED_AT   TIMESTAMP WITH TIME ZONE NOT NULL,
                LAST_USED_AT TIMESTAMP WITH TIME ZONE,
                CONSTRAINT UK_DEVICE_TOKEN_HASH UNIQUE (TOKEN_HASH),
                CONSTRAINT FK_DEVICE_TOKEN_USER FOREIGN KEY (USER_ID) REFERENCES USERS ON DELETE CASCADE
            );
            CREATE INDEX IDX_DEVICE_TOKEN_USER ON DEVICE_TOKEN (USER_ID);
        """.trimIndent()
    }
}
