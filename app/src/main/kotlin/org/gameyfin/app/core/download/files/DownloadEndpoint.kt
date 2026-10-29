package org.gameyfin.app.core.download.files

import com.google.common.io.ByteStreams
import com.vaadin.flow.server.auth.AnonymousAllowed
import jakarta.servlet.http.HttpServletRequest
import org.gameyfin.app.core.LookupPolicy
import org.gameyfin.app.core.annotations.DynamicPublicAccess
import org.gameyfin.app.core.getRemoteIp
import org.gameyfin.app.core.security.getCurrentAuth
import org.gameyfin.app.games.GameService
import org.gameyfin.app.games.entities.Game
import org.gameyfin.pluginapi.download.FileDownload
import org.gameyfin.pluginapi.download.LinkDownload
import org.gameyfin.pluginapi.download.ResumableFileDownload
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.context.request.async.DeferredResult
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executor
import java.util.concurrent.Executors

@RestController
@RequestMapping("/download")
@DynamicPublicAccess
@AnonymousAllowed
class DownloadEndpoint(
    private val downloadService: DownloadService,
    private val gameService: GameService,
) {

    private val downloadExecutor: Executor = Executors.newVirtualThreadPerTaskExecutor()

    @GetMapping("/{gameId}")
    fun downloadGame(
        @PathVariable gameId: Long,
        @RequestParam provider: String,
        request: HttpServletRequest
    ): DeferredResult<ResponseEntity<StreamingResponseBody>> {
        val deferredResult = DeferredResult<ResponseEntity<StreamingResponseBody>>()
        val rangeHeader = request.getHeader(HttpHeaders.RANGE)
        val ifRangeHeader = request.getHeader(HttpHeaders.IF_RANGE)

        downloadExecutor.execute {
            try {
                val game = gameService.getById(gameId)
                val sessionId = request.session.id
                val remoteIp = request.getRemoteIp(LookupPolicy.IPV4_PREFERRED)

                val result = when (val download = downloadService.getDownload(game.metadata.path, provider)) {
                    is FileDownload -> {
                        gameService.incrementDownloadCount(game)

                        val responseBuilder = ResponseEntity.ok()
                            .header("Content-Disposition", createContentDispositionHeader(filenameFor(game, download.fileExtension)))
                            .header("Content-Type", "application/octet-stream")

                        val downloadSize = download.size
                        if (downloadSize != null) {
                            responseBuilder.contentLength(downloadSize)
                        }

                        responseBuilder.body(StreamingResponseBody { outputStream ->
                            downloadService.processDownload(
                                download.data,
                                outputStream,
                                game,
                                getCurrentAuth()?.name,
                                sessionId,
                                remoteIp
                            )
                        })
                    }

                    is ResumableFileDownload -> resumableResponse(download, game, rangeHeader, ifRangeHeader) { data, outputStream ->
                        downloadService.processDownload(data, outputStream, game, getCurrentAuth()?.name, sessionId, remoteIp)
                    }

                    is LinkDownload -> {
                        TODO("Handle download link")
                    }
                }

                @Suppress("UsePropertyAccessSyntax")
                deferredResult.setResult(result)
            } catch (e: Exception) {
                deferredResult.setErrorResult(e)
            }
        }

        return deferredResult
    }

    private fun resumableResponse(
        download: ResumableFileDownload,
        game: Game,
        rangeHeader: String?,
        ifRangeHeader: String?,
        send: (InputStream, OutputStream) -> Unit,
    ): ResponseEntity<StreamingResponseBody> {
        val etag = "\"${download.version}\""
        val range = ByteRange.resolve(rangeHeader, ifRangeHeader, etag, download.size)
        if (range == ByteRange.Unsatisfiable) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .header(HttpHeaders.CONTENT_RANGE, "bytes */${download.size}")
                .eTag(etag)
                .build()
        }

        val (start, end) = (range as? ByteRange.Part)?.let { it.start to it.end } ?: (0L to download.size - 1)
        // A resume continues a download already counted.
        if (start == 0L) gameService.incrementDownloadCount(game)

        val responseBuilder = if (range is ByteRange.Part) {
            ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .header(HttpHeaders.CONTENT_RANGE, "bytes $start-$end/${download.size}")
        } else {
            ResponseEntity.ok()
        }

        return responseBuilder
            .header("Content-Disposition", createContentDispositionHeader(filenameFor(game, download.fileExtension)))
            .header("Content-Type", "application/octet-stream")
            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
            .eTag(etag)
            .contentLength(end - start + 1)
            .body(StreamingResponseBody { outputStream ->
                send(ByteStreams.limit(download.open(start), end - start + 1), outputStream)
            })
    }

    private fun filenameFor(game: Game, fileExtension: String?): String {
        val baseFilename = game.title?.replace("[\\\\/:*?\"<>|]".toRegex(), "") // Remove common invalid filename chars
            ?: "download"

        return if (!fileExtension.isNullOrEmpty()) "$baseFilename.$fileExtension" else baseFilename
    }

    /**
     * Converts a string to a safe ASCII fallback filename by replacing non-ASCII characters.
     * Characters with code points > 127 and common invalid chars for filenames are removed, and if the result is empty or only whitespace,
     * returns "download" as a fallback.
     */
    private fun String.safeDownloadFileName(): String {
        val asciiOnly = filter { it.code in 0..255 } // Printable ASCII only
            .trim()

        return asciiOnly.ifBlank { "download" }
    }

    /**
     * URL-encodes a string according to RFC 5987.
     */
    private fun String.encodeRfc5987(): String {
        return URLEncoder.encode(this, StandardCharsets.UTF_8)
            .replace("+", "%20") // URLEncoder uses + for space, but RFC 5987 requires %20
    }

    /**
     * Creates a Content-Disposition header value with both ASCII fallback and RFC 5987 Unicode support.
     *
     * Example output:
     *   attachment; filename="Game_Title.zip"; filename*=UTF-8''Game%E2%84%A2%20Title.zip
     *
     * @param filename The original filename (may contain Unicode characters)
     * @param disposition The disposition type (default: "attachment")
     * @return A properly formatted Content-Disposition header value
     */
    private fun createContentDispositionHeader(filename: String, disposition: String = "attachment"): String {
        val asciiFallback = filename.safeDownloadFileName()
        val encodedFilename = filename.encodeRfc5987()

        return buildString {
            append(disposition)
            append("; filename=\"")
            append(asciiFallback)
            append("\"")
            // Only add filename* if there are non-ASCII characters
            if (filename != asciiFallback) {
                append("; filename*=utf-8''")
                append(encodedFilename)
            }
        }
    }
}