package org.gameyfin.app.saves.entities

import jakarta.persistence.*
import org.gameyfin.app.games.entities.Game
import org.gameyfin.app.users.entities.User
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import java.time.Instant

@Entity
class GameSave(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    val user: User,

    @ManyToOne(fetch = FetchType.EAGER)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    var game: Game?,

    val gameTitle: String? = null,

    @Column(nullable = false)
    var gamePath: String,

    // One "pluginId=originalId" per line
    @Column(length = 1024)
    var gameProviderIds: String? = null,

    // Server-generated file name, never client input
    @Column(nullable = false)
    val contentId: String,

    @Column(nullable = false)
    val contentLength: Long,

    @Column(nullable = false, length = 64)
    val contentHash: String,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    val platform: SavePlatform = SavePlatform.UNKNOWN,

    val installationId: String? = null,

    val deviceName: String? = null,

    val ludusaviTitle: String? = null,

    @Column(nullable = false)
    var locked: Boolean = false,

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    var createdAt: Instant? = null
)
