@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brahmkshatriya.kmplayer.internal

import dev.brahmkshatriya.kmplayer.KMCacheConfig
import kotlinx.cinterop.toKString
import okio.FileSystem
import platform.posix.getenv

internal actual fun createPlatformMediaCache(config: KMCacheConfig): KMMediaCache {
    fun env(name: String) = getenv(name)?.toKString()?.takeIf(String::isNotEmpty)
    val root = (env("LOCALAPPDATA") ?: env("TEMP") ?: ".").replace('\\', '/')
    return DiskKMMediaCache(config.diskDirectory("$root/KMPlayer"), FileSystem.SYSTEM, config)
}
