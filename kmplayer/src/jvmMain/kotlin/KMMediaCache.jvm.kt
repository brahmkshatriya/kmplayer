package dev.brahmkshatriya.kmplayer.internal

import dev.brahmkshatriya.kmplayer.KMCacheConfig
import okio.FileSystem
import java.io.File
import java.util.Locale

internal actual fun createPlatformMediaCache(config: KMCacheConfig): KMMediaCache {
    val home = System.getProperty("user.home")
    val os = System.getProperty("os.name").lowercase(Locale.ROOT)
    val parent = when {
        os.contains("mac") -> "$home/Library/Caches"
        os.contains("win") -> System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank) ?: "$home/AppData/Local"
        else -> System.getenv("XDG_CACHE_HOME")?.takeIf(String::isNotBlank) ?: "$home/.cache"
    }
    return DiskKMMediaCache(config.diskDirectory(File(parent, "kmplayer").path), FileSystem.SYSTEM, config)
}
