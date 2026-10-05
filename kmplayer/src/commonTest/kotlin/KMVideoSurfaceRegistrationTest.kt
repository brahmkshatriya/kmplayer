package dev.brahmkshatriya.kmplayer

import dev.brahmkshatriya.kmplayer.internal.KMPlayerBackend
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration

class KMVideoSurfaceRegistrationTest {
    @Test
    fun autoModeEnablesOnFirstSurfaceAndDisablesOnLastSurface() {
        val backend = FakeBackend(video = true)
        val player = KMPlayer(backend, KMPlayerConfig(audioOnly = null))

        val first = player.registerVideoSurface().getOrThrow()
        val second = player.registerVideoSurface().getOrThrow()
        assertEquals(listOf(true), backend.videoChanges)

        first.close()
        assertEquals(listOf(true), backend.videoChanges)

        second.close()
        assertEquals(listOf(true, false), backend.videoChanges)
    }

    @Test
    fun forcedVideoDoesNotFollowSurfaceLifetime() {
        val backend = FakeBackend(video = true)
        val player = KMPlayer(backend, KMPlayerConfig(audioOnly = false))
        val registration = player.registerVideoSurface().getOrThrow()
        registration.close()
        assertEquals(emptyList(), backend.videoChanges)
    }

    @Test
    fun forcedAudioRejectsVideoSurface() {
        val backend = FakeBackend(video = false)
        val player = KMPlayer(backend, KMPlayerConfig(audioOnly = true))
        assertIs<KMResult.Failure>(player.registerVideoSurface())
        assertEquals(emptyList(), backend.videoChanges)
    }
}

private class FakeBackend(video: Boolean) : KMPlayerBackend {
    private val mutableState = MutableStateFlow(KMPlayerState())
    private val mutableEvents = MutableSharedFlow<KMPlayerEvent>()
    override val state: StateFlow<KMPlayerState> = mutableState
    override val events: SharedFlow<KMPlayerEvent> = mutableEvents
    override val capabilities: KMPlayerCapabilities = KMPlayerCapabilities(
        backend = KMBackendKind.Media3,
        video = video,
        hls = true,
        nativeVideoSurface = video,
    )
    val videoChanges = mutableListOf<Boolean>()

    override suspend fun load(source: KMMediaSource, playWhenReady: Boolean): KMResult<Unit> = KMResult.Success(Unit)
    override fun play(): KMResult<Unit> = KMResult.Success(Unit)
    override fun pause(): KMResult<Unit> = KMResult.Success(Unit)
    override fun stop(): KMResult<Unit> = KMResult.Success(Unit)
    override fun seekTo(position: Duration): KMResult<Unit> = KMResult.Success(Unit)
    override fun setVolume(volume: Double): KMResult<Unit> = KMResult.Success(Unit)
    override fun setMuted(muted: Boolean): KMResult<Unit> = KMResult.Success(Unit)
    override fun setSpeed(speed: Double): KMResult<Unit> = KMResult.Success(Unit)
    override fun setVideoEnabled(enabled: Boolean): KMResult<Unit> {
        videoChanges += enabled
        return KMResult.Success(Unit)
    }
    override fun close() = Unit
}
