package dev.brahmkshatriya.kmplayer

import dev.brahmkshatriya.kmplayer.internal.JvmGStreamerKMPlayerBackend
import dev.brahmkshatriya.kmplayer.internal.JvmKMPlayerBackend
import dev.brahmkshatriya.kmplayer.internal.JvmNativeKMPlayerBackend
import kotlinx.coroutines.flow.StateFlow
import org.freedesktop.gstreamer.elements.PlayBin

/** Borrowed GStreamer pipeline, owned by this player. Never dispose it or use it after close. */
public val KMPlayer.gStreamerPlayBin: PlayBin?
    get() = (platformBackend as? JvmGStreamerKMPlayerBackend)?.pipeline

/** A decoded, tightly packed BGRA frame. Treat [pixels] as read-only. */
public class KMVideoFrame internal constructor(
    public val width: Int,
    public val height: Int,
    public val pixels: ByteArray,
) {
    public val stride: Int get() = width * 4
}

/** Latest decoded frame, updated at the media frame rate while video is enabled. */
public val KMPlayer.videoFrames: StateFlow<KMVideoFrame?>
    get() = (platformBackend as JvmKMPlayerBackend).videoFrames

/** Borrowed AVPlayer address on macOS JVM; null on other backends or after close. */
public val KMPlayer.avPlayerHandle: Long?
    get() = (platformBackend as? JvmNativeKMPlayerBackend)
        ?.takeIf { it.capabilities.backend == KMBackendKind.AvFoundation }?.nativeHandle

/** Borrowed IMFMediaEngine address on Windows JVM. Video mode changes may replace it. */
public val KMPlayer.mediaEngineHandle: Long?
    get() = (platformBackend as? JvmNativeKMPlayerBackend)
        ?.takeIf { it.capabilities.backend == KMBackendKind.MediaFoundation }?.nativeHandle
