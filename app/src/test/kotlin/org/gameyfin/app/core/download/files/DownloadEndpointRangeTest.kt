package org.gameyfin.app.core.download.files

import io.mockk.*
import org.gameyfin.app.core.getRemoteIp
import org.gameyfin.app.core.security.getCurrentAuth
import org.gameyfin.app.games.GameService
import org.gameyfin.app.games.entities.Game
import org.gameyfin.app.games.entities.GameMetadata
import org.gameyfin.pluginapi.download.ResumableFileDownload
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

/** Range requests through Spring MVC itself, which also has opinions about ETags and conditional requests. */
class DownloadEndpointRangeTest {

    private val content = ByteArray(10_000) { (it % 251).toByte() }
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        val metadata = mockk<GameMetadata> { every { path } returns "/games/Test" }
        val game = mockk<Game>(relaxed = true) {
            every { title } returns "Test Game"
            every { this@mockk.metadata } returns metadata
        }
        val gameService = mockk<GameService>(relaxed = true) { every { getById(1L) } returns game }
        val downloadService = mockk<DownloadService> {
            every { getDownload("/games/Test", "p") } returns
                    ResumableFileDownload("zip", content.size.toLong(), "v1") { offset ->
                        ByteArrayInputStream(content, offset.toInt(), content.size - offset.toInt())
                    }
            every { processDownload(any(), any(), any(), any(), any(), any()) } answers {
                firstArg<InputStream>().use { it.copyTo(secondArg<OutputStream>()) }
            }
        }

        mockkStatic("org.gameyfin.app.core.UtilsKt", "org.gameyfin.app.core.security.SecurityUtilsKt")
        every { any<jakarta.servlet.http.HttpServletRequest>().getRemoteIp(any()) } returns "127.0.0.1"
        every { getCurrentAuth() } returns null

        mvc = MockMvcBuilders.standaloneSetup(DownloadEndpoint(downloadService, gameService)).build()
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /** The deferred response, then the streamed body, each finish on their own async dispatch. */
    private fun download(vararg headers: Pair<String, String>): ResultActions {
        val started = mvc.perform(get("/download/1").param("provider", "p").apply {
            headers.forEach { (name, value) -> header(name, value) }
        }).andExpect(request().asyncStarted()).andReturn()
        val dispatched = mvc.perform(asyncDispatch(started))
        dispatched.andReturn().takeIf { it.request.isAsyncStarted }?.getAsyncResult(5_000)
        return dispatched
    }

    @Test
    fun `a whole download names its version and accepts ranges`() {
        download()
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ETAG, "\"v1\""))
            .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
            .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 10_000))
            .andExpect(content().bytes(content))
    }

    @Test
    fun `a resume gets the rest of the same version`() {
        download(HttpHeaders.RANGE to "bytes=4000-", HttpHeaders.IF_RANGE to "\"v1\"")
            .andExpect(status().isPartialContent)
            .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 4000-9999/10000"))
            .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 6_000))
            .andExpect(content().bytes(content.copyOfRange(4000, 10_000)))
    }

    @Test
    fun `a resume of an older version gets the whole new one`() {
        download(HttpHeaders.RANGE to "bytes=4000-", HttpHeaders.IF_RANGE to "\"v0\"")
            .andExpect(status().isOk)
            .andExpect(content().bytes(content))
    }

    @Test
    fun `a range past the end is refused`() {
        download(HttpHeaders.RANGE to "bytes=10000-")
            .andExpect(status().isRequestedRangeNotSatisfiable)
            .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes */10000"))
    }
}
