package org.gameyfin.plugins.download.direct

import org.gameyfin.pluginapi.core.wrapper.PluginContext
import org.gameyfin.pluginapi.data.GameyfinDataService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.random.Random
import kotlin.test.*

class FolderRangeDownloadTest {
    @TempDir lateinit var root: Path

    private fun provider(mode: CompressionMode, calculateSize: Boolean): DirectDownloadPlugin.DirectDownloadProvider {
        DirectDownloadPlugin(object : PluginContext {
            override val pluginId = "direct"
            override val pluginVersion = "test"
            override val dataDirectory get() = root
            override val dataService: GameyfinDataService get() = error("unused")
        }).loadConfig(mapOf("compressionMode" to mode.name, "calculateArchiveSize" to calculateSize.toString()))
        return DirectDownloadPlugin.DirectDownloadProvider()
    }

    private fun folder(): Path {
        val folder = Files.createDirectories(root.resolve("game/sub"))
        Files.write(folder.resolve("data.bin"), Random(1).nextBytes(300_000))
        Files.write(folder.parent.resolve("readme.txt"), "hello".toByteArray())
        return folder.parent
    }

    @Test
    fun `a folder resumes only uncompressed and with the size scan`() {
        val folder = folder()
        assertNull(provider(CompressionMode.None, calculateSize = false).prepareRangeDownload(folder))
        assertNull(provider(CompressionMode.Fast, calculateSize = true).prepareRangeDownload(folder))
        provider(CompressionMode.None, calculateSize = true).prepareRangeDownload(folder)!!.close()
    }

    @Test
    fun `any range of the folder zip matches the whole`() {
        val folder = folder()
        val provider = provider(CompressionMode.None, calculateSize = true)
        val whole = provider.prepareRangeDownload(folder)!!.use { r ->
            assertEquals("zip", r.metadata.fileExtension)
            assertTrue(r.etag.startsWith("\"") && r.etag.endsWith("\""))
            r.openStream(0, r.metadata.size!!).use { it.readAllBytes() }
        }
        assertEquals(whole.size.toLong(), provider.prepareRangeDownload(folder)!!.use { it.metadata.size })
        ZipFile(Files.write(root.resolve("whole.zip"), whole).toFile()).use { zip ->
            assertEquals(5, zip.getInputStream(zip.getEntry("readme.txt")).readAllBytes().size)
            assertEquals(300_000, zip.getInputStream(zip.getEntry("sub/data.bin")).readAllBytes().size)
        }
        for ((start, length) in listOf(0L to 10L, 1_000L to 200_000L, whole.size - 50L to 50L)) {
            val part = provider.prepareRangeDownload(folder)!!.use { r -> r.openStream(start, length).use { it.readAllBytes() } }
            assertContentEquals(whole.copyOfRange(start.toInt(), (start + length).toInt()), part)
        }
    }

    @Test
    fun `the etag stays for the same folder and changes with it`() {
        val folder = folder()
        val provider = provider(CompressionMode.None, calculateSize = true)
        val first = provider.prepareRangeDownload(folder)!!.use { it.etag }
        assertEquals(first, provider.prepareRangeDownload(folder)!!.use { it.etag })
        Files.write(folder.resolve("readme.txt"), "changed".toByteArray())
        assertNotEquals(first, provider.prepareRangeDownload(folder)!!.use { it.etag })
    }
}
