package dev.brahmkshatriya.kmplayer.internal

import dev.brahmkshatriya.kmplayer.KMCacheConfig
import dev.brahmkshatriya.kmplayer.KMPlayerAndroid
import okio.FileSystem

internal actual fun createPlatformMediaCache(config: KMCacheConfig): KMMediaCache {
    val context = checkNotNull(KMPlayerAndroid.applicationContext) {
        "Initialize KMPlayerAndroid before creating a player"
    }
    return DiskKMMediaCache(config.diskDirectory(context.cacheDir.resolve("kmplayer").absolutePath), FileSystem.SYSTEM, config)
}
