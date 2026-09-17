package org.gameyfin.app.users.devicetokens

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gameyfin.app.users.entities.User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.*

@Service
class DeviceTokenService(private val repository: DeviceTokenRepository) {

    companion object {
        const val PREFIX = "gyf_"
        const val MAX_PER_USER = 50
        const val MAX_NAME_LENGTH = 100

        private val TOUCH_INTERVAL = Duration.ofHours(1)
        private val random = SecureRandom()
        private val log = KotlinLogging.logger {}

        fun hash(secret: String): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()))
    }

    @Transactional
    fun create(user: User, name: String): String {
        check(repository.countByUser(user) < MAX_PER_USER) { "Too many devices, revoke one first" }
        val bytes = ByteArray(32).also(random::nextBytes)
        val secret = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val label = name.trim().take(MAX_NAME_LENGTH).ifEmpty { "Unnamed device" }
        repository.save(DeviceToken(user = user, name = label, tokenHash = hash(secret)))
        log.info { "Created device token '$label' for user '${user.username}'" }
        return secret
    }

    @Transactional
    fun authenticate(secret: String): DeviceToken? {
        if (!secret.startsWith(PREFIX)) return null
        val token = repository.findByTokenHash(hash(secret)) ?: return null
        if (!token.user.enabled) return null

        val now = Instant.now()
        if (token.lastUsedAt?.isAfter(now.minus(TOUCH_INTERVAL)) != true) {
            token.lastUsedAt = now
            repository.save(token)
        }
        return token
    }

    fun list(user: User): List<DeviceToken> = repository.findAllByUserOrderByCreatedAtDesc(user)

    @Transactional
    fun revoke(user: User, id: Long): Boolean = repository.deleteByIdAndUser(id, user) > 0

    @Transactional
    fun revokeAll(user: User) {
        val count = repository.deleteAllByUser(user)
        if (count > 0) log.info { "Revoked $count device token(s) of user '${user.username}'" }
    }
}
