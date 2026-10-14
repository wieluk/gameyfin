package org.gameyfin.plugins.download.direct

import org.gameyfin.pluginapi.core.config.ConfigMetadata
import org.gameyfin.pluginapi.core.config.PluginConfigMetadata
import org.gameyfin.pluginapi.core.wrapper.ConfigurableGameyfinPlugin
import org.gameyfin.pluginapi.download.Download
import org.gameyfin.pluginapi.download.DownloadProvider
import org.gameyfin.pluginapi.download.FileDownload
import org.gameyfin.pluginapi.download.ResumableFileDownload
import org.pf4j.Extension
import org.pf4j.PluginWrapper
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory

class DirectDownloadPlugin(wrapper: PluginWrapper) : ConfigurableGameyfinPlugin(wrapper) {

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
            description = "Higher compression modes are more resource intensive, but save bandwidth. Paused downloads of folders can only resume with None.",
            default = CompressionMode.None
        )
    )

    @Suppress("Unused")
    @Extension(ordinal = 1)
    class DirectDownloadProvider : DownloadProvider {
        override fun download(path: Path): Download {
            if (!path.exists()) throw IllegalArgumentException("Path $path does not exist")

            if (!path.isDirectory()) return resumableFile(path)

            // Only an uncompressed zip has a size known before it is sent, which resuming needs.
            if (plugin.config<CompressionMode>("compressionMode") == CompressionMode.None) {
                val zip = FolderZip.of(path)
                return ResumableFileDownload("zip", zip.size, zip.version, zip::open)
            }

            return FileDownload(data = streamFolderAsZip(path), fileExtension = "zip")
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

        fun streamFolderAsZip(path: Path): InputStream {
            val pipeIn = PipedInputStream(512 * 1024) // 512 KB buffer
            val pipeOut = PipedOutputStream(pipeIn)

            Thread.ofVirtual().start {
                try {
                    ZipOutputStream(pipeOut).use { zos ->

                        val compressionMode = plugin.config<CompressionMode>("compressionMode")
                        zos.setLevel(compressionMode.deflaterLevel())

                        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                                val entry = ZipEntry(path.relativize(file).toString())
                                zos.putNextEntry(entry)
                                Files.newInputStream(file, StandardOpenOption.READ).use { input ->
                                    input.copyTo(zos, 512 * 1024)
                                }
                                zos.closeEntry()
                                return FileVisitResult.CONTINUE
                            }
                        })
                    }
                    pipeOut.close()
                } catch (_: IOException) {
                } finally {
                    try {
                        pipeOut.close()
                    } catch (_: IOException) {
                    }
                }
            }

            return pipeIn
        }
    }
}
