package org.gameyfin.plugins.download.direct

import org.gameyfin.pluginapi.download.DownloadMetadata
import org.gameyfin.pluginapi.download.RangeDownload
import java.io.InputStream
import java.nio.file.Path

/** A folder as a [FolderZip], so it can resume. Files must stay unchanged while it is sent, as for [FileRangeDownload]. */
internal class FolderRangeDownload(root: Path) : RangeDownload {
    private val zip = FolderZip.of(root)
    @Volatile private var stream: InputStream? = null
    override val metadata = DownloadMetadata("zip", zip.size)
    override val etag = "\"${zip.version}\""

    override fun openStream(offset: Long, length: Long): InputStream {
        require(offset >= 0 && length >= 0 && length <= zip.size - offset)
        val source = zip.open(offset)
        return object : InputStream() {
            private var remaining = length
            override fun read(): Int {
                val byte = ByteArray(1)
                return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255
            }

            override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                java.util.Objects.checkFromIndexSize(off, len, bytes.size)
                if (len == 0) return 0
                if (remaining == 0L) return -1
                val count = source.read(bytes, off, minOf(len.toLong(), remaining).toInt())
                if (count > 0) remaining -= count
                return count
            }

            override fun close() = this@FolderRangeDownload.close()
        }.also { stream = source }
    }

    // Closing the pipe stops the writer thread, also when only part of the zip was asked for.
    override fun close() {
        stream?.close()
    }
}
