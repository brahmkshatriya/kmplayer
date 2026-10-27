@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brahmkshatriya.kmplayer.internal

import dev.brahmkshatriya.kmplayer.KMCacheConfig
import kotlinx.cinterop.toKString
import okio.FileSystem
import platform.posix.getenv

internal actual fun createPlatformMediaCache(config: KMCacheConfig): KMMediaCache {
    val home = getenv("HOME")?.toKString()?.takeIf(String::isNotEmpty) ?: "."
    return DiskKMMediaCache(config.diskDirectory("$home/Library/Caches/KMPlayer"), FileSystem.SYSTEM, config)
}
