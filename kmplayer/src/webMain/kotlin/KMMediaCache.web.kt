package dev.brahmkshatriya.kmplayer.internal

import dev.brahmkshatriya.kmplayer.KMCacheConfig
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Browser Cache Storage persists media across reloads; blob URIs live only for this player. */
internal class BrowserKMMediaCache(private val config: KMCacheConfig) : KMMediaCache {
    private val objectUrls = mutableSetOf<String>()
    private val cacheName = config.browserCacheName()

    override suspend fun cachedUri(sourceUrl: String): String? = suspendCoroutine { continuation ->
        browserCachedMedia(cacheName, sourceUrl) { uri ->
            if (uri != null) objectUrls += uri
            continuation.resume(uri)
        }
    }

    override suspend fun store(sourceUrl: String): Unit = suspendCoroutine { continuation ->
        browserStoreMedia(cacheName, sourceUrl, config.maxSizeBytes.toDouble(), config.maxTrackBytes.toDouble()) {
            continuation.resume(Unit)
        }
    }

    override suspend fun invalidate(sourceUrl: String): Unit = suspendCoroutine { continuation ->
        browserInvalidateMedia(cacheName, sourceUrl) { continuation.resume(Unit) }
    }

    override fun close() {
        objectUrls.forEach(::browserReleaseMedia)
        objectUrls.clear()
    }
}

internal expect fun browserCachedMedia(cacheName: String, sourceUrl: String, done: (String?) -> Unit)
internal expect fun browserStoreMedia(cacheName: String, sourceUrl: String, maxBytes: Double, maxTrackBytes: Double, done: () -> Unit)
internal expect fun browserInvalidateMedia(cacheName: String, sourceUrl: String, done: () -> Unit)
internal expect fun browserReleaseMedia(uri: String)
