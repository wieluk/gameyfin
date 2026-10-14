package org.gameyfin.plugins.download.direct

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.writeBytes
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DirectDownloadProviderTest {

    @TempDir
    lateinit var root: Path

    private val provider = DirectDownloadPlugin.DirectDownloadProvider()

    @Test
    fun `a single file opens at any offset`() {
        val bytes = Random(7).nextBytes(100_000)
        val file = root.resolve("setup.exe").also { it.writeBytes(bytes) }

        val download = provider.resumableFile(file)

        assertEquals("exe", download.fileExtension)
        assertEquals(100_000, download.size)
        assertContentEquals(bytes, download.open(0).use { it.readAllBytes() })
        assertContentEquals(bytes.copyOfRange(65_000, 100_000), download.open(65_000).use { it.readAllBytes() })
    }

    @Test
    fun `a replaced file gets a new version`() {
        val file = root.resolve("game.iso").also { it.writeBytes(ByteArray(10)) }
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_000_000))
        val before = provider.resumableFile(file).version

        Files.setLastModifiedTime(file, FileTime.fromMillis(2_000_000))

        assertNotEquals(before, provider.resumableFile(file).version)
    }
}
