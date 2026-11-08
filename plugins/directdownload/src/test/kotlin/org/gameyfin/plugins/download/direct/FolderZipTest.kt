package org.gameyfin.plugins.download.direct

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class FolderZipTest {

    @TempDir
    lateinit var root: Path

    private val files = linkedMapOf<String, ByteArray>()

    @BeforeEach
    fun setup() {
        FolderZip.clearCrcCache()
        // Several stored blocks, an exact block, an empty file, nesting and a non-ASCII name.
        add("Game.exe", Random(1).nextBytes(200_000))
        add("data/level1.pak", Random(2).nextBytes(65_535))
        add("data/empty.txt", ByteArray(0))
        add("data/sub/über.cfg", "fullscreen=1".toByteArray())
        add("readme.txt", "hello".toByteArray())
    }

    private fun add(name: String, bytes: ByteArray) {
        val file = root.resolve(name)
        file.parent.createDirectories()
        file.writeBytes(bytes)
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2024-05-06T07:08:10Z")))
        files[name] = bytes
    }

    private fun zip() = FolderZip.of(root, ZoneOffset.UTC)

    private fun bytes(zip: FolderZip, from: Long = 0): ByteArray =
        ByteArrayOutputStream().also { zip.writeTo(it, from) }.toByteArray()

    @Test
    fun `the announced size is the size written`() {
        val zip = zip()
        assertEquals(zip.size, bytes(zip).size.toLong())
    }

    @Test
    fun `a zip reader finds every file intact`() {
        val content = bytes(zip())
        val archive = root.resolve("out.zip")
        archive.writeBytes(content)

        ZipFile(archive.toFile()).use { zipFile ->
            assertEquals(files.keys.sorted(), zipFile.entries().toList().map { it.name })
            files.forEach { (name, expected) ->
                assertContentEquals(expected, zipFile.getInputStream(zipFile.getEntry(name)).readAllBytes(), name)
            }
        }
    }

    @Test
    fun `a stream reader finds every file intact`() {
        // How a client unpacks while it downloads: front to back, through the data descriptors.
        val found = mutableMapOf<String, ByteArray>()
        ZipInputStream(bytes(zip()).inputStream()).use { input ->
            generateSequence { input.nextEntry }.forEach { found[it.name] = input.readAllBytes() }
        }
        assertEquals(files.keys, found.keys)
        files.forEach { (name, expected) -> assertContentEquals(expected, found[name], name) }
    }

    @Test
    fun `entries keep the files' modification times`() {
        ZipInputStream(bytes(zip()).inputStream()).use { input ->
            val entry = input.nextEntry!!
            assertEquals(Instant.parse("2024-05-06T07:08:10Z"), entry.timeLocal.toInstant(ZoneOffset.UTC))
        }
    }

    @Test
    fun `starting at any offset continues the same bytes`() {
        val zip = zip()
        val whole = bytes(zip)
        val offsets = (0L..40L) + listOf(65_000L, 65_540L, 65_541L, 131_080L, 200_030L) +
                (whole.size - 200L until whole.size.toLong() + 1)

        for (offset in offsets) {
            FolderZip.clearCrcCache()
            assertContentEquals(whole.copyOfRange(offset.toInt(), whole.size), bytes(zip, offset), "from $offset")
        }
    }

    @Test
    fun `a resume reuses the checksums of files already sent`() {
        val zip = zip()
        val whole = bytes(zip)
        // Seeded by the full pass; a resume after it must still agree.
        assertContentEquals(whole.copyOfRange(150_000, whole.size), bytes(zip, 150_000))
    }

    @Test
    fun `open streams from the offset`() {
        val zip = zip()
        val whole = bytes(zip)
        assertContentEquals(whole.copyOfRange(1234, whole.size), zip.open(1234).use { it.readAllBytes() })
    }

    @Test
    fun `the same folder always produces the same bytes and version`() {
        assertContentEquals(bytes(zip()), bytes(zip()))
        assertEquals(zip().version, zip().version)
    }

    @Test
    fun `the version changes with the content`() {
        val before = zip().version
        add("readme.txt", "changed".toByteArray())
        assertNotEquals(before, zip().version)
    }

    @Test
    fun `an empty folder is an empty zip`() {
        val empty = root.resolve("nothing").createDirectories()
        val zip = FolderZip.of(empty)
        assertEquals(22, zip.size)
        ZipInputStream(bytes(zip).inputStream()).use { assertEquals(null, it.nextEntry) }
    }

    @Test
    fun `an offset past the end is refused`() {
        val zip = zip()
        assertFailsWith<IllegalArgumentException> { zip.writeTo(ByteArrayOutputStream(), zip.size + 1) }
    }
}
