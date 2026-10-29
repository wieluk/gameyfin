package org.gameyfin.app.core

import com.vaadin.hilla.Endpoint
import jakarta.annotation.security.PermitAll
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties

@Endpoint
@PermitAll
class VersionEndpoint(
    // Absent in development runs
    private val buildProperties: ObjectProvider<BuildProperties>
) {
    fun getVersion(): String? = buildProperties.ifAvailable?.version
}
