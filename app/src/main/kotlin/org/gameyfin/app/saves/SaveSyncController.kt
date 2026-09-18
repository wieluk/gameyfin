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

@RestController
@RequestMapping("/saves")
class SaveSyncController(
    private val gameSaveService: GameSaveService,
    private val gameService: GameService
) {

    data class ConflictResponse(val remote: GameSaveDto, val baseSaveId: Long?)

    @GetMapping("/game/{gameId}")
    fun listVersions(@PathVariable gameId: Long): ResponseEntity<List<GameSaveDto>> = withUser { user ->
        ResponseEntity.ok(gameSaveService.list(user.id!!, gameId).map { it.toDto() })
    }

    @GetMapping("/game/{gameId}/{saveId}")
    fun downloadVersion(@PathVariable gameId: Long, @PathVariable saveId: Long): ResponseEntity<Resource> =
        withUser { user -> serve(find(gameId, saveId), user) }

    @GetMapping("/{saveId}")
    fun download(@PathVariable saveId: Long): ResponseEntity<Resource> =
        withUser { user -> serve(gameSaveService.byId(saveId), user) }

    // Raw body, not multipart, so the size limit applies while streaming
    @PostMapping("/game/{gameId}", consumes = ["application/zip"])
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

    @DeleteMapping("/game/{gameId}/{saveId}")
    fun deleteVersion(@PathVariable gameId: Long, @PathVariable saveId: Long): ResponseEntity<Void> = withUser { user ->
        val save = find(gameId, saveId)?.takeIf { gameSaveService.canManage(it, user) }
            ?: return@withUser ResponseEntity.notFound().build()

        gameSaveService.delete(listOf(save))
        ResponseEntity.noContent().build()
    }

    private inline fun <T : Any> withUser(block: (User) -> ResponseEntity<T>): ResponseEntity<T> {
        if (!gameSaveService.enabled()) return disabled()
        val user = gameSaveService.currentUser() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return block(user)
    }

    private fun serve(save: GameSave?, user: User): ResponseEntity<Resource> {
        val allowed = save?.takeIf { gameSaveService.canManage(it, user) }
        val archive = allowed?.let { gameSaveService.archivePath(it) } ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(allowed.contentLength)
            .eTag(allowed.contentHash)
            // Same origin as the app, so the browser must never render it
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"save-${allowed.id}.zip\"")
            .header("X-Content-Type-Options", "nosniff")
            .body(FileSystemResource(archive))
    }

    // 404 instead of 403, so save ids can't be probed
    private fun find(gameId: Long, saveId: Long): GameSave? =
        gameSaveService.byId(saveId)?.takeIf { it.game?.id == gameId }

    private fun parsePlatform(raw: String?): SavePlatform =
        raw?.trim()?.uppercase()?.let { value -> SavePlatform.entries.find { it.name == value } } ?: SavePlatform.UNKNOWN

    private fun <T : Any> disabled(): ResponseEntity<T> = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build()
}
