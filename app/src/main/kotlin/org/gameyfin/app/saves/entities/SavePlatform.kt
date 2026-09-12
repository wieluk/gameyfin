package org.gameyfin.app.saves.entities

// Save locations are not portable across systems, so clients warn before restoring a mismatch
enum class SavePlatform {
    WINDOWS,
    LINUX,
    PROTON,
    MACOS,
    UNKNOWN
}
