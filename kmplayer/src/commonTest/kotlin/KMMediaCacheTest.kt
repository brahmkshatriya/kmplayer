package dev.brahmkshatriya.kmplayer

import dev.brahmkshatriya.kmplayer.internal.KMMediaCache
import dev.brahmkshatriya.kmplayer.internal.KMPlayerBackend
import dev.brahmkshatriya.kmplayer.internal.browserCacheName
import dev.brahmkshatriya.kmplayer.internal.diskDirectory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration

class KMMediaCacheTest {
    @Test
    fun customLocationAndNameResolveToSeparateDirectory() {
        assertEquals("/custom/caches/EchoAudio", KMCacheConfig(
            baseDirectory = "/custom/caches", cacheName = "EchoAudio",
        ).diskDirectory("/unused"))
        assertEquals("/default/kmplayer/media", KMCacheConfig().diskDirectory("/default/kmplayer"))
    }

    @Test
    fun cacheStorageNamespaceIncludesConfiguredLocationAndName() {
        assertEquals("kmplayer-media-v1", KMCacheConfig().browserCacheName())
        assertEquals("kmplayer-project/EchoAudio-v1", KMCacheConfig(
            baseDirectory = "project", cacheName = "EchoAudio",
        ).browserCacheName())
    }

    @Test
    fun rejectsInvalidCacheNamesAndEmptyBaseDirectory() {
        for (name in listOf("", ".", "..", "../sibling", "nested/path", "bad\\name", "oops:name", "trailing.")) {
            assertFailsWith<IllegalArgumentException> { KMCacheConfig(cacheName = name) }
        }
        assertFailsWith<IllegalArgumentException> { KMCacheConfig(baseDirectory = "  ") }
    }

    @Test
    fun cachedFileLoadsButOriginalUrlRemainsVisible() = runBlocking {
        val url = "https://example.org/song.mp3"
        val cache = TestMediaCache(cached = "file:///tmp/cached-song.mp3")
        val backend = CacheTestBackend()
        val player = KMPlayer(backend, KMPlayerConfig(), cache)
        try {
            assertIs<KMResult.Success<Unit>>(player.load(url, playWhenReady = true))
            assertEquals(cache.cached, backend.loaded.single().uri)
            assertEquals(url, withTimeout(2_000) { player.state.first { it.uri == url } }.uri)
            assertTrue(cache.stored.isEmpty())
        } finally { player.close() }
        assertTrue(cache.closed)
    }

    @Test
    fun failedCachedLoadRetriesRemoteUrl() = runBlocking {
        val url = "https://example.org/song.mp3"
        val cache = TestMediaCache(cached = "file:///tmp/cached-song.mp3")
        val backend = CacheTestBackend(rejectLocalFiles = true)
        val player = KMPlayer(backend, KMPlayerConfig(), cache)
        try {
            assertIs<KMResult.Success<Unit>>(player.load(url))
            assertEquals(listOf(cache.cached, url), backend.loaded.map { it.uri })
            assertEquals(listOf(url), cache.invalidated)
            withTimeout(2_000) {
                while (cache.stored.isEmpty()) delay(10)
            }
            assertEquals(listOf(url), cache.stored)
        } finally { player.close() }
    }

    @Test
    fun hlsIsNotStoredAsAnOrdinaryFile() = runBlocking {
        val cache = TestMediaCache(cached = "file:///tmp/not-a-playlist.mp3")
        val backend = CacheTestBackend()
        val player = KMPlayer(backend, KMPlayerConfig(), cache)
        try {
            val url = "https://example.org/master.m3u8"
            assertIs<KMResult.Success<Unit>>(player.load(url))
            assertEquals(url, backend.loaded.single().uri)
            assertTrue(cache.lookups.isEmpty())
            assertTrue(cache.stored.isEmpty())
        } finally { player.close() }
    }
}

private class TestMediaCache(val cached: String?) : KMMediaCache {
    val lookups = mutableListOf<String>()
    val stored = mutableListOf<String>()
    val invalidated = mutableListOf<String>()
    var closed = false
    override suspend fun cachedUri(sourceUrl: String): String? { lookups.add(sourceUrl); return cached }
    override suspend fun store(sourceUrl: String) { stored.add(sourceUrl) }
    override suspend fun invalidate(sourceUrl: String) { invalidated.add(sourceUrl) }
    override fun close() { closed = true }
}

private class CacheTestBackend(private val rejectLocalFiles: Boolean = false) : KMPlayerBackend {
    private val mutableState = MutableStateFlow(KMPlayerState())
    override val state: StateFlow<KMPlayerState> = mutableState
    override val events: SharedFlow<KMPlayerEvent> = MutableSharedFlow()
    override val capabilities: KMPlayerCapabilities = KMPlayerCapabilities(backend = KMBackendKind.GStreamer, hls = true)
    val loaded = mutableListOf<KMMediaSource>()

    override suspend fun load(source: KMMediaSource, playWhenReady: Boolean): KMResult<Unit> {
        loaded += source
        if (rejectLocalFiles && source.uri.startsWith("file:")) {
            return KMResult.Failure(KMPlayerError("cached-file-bad", "Cannot read cached file"))
        }
        mutableState.value = KMPlayerState(mediaStatus = KMMediaStatus.Ready, uri = source.uri)
        return KMResult.Success(Unit)
    }
    override fun play(): KMResult<Unit> = KMResult.Success(Unit)
    override fun pause(): KMResult<Unit> = KMResult.Success(Unit)
    override fun stop(): KMResult<Unit> = KMResult.Success(Unit)
    override fun seekTo(position: Duration): KMResult<Unit> = KMResult.Success(Unit)
    override fun setVolume(volume: Double): KMResult<Unit> = KMResult.Success(Unit)
    override fun setMuted(muted: Boolean): KMResult<Unit> = KMResult.Success(Unit)
    override fun setSpeed(speed: Double): KMResult<Unit> = KMResult.Success(Unit)
    override fun setVideoEnabled(enabled: Boolean): KMResult<Unit> = KMResult.Success(Unit)
    override fun close() = Unit
}
