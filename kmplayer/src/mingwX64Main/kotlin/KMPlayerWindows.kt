package dev.brahmkshatriya.kmplayer

import dev.brahmkshatriya.kmplayer.internal.MediaFoundationKMPlayerBackend
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi

/** Borrowed IMFMediaEngine* address. Valid only while this KMPlayer is open. */
public val KMPlayer.mediaEngineHandle: ULong?
    get() = (platformBackend as? MediaFoundationKMPlayerBackend)?.engineHandle

/** Renders the latest Media Foundation video frame into a BGRA8888 buffer. */
@OptIn(ExperimentalForeignApi::class)
public fun KMPlayer.renderVideoFrameBgra(
    pixels: COpaquePointer,
    width: Int,
    height: Int,
    stride: Int,
    scale: KMVideoScale = KMVideoScale.Fit,
): Boolean = (platformBackend as? MediaFoundationKMPlayerBackend)
    ?.renderBgra(pixels, width, height, stride, scale)
    ?: false
