package dev.brahmkshatriya.kmplayer

import dev.brahmkshatriya.kmplayer.internal.KMPlayerBackend
import dev.brahmkshatriya.kmplayer.internal.KMMediaCache
import dev.brahmkshatriya.kmplayer.internal.createPlatformKMPlayerBackend
import dev.brahmkshatriya.kmplayer.internal.createPlatformMediaCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration

public enum class KMMediaStatus {
    Idle,
    Opening,
    Ready,
    Ended,
    Error,
    Released,
}

public enum class KMBackendKind {
    Media3,
    AvFoundation,
    MediaFoundation,
    GStreamer,
    HtmlMedia,
}

public enum class KMMediaSourceType {
    Auto,
    Hls,
}

/** Video scaling policy shared by native and Compose surfaces. */
public enum class KMVideoScale {
    Fit,
    Crop,
    Fill,
}

public data class KMMediaSource(
    val uri: String,
    val mimeType: String? = null,
    val type: KMMediaSourceType = KMMediaSourceType.Auto,
) {
    public val isHls: Boolean
        get() = type == KMMediaSourceType.Hls ||
            mimeType.equals("application/vnd.apple.mpegurl", ignoreCase = true) ||
            mimeType.equals("application/x-mpegurl", ignoreCase = true) ||
            uri.substringBefore('?').substringBefore('#').endsWith(".m3u8", ignoreCase = true)
}

public data class KMPlayerCapabilities(
    val backend: KMBackendKind,
    val audio: Boolean = true,
    val video: Boolean = true,
    val hls: Boolean,
    val seeking: Boolean = true,
    val playbackRate: Boolean = true,
    val nativeVideoSurface: Boolean = true,
)

public data class KMPlayerState(
    val mediaStatus: KMMediaStatus = KMMediaStatus.Idle,
    val playWhenReady: Boolean = false,
    val isBuffering: Boolean = false,
    /** End of a contiguous buffered interval from the playhead, or null if unknown. */
    val bufferedPosition: Duration? = null,
    val position: Duration = Duration.ZERO,
    val duration: Duration? = null,
    val volume: Double = 100.0,
    val muted: Boolean = false,
    val speed: Double = 1.0,
    val seekable: Boolean = false,
    val uri: String? = null,
    val title: String? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val isLive: Boolean = false,
    val error: KMPlayerError? = null,
) {
    public val isPlaying: Boolean
        get() = mediaStatus == KMMediaStatus.Ready && playWhenReady && !isBuffering
}

public sealed interface KMPlayerEvent {
    public data object MediaLoaded : KMPlayerEvent
    public data object MediaEnded : KMPlayerEvent
    public data class PlaybackError(val error: KMPlayerError) : KMPlayerEvent
}

/**
 * Persistent caching for finite HTTP(S) media. HLS/live streams are not downloaded.
 *
 * [baseDirectory] is the parent cache directory on native platforms. When null,
 * the platform's default KMPlayer cache parent is used. [cacheName] is the
 * cache's subdirectory. On web, the two fields form a Cache Storage namespace;
 * browsers manage the physical location themselves.
 */
public data class KMCacheConfig(
    val maxSizeBytes: Long = 256L * 1024 * 1024,
    val maxTrackBytes: Long = 48L * 1024 * 1024,
    val baseDirectory: String? = null,
    val cacheName: String = "media",
) {
    init {
        require(maxSizeBytes > 0L && maxTrackBytes in 1..maxSizeBytes) {
            "Cache limits must be positive and maxTrackBytes must not exceed maxSizeBytes"
        }
        require(baseDirectory == null || baseDirectory.isNotBlank()) {
            "Cache base directory must not be blank; use null for the default"
        }
        require(cacheName.isNotBlank() && cacheName != "." && cacheName != ".." &&
            cacheName == cacheName.trim() && cacheName.none { it in "/\\<>:\"|?*" || it.code < 32 } &&
            !cacheName.endsWith('.')) {
            "Cache name must be a valid single directory name"
        }
    }
}

public data class KMPlayerConfig(
    /** Refuse to construct a backend that cannot play HLS. */
    val requireHls: Boolean = true,
    /**
     * Video policy for this player instance.
     *
     * `null` (default) is automatic: video stays disabled while no KMPlayer-managed video
     * surface is attached and is enabled while at least one surface is attached.
     * `true` forces audio-only playback. `false` keeps video enabled even without a surface.
     */
    val audioOnly: Boolean? = null,
    /** On by default. Set to null to disable persistent HTTP media caching. */
    val cache: KMCacheConfig? = KMCacheConfig(),
)

/**
 * Cross-platform system media player.
 *
 * Backends:
 * Android -> Media3/ExoPlayer
 * Apple -> AVFoundation
 * Windows -> Media Foundation / IMFMediaEngine
 * Linux -> GStreamer
 * JS/Wasm -> HTMLMediaElement with hls.js fallback
 */
public class KMPlayer internal constructor(
    internal val platformBackend: KMPlayerBackend,
    public val config: KMPlayerConfig,
    cacheOverride: KMMediaCache? = null,
) : AutoCloseable {
    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cacheDelegate = lazy { cacheOverride ?: config.cache?.let(::createPlatformMediaCache) }
    private val mediaCache: KMMediaCache? get() = cacheDelegate.value
    private val localUriMapping = MutableStateFlow<Pair<String, String>?>(null)
    /** Preserve the requested URL and expose completed cached files as fully buffered. */
    public val state: StateFlow<KMPlayerState> = combine(platformBackend.state, localUriMapping) { value, mapping ->
        if (mapping != null && value.uri == mapping.first) value.copy(
            uri = mapping.second,
            bufferedPosition = value.bufferedPosition ?: value.duration,
        )
        else value
    }.stateIn(cacheScope, SharingStarted.Eagerly, platformBackend.state.value)
    public val events: SharedFlow<KMPlayerEvent> get() = platformBackend.events
    public val capabilities: KMPlayerCapabilities get() = platformBackend.capabilities

    /** Current playback position. Use [seekTo] to change it. */
    public val position: Duration get() = state.value.position

    /** Duration of the current item, or `null` while unknown/live. */
    public val duration: Duration? get() = state.value.duration

    public val isPlaying: Boolean get() = state.value.isPlaying
    public val isBuffering: Boolean get() = state.value.isBuffering
    public val isLive: Boolean get() = state.value.isLive
    public val seekable: Boolean get() = state.value.seekable

    private var videoSurfaceCount: Int = 0

    /**
     * Player volume in the 0..100 range.
     *
     * Property assignment is the convenient API and throws if the platform rejects the change.
     * Use [setVolume] when explicit [KMResult] handling is preferred.
     */
    public var volume: Double
        get() = state.value.volume
        set(value) {
            setVolume(value).getOrThrow()
        }

    public var muted: Boolean
        get() = state.value.muted
        set(value) {
            setMuted(value).getOrThrow()
        }

    public var speed: Double
        get() = state.value.speed
        set(value) {
            setSpeed(value).getOrThrow()
        }

    public suspend fun load(
        uri: String,
        playWhenReady: Boolean = false,
        mimeType: String? = null,
        type: KMMediaSourceType = KMMediaSourceType.Auto,
    ): KMResult<Unit> = load(KMMediaSource(uri, mimeType, type), playWhenReady)

    public suspend fun load(source: KMMediaSource, playWhenReady: Boolean = false): KMResult<Unit> {
        // HLS may consist of playlists and multiple segments: never cache it as a single file.
        val cache = mediaCache?.takeIf {
            !source.isHls && (source.uri.startsWith("https://") || source.uri.startsWith("http://"))
        }
        val localUri = cache?.cachedUri(source.uri)
        if (localUri != null) {
            localUriMapping.value = localUri to source.uri
            val cachedResult = platformBackend.load(source.copy(uri = localUri), playWhenReady)
            if (cachedResult is KMResult.Success) return cachedResult
            // A cached file may be unsupported or damaged; retry the original URL.
            cache.invalidate(source.uri)
        }
        localUriMapping.value = null
        val result = platformBackend.load(source, playWhenReady)
        if (result is KMResult.Success && cache != null) {
            cacheScope.launch { cache.store(source.uri) }
        }
        return result
    }

    public fun play(): KMResult<Unit> = platformBackend.play()
    public fun pause(): KMResult<Unit> = platformBackend.pause()
    public fun stop(): KMResult<Unit> = platformBackend.stop()
    public fun seekTo(position: Duration): KMResult<Unit> = platformBackend.seekTo(position)
    public fun setVolume(volume: Double): KMResult<Unit> = platformBackend.setVolume(volume)
    public fun setMuted(muted: Boolean): KMResult<Unit> = platformBackend.setMuted(muted)
    public fun setSpeed(speed: Double): KMResult<Unit> = platformBackend.setSpeed(speed)

    /** Select a player-specific output where supported (macOS AVFoundation).
     * Pass null to return to the system default. Other backends may reject the request.
     */
    public fun setAudioOutputDevice(deviceId: String?): KMResult<Unit> = platformBackend.setAudioOutputDevice(deviceId)

    /** Analyze decoded audio from this player without capturing other applications' sound.
     * Analysis is optional and may disable hardware offload on some platforms.
     * Backends without a compatible audio tap report audio-analysis-unavailable. */
    public fun createAudioAnalyzer(config: KMAudioAnalyzerConfig = KMAudioAnalyzerConfig()): KMResult<KMAudioAnalyzer> {
        if (!config.isValid()) return KMResult.Failure(KMPlayerError(
            "invalid-audio-analyzer-config", "fftSize must be a power of two from 256 to 8192, updateRateHz 1..60, and smoothing in [0, 1).",
        ))
        return platformBackend.createAudioAnalyzer(config)
    }

    /**
     * Registers a video surface with this player.
     *
     * Compose's [KMVideoSurface] does this automatically. Custom/native video integrations
     * should keep the returned registration alive while their surface is attached.
     */
    public fun registerVideoSurface(): KMResult<KMVideoSurfaceRegistration> {
        if (!capabilities.video) {
            return KMResult.Failure(
                KMPlayerError("video-disabled", "This KMPlayer was created in forced audio-only mode."),
            )
        }
        videoSurfaceCount += 1
        if (videoSurfaceCount == 1 && config.audioOnly == null) {
            when (val result = platformBackend.setVideoEnabled(true)) {
                is KMResult.Success -> Unit
                is KMResult.Failure -> {
                    videoSurfaceCount -= 1
                    return result
                }
            }
        }
        return KMResult.Success(KMVideoSurfaceRegistration(this))
    }

    internal fun unregisterVideoSurface() {
        if (videoSurfaceCount <= 0) return
        videoSurfaceCount -= 1
        if (videoSurfaceCount == 0 && config.audioOnly == null) {
            platformBackend.setVideoEnabled(false)
        }
    }

    override fun close() {
        cacheScope.cancel()
        if (cacheDelegate.isInitialized()) mediaCache?.close()
        platformBackend.close()
    }

    public companion object {
        public fun create(config: KMPlayerConfig = KMPlayerConfig()): KMResult<KMPlayer> =
            when (val backend = createPlatformKMPlayerBackend(config)) {
                is KMResult.Success -> {
                    if (config.requireHls && !backend.value.capabilities.hls) {
                        backend.value.close()
                        KMResult.Failure(
                            KMPlayerError(
                                code = "hls-unavailable",
                                message = "The platform backend is available but HLS support is not installed.",
                            ),
                        )
                    } else {
                        KMResult.Success(KMPlayer(backend.value, config))
                    }
                }
                is KMResult.Failure -> backend
            }
    }
}

public class KMVideoSurfaceRegistration internal constructor(
    private val player: KMPlayer,
) : AutoCloseable {
    private var closed: Boolean = false

    override fun close() {
        if (closed) return
        closed = true
        player.unregisterVideoSurface()
    }
}
