package org.gameyfin.plugins.download.direct

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.*
import java.util.zip.CRC32
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile

/**
 * A folder as a zip whose every byte is known before it is sent, so a transfer can start at any offset.
 * Laid out as Java's ZipOutputStream writes it, but deflated with stored blocks only, which have a known size.
 */
class FolderZip private constructor(private val entries: List<Entry>, zone: ZoneId) {

    private class Entry(
        val file: Path,
        val name: ByteArray,
        val size: Long,
        val modified: FileTime,
        val dosTime: Long,
        /** Where the local header starts. */
        val offset: Long,
    ) {
        val compressedSize = deflatedSize(size)
        val dataStart = offset + LOCAL_HEADER + name.size
        val descriptorStart = dataStart + compressedSize

        /** Java widens the descriptor's sizes once either no longer fits 32 bits. */
        val wideDescriptor = compressedSize >= ZIP64_MAGIC || size >= ZIP64_MAGIC
        val end = descriptorStart + if (wideDescriptor) 24 else 16
    }

    private val centralStart: Long = entries.lastOrNull()?.end ?: 0L
    private val centralSize: Long = entries.sumOf { centralEntrySize(it) }
    private val zip64End = centralStart >= ZIP64_MAGIC || centralSize >= ZIP64_MAGIC || entries.size >= 0xFFFF

    val size: Long = centralStart + centralSize + (if (zip64End) ZIP64_END + ZIP64_LOCATOR else 0) + END

    /** Changes with any file's name, size or modification time, and with the time zone the DOS times are in. */
    val version: String = fingerprint(
        sequenceOf("folder-zip-1", zone.id) + entries.asSequence()
            .map { "${String(it.name, Charsets.UTF_8)}\u0000${it.size}\u0000${it.modified.toMillis()}" }
    )

    fun open(offset: Long): InputStream {
        val pipeIn = PipedInputStream(PIPE_SIZE)
        val pipeOut = PipedOutputStream(pipeIn)

        Thread.ofVirtual().start {
            try {
                pipeOut.use { writeTo(it, offset) }
            } catch (_: IOException) {
                // The reader went away, or a file changed underneath; either way the transfer ends short.
            }
        }

        return pipeIn
    }

    fun writeTo(out: OutputStream, from: Long) {
        require(from in 0..size) { "Offset $from is outside the zip (size $size)" }
        // Checksums of the files sent whole by this call; the others come from the cache or a re-read.
        val crcs = arrayOfNulls<Int>(entries.size)
        // After a restart the cache is empty, so the skipped files are re-read while the rest streams.
        val skipped = entries.filter { it.dataStart < from }
        val warmUp = if (skipped.isEmpty()) null else Thread.ofVirtual().start {
            for (entry in skipped) {
                if (Thread.currentThread().isInterrupted) break
                runCatching { crcOf(entry) }
            }
        }

        try {
            for ((index, entry) in entries.withIndex()) {
                if (entry.end <= from) continue
                writeClipped(out, localHeader(entry), entry.offset, from)
                if (entry.descriptorStart > from) crcs[index] = writeData(out, entry, from)
                val crc = crcs[index] ?: crcOf(entry).also { crcs[index] = it }
                writeClipped(out, descriptor(entry, crc), entry.descriptorStart, from)
            }

            if (size > from) {
                warmUp?.join()
                writeClipped(out, centralDirectory { crcs[it] ?: crcOf(entries[it]) }, centralStart, from)
            }
            out.flush()
        } finally {
            warmUp?.interrupt()
        }
    }

    /** Writes the stored blocks holding [entry], returning its checksum when the whole file went out. */
    private fun writeData(out: OutputStream, entry: Entry, from: Long): Int? {
        val skip = maxOf(0L, from - entry.dataStart)
        val blocks = blockCount(entry.size)
        val firstBlock = skip / (BLOCK + BLOCK_HEADER)
        var within = (skip % (BLOCK + BLOCK_HEADER)).toInt()
        val crc = if (skip == 0L) CRC32() else null
        val buffer = ByteArray(BLOCK)

        FileChannel.open(entry.file, StandardOpenOption.READ).use { channel ->
            channel.position(firstBlock * BLOCK + maxOf(0, within - BLOCK_HEADER))
            val input = Channels.newInputStream(channel)

            for (block in firstBlock until blocks) {
                val length = minOf(BLOCK.toLong(), entry.size - block * BLOCK).toInt()
                if (within < BLOCK_HEADER) {
                    out.write(blockHeader(length, last = block == blocks - 1), within, BLOCK_HEADER - within)
                }
                val wanted = length - maxOf(0, within - BLOCK_HEADER)
                if (input.readNBytes(buffer, 0, wanted) != wanted) {
                    throw IOException("${entry.file} shrank while it was being sent")
                }
                crc?.update(buffer, 0, wanted)
                out.write(buffer, 0, wanted)
                within = 0
            }
        }

        return crc?.value?.toInt()?.also { crcCache[CrcKey(entry)] = it }
    }

    private fun crcOf(entry: Entry): Int {
        crcCache[CrcKey(entry)]?.let { return it }

        val crc = CRC32()
        val buffer = ByteArray(BLOCK)
        Files.newInputStream(entry.file).use { input ->
            var remaining = entry.size
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) throw IOException("${entry.file} shrank while it was being sent")
                crc.update(buffer, 0, read)
                remaining -= read
            }
        }
        return crc.value.toInt().also { crcCache[CrcKey(entry)] = it }
    }

    private fun localHeader(entry: Entry): ByteArray = littleEndian(LOCAL_HEADER + entry.name.size) {
        putInt(0x04034b50)
        putShort(VERSION_DEFLATE)
        putShort(FLAGS)
        putShort(METHOD_DEFLATE)
        putInt(entry.dosTime.toInt())
        // Checksum and sizes follow the data in the descriptor.
        putInt(0)
        putInt(0)
        putInt(0)
        putShort(entry.name.size.toShort())
        putShort(0)
        put(entry.name)
    }

    private fun descriptor(entry: Entry, crc: Int): ByteArray =
        littleEndian((entry.end - entry.descriptorStart).toInt()) {
            putInt(0x08074b50)
            putInt(crc)
            if (entry.wideDescriptor) {
                putLong(entry.compressedSize)
                putLong(entry.size)
            } else {
                putInt(entry.compressedSize.toInt())
                putInt(entry.size.toInt())
            }
        }

    private fun centralDirectory(crc: (Int) -> Int): ByteArray {
        val endSize = (size - centralStart - centralSize).toInt()
        return littleEndian(Math.toIntExact(centralSize) + endSize) {
            for ((index, entry) in entries.withIndex()) {
                val zip64 = zip64Fields(entry)
                putInt(0x02014b50)
                // Version made by, then version needed.
                putShort(if (zip64.isEmpty()) VERSION_DEFLATE else VERSION_ZIP64)
                putShort(if (zip64.isEmpty()) VERSION_DEFLATE else VERSION_ZIP64)
                putShort(FLAGS)
                putShort(METHOD_DEFLATE)
                putInt(entry.dosTime.toInt())
                putInt(crc(index))
                putInt(capped(entry.compressedSize))
                putInt(capped(entry.size))
                putShort(entry.name.size.toShort())
                putShort((if (zip64.isEmpty()) 0 else 4 + zip64.size * 8).toShort())
                putShort(0) // comment length
                putShort(0) // disk number
                putShort(0) // internal attributes
                putInt(0) // external attributes
                putInt(capped(entry.offset))
                put(entry.name)
                if (zip64.isNotEmpty()) {
                    putShort(0x0001)
                    putShort((zip64.size * 8).toShort())
                    zip64.forEach { putLong(it) }
                }
            }

            if (zip64End) {
                val zip64EndStart = centralStart + centralSize
                putInt(0x06064b50)
                putLong(ZIP64_END - 12L)
                putShort(VERSION_ZIP64)
                putShort(VERSION_ZIP64)
                putInt(0)
                putInt(0)
                putLong(entries.size.toLong())
                putLong(entries.size.toLong())
                putLong(centralSize)
                putLong(centralStart)

                putInt(0x07064b50)
                putInt(0)
                putLong(zip64EndStart)
                putInt(1)
            }

            val count = if (zip64End && entries.size >= 0xFFFF) 0xFFFF else entries.size
            putInt(0x06054b50)
            putShort(0)
            putShort(0)
            putShort(count.toShort())
            putShort(count.toShort())
            putInt(capped(centralSize))
            putInt(capped(centralStart))
            putShort(0) // comment length
        }
    }

    /** The values that no longer fit 32 bits, in the order the zip64 extra field holds them. */
    private fun zip64Fields(entry: Entry): List<Long> =
        listOf(entry.size, entry.compressedSize, entry.offset).filter { it >= ZIP64_MAGIC }

    private fun centralEntrySize(entry: Entry): Long {
        val zip64 = zip64Fields(entry)
        return CENTRAL_HEADER + entry.name.size + if (zip64.isEmpty()) 0L else 4L + zip64.size * 8
    }

    private data class CrcKey(val file: Path, val size: Long, val modified: FileTime) {
        constructor(entry: Entry) : this(entry.file, entry.size, entry.modified)
    }

    companion object {
        private const val LOCAL_HEADER = 30
        private const val CENTRAL_HEADER = 46
        private const val ZIP64_END = 56
        private const val ZIP64_LOCATOR = 20
        private const val END = 22
        private const val ZIP64_MAGIC = 0xFFFFFFFFL

        private const val VERSION_DEFLATE: Short = 20
        private const val VERSION_ZIP64: Short = 45
        private const val METHOD_DEFLATE: Short = 8

        /** Data descriptor follows, names are UTF-8. */
        private const val FLAGS: Short = 0x0808

        /** The most a stored deflate block holds. */
        private const val BLOCK = 65_535
        private const val BLOCK_HEADER = 5

        private const val PIPE_SIZE = 512 * 1024
        private const val DOS_TIME_BEFORE_1980 = (1L shl 21) or (1L shl 16)

        /** Enough for a few large libraries; a miss costs one re-read of the file. */
        private const val CRC_CACHE_SIZE = 100_000

        private val crcCache: MutableMap<CrcKey, Int> = Collections.synchronizedMap(
            object : LinkedHashMap<CrcKey, Int>(1024, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CrcKey, Int>) =
                    size > CRC_CACHE_SIZE
            }
        )

        fun of(root: Path, zone: ZoneId = ZoneId.systemDefault()): FolderZip {
            // Sorted, so the same folder always produces the same bytes.
            val files = Files.walk(root).use { paths ->
                paths.filter { it.isRegularFile() }
                    .map { root.relativize(it).joinToString("/") to it }
                    .toList()
            }.sortedBy { it.first }

            var offset = 0L
            val entries = files.map { (name, file) ->
                val modified = file.getLastModifiedTime()
                Entry(
                    file = file,
                    name = name.toByteArray(Charsets.UTF_8),
                    size = file.fileSize(),
                    modified = modified,
                    dosTime = dosTime(modified, zone),
                    offset = offset,
                ).also { offset = it.end }
            }
            return FolderZip(entries, zone)
        }

        internal fun clearCrcCache() = crcCache.clear()

        internal fun fingerprint(parts: Sequence<String>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            parts.forEach { digest.update(it.toByteArray(Charsets.UTF_8)); digest.update('\n'.code.toByte()) }
            return HexFormat.of().formatHex(digest.digest(), 0, 16)
        }

        private fun blockCount(size: Long): Long = maxOf(1L, (size + BLOCK - 1) / BLOCK)

        private fun deflatedSize(size: Long): Long = size + blockCount(size) * BLOCK_HEADER

        /** A stored block: final flag and type 00, then the length and its complement. */
        private fun blockHeader(length: Int, last: Boolean) = byteArrayOf(
            if (last) 1 else 0,
            length.toByte(),
            (length shr 8).toByte(),
            length.inv().toByte(),
            (length.inv() shr 8).toByte(),
        )

        private fun dosTime(modified: FileTime, zone: ZoneId): Long {
            val time = LocalDateTime.ofInstant(modified.toInstant(), zone)
            if (time.year < 1980) return DOS_TIME_BEFORE_1980
            val year = minOf(time.year, 2107)
            return ((year - 1980L) shl 25) or (time.monthValue.toLong() shl 21) or
                    (time.dayOfMonth.toLong() shl 16) or (time.hour.toLong() shl 11) or
                    (time.minute.toLong() shl 5) or (time.second.toLong() shr 1)
        }

        private fun capped(value: Long): Int = minOf(value, ZIP64_MAGIC).toInt()

        private fun writeClipped(out: OutputStream, bytes: ByteArray, start: Long, from: Long) {
            if (start + bytes.size <= from) return
            val skip = maxOf(0L, from - start).toInt()
            out.write(bytes, skip, bytes.size - skip)
        }

        private inline fun littleEndian(size: Int, fill: ByteBuffer.() -> Unit): ByteArray {
            val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            buffer.fill()
            check(!buffer.hasRemaining()) { "Zip record is ${buffer.position()} bytes, expected $size" }
            return buffer.array()
        }
    }
}
