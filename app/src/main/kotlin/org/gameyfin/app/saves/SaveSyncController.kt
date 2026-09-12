package org.gameyfin.app.saves

import org.gameyfin.app.games.GameService
import org.gameyfin.app.saves.dto.GameSaveDto
import org.gameyfin.app.saves.entities.GameSave
import org.gameyfin.app.saves.entities.SavePlatform
import org.gameyfin.app.saves.extensions.toDto
import org.gameyfin.app.users.entities.User
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.io.InputStream

// Archives are binary, so they travel over plain MVC instead of Hilla's JSON-only RPC
@RestController
@RequestMapping("/saves/game/{gameId}")
class SaveSyncController(
    private val gameSaveService: GameSaveService,
    private val gameService: GameService
) {

    // Both sides of a contested upload, so the client can offer a real choice
    data class ConflictResponse(val remote: GameSaveDto, val baseSaveId: Long?)

    @GetMapping
    fun listVersions(@PathVariable gameId: Long): ResponseEntity<List<GameSaveDto>> = withUser { user ->
        ResponseEntity.ok(gameSaveService.list(user.id!!, gameId).map { it.toDto() })
    }

    @GetMapping("/{saveId}")
    fun downloadVersion(@PathVariable gameId: Long, @PathVariable saveId: Long): ResponseEntity<Resource> =
        withUser { user ->
            // Owner only, admins included, since a save can hold personal data
            val save = find(gameId, saveId)?.takeIf { it.user.id == user.id }
            val archive = save?.let { gameSaveService.archivePath(it) }
                ?: return@withUser ResponseEntity.notFound().build()

            ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(save.contentLength)
                .eTag(save.contentHash)
                // Served from the app's own origin, so make it plainly a download and never sniffable
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"save-${save.id}.zip\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(FileSystemResource(archive))
        }

    // Raw body rather than multipart: the size limit then applies while reading, not after buffering
    @PostMapping(consumes = ["application/zip"])
    fun upload(
        @PathVariable gameId: Long,
        body: InputStream,
        @RequestHeader("X-Content-Hash") contentHash: String,
        @RequestHeader(value = "X-Base-Save-Id", required = false) baseSaveId: Long?,
        @RequestHeader(value = "X-Installation-Id", required = false) installationId: String?,
        @RequestHeader(value = "X-Device-Name", required = false) deviceName: String?,
        @RequestHeader(value = "X-Save-Platform", required = false) platform: String?,
        @RequestHeader(value = "X-Ludusavi-Title", required = false) ludusaviTitle: String?,
        @RequestHeader(value = "X-Force", required = false) force: Boolean?
    ): ResponseEntity<Any> = withUser { user ->
        val game = try {
            gameService.getById(gameId)
        } catch (_: IllegalArgumentException) {
            return@withUser ResponseEntity.notFound().build()
        }

        val metadata = SaveUploadMetadata(
            declaredHash = contentHash,
            platform = parsePlatform(platform),
            installationId = installationId,
            deviceName = deviceName,
            ludusaviTitle = ludusaviTitle,
            baseSaveId = baseSaveId,
            force = force == true
        )

        when (val result = gameSaveService.store(user, game, body, metadata)) {
            is StoreResult.Stored -> ResponseEntity.status(HttpStatus.CREATED).body(result.save.toDto())
            is StoreResult.Unchanged -> ResponseEntity.noContent().build()
            is StoreResult.Conflict ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(ConflictResponse(result.latest.toDto(), baseSaveId))

            StoreResult.Disabled -> disabled()
            StoreResult.TooLarge, StoreResult.QuotaExceeded -> ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build()
            StoreResult.HashMismatch, StoreResult.NotAnArchive -> ResponseEntity.badRequest().build()
        }
    }

    @DeleteMapping("/{saveId}")
    fun deleteVersion(@PathVariable gameId: Long, @PathVariable saveId: Long): ResponseEntity<Void> = withUser { user ->
        val save = find(gameId, saveId)?.takeIf { gameSaveService.canManage(it, user) }
            ?: return@withUser ResponseEntity.notFound().build()

        gameSaveService.delete(listOf(save))
        ResponseEntity.noContent().build()
    }

    // Authentication itself is enforced in SecurityConfig
    private inline fun <T : Any> withUser(block: (User) -> ResponseEntity<T>): ResponseEntity<T> {
        if (!gameSaveService.enabled()) return disabled()
        val user = gameSaveService.currentUser() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return block(user)
    }

    // Reported as missing rather than forbidden, so a save id cannot be probed for existence
    private fun find(gameId: Long, saveId: Long): GameSave? =
        gameSaveService.byId(saveId)?.takeIf { it.game.id == gameId }

    private fun parsePlatform(raw: String?): SavePlatform =
        raw?.trim()?.uppercase()?.let { value -> SavePlatform.entries.find { it.name == value } } ?: SavePlatform.UNKNOWN

    // The route exists but does not accept calls while the feature is off
    private fun <T : Any> disabled(): ResponseEntity<T> = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build()
}
