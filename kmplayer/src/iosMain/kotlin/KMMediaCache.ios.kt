package dev.brahmkshatriya.kmplayer.internal

import dev.brahmkshatriya.kmplayer.KMCacheConfig
import okio.FileSystem
import platform.Foundation.NSHomeDirectory

internal actual fun createPlatformMediaCache(config: KMCacheConfig): KMMediaCache =
    DiskKMMediaCache(config.diskDirectory(NSHomeDirectory() + "/Library/Caches/KMPlayer"), FileSystem.SYSTEM, config)
