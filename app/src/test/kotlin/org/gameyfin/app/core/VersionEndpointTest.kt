package org.gameyfin.app.core

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VersionEndpointTest {

    private fun endpoint(version: String?): VersionEndpoint {
        val provider = mockk<ObjectProvider<BuildProperties>>()
        every { provider.ifAvailable } returns version?.let {
            BuildProperties(Properties().apply { setProperty("version", it) })
        }
        return VersionEndpoint(provider)
    }

    @Test
    fun `reports the version from the build info`() {
        assertEquals("2.4.0", endpoint("2.4.0").getVersion())
    }

    @Test
    fun `reports nothing when there is no build info`() {
        assertNull(endpoint(null).getVersion())
    }
}
