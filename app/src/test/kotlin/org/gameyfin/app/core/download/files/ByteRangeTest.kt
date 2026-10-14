package org.gameyfin.app.core.download.files

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ByteRangeTest {

    private val etag = "\"v1\""

    private fun resolve(range: String?, ifRange: String? = null, size: Long = 1000) =
        ByteRange.resolve(range, ifRange, etag, size)

    @Test
    fun `no header means the whole body`() {
        assertEquals(ByteRange.Whole, resolve(null))
    }

    @Test
    fun `an open range runs to the end`() {
        assertEquals(ByteRange.Part(400, 999), resolve("bytes=400-"))
    }

    @Test
    fun `a closed range is kept, and clipped to the size`() {
        assertEquals(ByteRange.Part(10, 19), resolve("bytes=10-19"))
        assertEquals(ByteRange.Part(10, 999), resolve("bytes=10-5000"))
    }

    @Test
    fun `a suffix range takes the last bytes`() {
        assertEquals(ByteRange.Part(900, 999), resolve("bytes=-100"))
        assertEquals(ByteRange.Part(0, 999), resolve("bytes=-5000"))
    }

    @Test
    fun `a start past the end cannot be satisfied`() {
        assertEquals(ByteRange.Unsatisfiable, resolve("bytes=1000-"))
        assertEquals(ByteRange.Unsatisfiable, resolve("bytes=-0"))
        assertEquals(ByteRange.Unsatisfiable, resolve("bytes=0-", size = 0))
    }

    @Test
    fun `a matching If-Range keeps the range`() {
        assertEquals(ByteRange.Part(400, 999), resolve("bytes=400-", ifRange = etag))
    }

    @Test
    fun `a different If-Range sends the whole changed file`() {
        assertEquals(ByteRange.Whole, resolve("bytes=400-", ifRange = "\"v0\""))
        assertEquals(ByteRange.Whole, resolve("bytes=400-", ifRange = "W/\"v1\""))
        assertEquals(ByteRange.Whole, resolve("bytes=400-", ifRange = "Tue, 15 Nov 1994 08:12:31 GMT"))
    }

    @Test
    fun `malformed or multiple ranges are ignored`() {
        for (header in listOf("bytes=0-1,5-9", "items=0-5", "bytes=abc-", "bytes=9-3", "bytes=5", "garbage")) {
            assertEquals(ByteRange.Whole, resolve(header), header)
        }
    }
}
