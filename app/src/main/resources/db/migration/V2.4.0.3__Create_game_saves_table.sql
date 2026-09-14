-- Flyway Migration: V2.4.0.3
-- Purpose: Create GAME_SAVE table for the save synchronization feature.
-- Context: One row per uploaded save version. The archive bytes live on disk under the
--          file storage root and are referenced by CONTENT_ID; only metadata is stored here.
--          A save outlives its game: library scans delete games whose folder is missing, so
--          GAME_ID is cleared rather than cascaded. GAME_PATH and GAME_PROVIDER_IDS let the game claim
--          it back when it is re-added, also under a different folder.

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

-- Listing a user's versions for one game, newest first, is the hot path.
CREATE INDEX IDX_GAME_SAVE_USER_GAME_CREATED ON GAME_SAVE (USER_ID, GAME_ID, CREATED_AT DESC);

-- Supports the "identical content, skip the upload" check.
CREATE INDEX IDX_GAME_SAVE_USER_GAME_HASH ON GAME_SAVE (USER_ID, GAME_ID, CONTENT_HASH);
