package dev.brahmkshatriya.kmplayer.internal

import dev.brahmkshatriya.kmplayer.KMMediaSource
import dev.brahmkshatriya.kmplayer.KMAudioAnalyzer
import dev.brahmkshatriya.kmplayer.KMAudioAnalyzerConfig
import dev.brahmkshatriya.kmplayer.KMPlayerError
import dev.brahmkshatriya.kmplayer.KMPlayerCapabilities
import dev.brahmkshatriya.kmplayer.KMPlayerConfig
import dev.brahmkshatriya.kmplayer.KMPlayerEvent
import dev.brahmkshatriya.kmplayer.KMPlayerState
import dev.brahmkshatriya.kmplayer.KMResult
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

internal interface KMPlayerBackend : AutoCloseable {
    val state: StateFlow<KMPlayerState>
    val events: SharedFlow<KMPlayerEvent>
    val capabilities: KMPlayerCapabilities

    suspend fun load(source: KMMediaSource, playWhenReady: Boolean): KMResult<Unit>
    fun play(): KMResult<Unit>
    fun pause(): KMResult<Unit>
    fun stop(): KMResult<Unit>
    fun seekTo(position: Duration): KMResult<Unit>
    fun setVolume(volume: Double): KMResult<Unit>
    fun setMuted(muted: Boolean): KMResult<Unit>
    fun setSpeed(speed: Double): KMResult<Unit>
    fun setVideoEnabled(enabled: Boolean): KMResult<Unit>
    fun createAudioAnalyzer(config: KMAudioAnalyzerConfig): KMResult<KMAudioAnalyzer> =
        KMResult.Failure(KMPlayerError("audio-analysis-unavailable", "Audio analysis is not supported by this playback backend."))
}

internal expect fun createPlatformKMPlayerBackend(config: KMPlayerConfig): KMResult<KMPlayerBackend>
