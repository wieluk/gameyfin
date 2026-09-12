package org.gameyfin.app.saves.entities

// Save paths differ per OS, so a save may not restore on another platform
enum class SavePlatform {
    WINDOWS,
    LINUX,
    PROTON,
    MACOS,
    UNKNOWN
}
