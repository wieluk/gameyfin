package org.gameyfin.pluginapi.download

import java.io.InputStream

/**
 * Represents a downloadable resource, which can be either a file or a link.
 */
sealed interface Download

/**
 * Represents a file-based download.
 *
 * @property data The input stream containing the file data.
 * @property fileExtension The file extension (e.g., "zip", "png"), or null if none.
 * @property size The size of the file in bytes, or null if unknown.
 */
data class FileDownload(
    val data: InputStream,
    val fileExtension: String? = null,
    val size: Long? = null
) : Download

/**
 * Represents a file-based download that can start part way through, so an interrupted
 * transfer resumes instead of starting over.
 *
 * @property fileExtension The file extension (e.g., "zip", "png"), or null if none.
 * @property size The exact size of the file in bytes.
 * @property version Changes whenever the content does, so a resume never joins two different files.
 * @property open Opens the content at the given byte offset. The caller closes the stream.
 */
class ResumableFileDownload(
    val fileExtension: String?,
    val size: Long,
    val version: String,
    val open: (offset: Long) -> InputStream,
) : Download

/**
 * Represents a link-based download.
 *
 * @property url The URL to the downloadable resource.
 */
data class LinkDownload(
    val url: String
) : Download
