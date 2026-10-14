package org.gameyfin.plugins.download.direct

import org.gameyfin.pluginapi.core.config.ConfigMetadata
import org.gameyfin.pluginapi.core.config.PluginConfigMetadata
import org.gameyfin.pluginapi.core.wrapper.ConfigurableGameyfinPlugin
import org.gameyfin.pluginapi.core.wrapper.PluginContext
import org.gameyfin.pluginapi.download.Download
import org.gameyfin.pluginapi.download.DownloadProvider
import org.gameyfin.pluginapi.download.ResumableFileDownload
import org.pf4j.Extension
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory

class DirectDownloadPlugin(context: PluginContext) : ConfigurableGameyfinPlugin(context) {

    companion object {
        lateinit var plugin: DirectDownloadPlugin
            private set
    }

    init {
        plugin = this
    }

    override val configMetadata: PluginConfigMetadata = listOf(
        ConfigMetadata(
            key = "compressionMode",
            type = CompressionMode::class.java,
            label = "Compression mode",
            description = "Higher compression modes are more resource intensive, but save bandwidth",
            default = CompressionMode.None
        )
    )

    @Suppress("Unused")
    @Extension(ordinal = 1)
    class DirectDownloadProvider : DownloadProvider {
        override fun download(path: Path): Download {
            if (!path.exists()) throw IllegalArgumentException("Path $path does not exist")

            if (!path.isDirectory()) return resumableFile(path)

            // Folders are packed as an uncompressed zip whose every byte is known up front, so the
            // download has a Content-Length and can resume. See FolderZip for the layout.
            val zip = FolderZip.of(path)
            return ResumableFileDownload("zip", zip.size, zip.version, zip::open)
        }

        fun resumableFile(path: Path): ResumableFileDownload {
            val size = path.fileSize()
            val modified = path.getLastModifiedTime()
            return ResumableFileDownload(
                fileExtension = path.extension,
                size = size,
                version = FolderZip.fingerprint(sequenceOf("file-1", path.toString(), "$size", "${modified.toMillis()}")),
                open = { offset -> Channels.newInputStream(FileChannel.open(path, StandardOpenOption.READ).position(offset)) },
            )
        }
    }
}
