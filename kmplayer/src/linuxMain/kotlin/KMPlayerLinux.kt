package dev.brahmkshatriya.kmplayer

import dev.brahmkshatriya.kmplayer.internal.GStreamerKMPlayerBackend
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi

/** Raw GstElement* value for the backing playbin, for advanced native integration. */
public val KMPlayer.gStreamerPipelineHandle: ULong?
    get() = (platformBackend as? GStreamerKMPlayerBackend)?.pipelineHandle

/**
 * Renders the latest decoded video frame into a BGRA8888 buffer.
 * This is a low-level surface hook used by Compose Native and other framebuffer integrations.
 */
@OptIn(ExperimentalForeignApi::class)
public fun KMPlayer.renderVideoFrameBgra(
    pixels: COpaquePointer,
    width: Int,
    height: Int,
    stride: Int,
    scale: KMVideoScale = KMVideoScale.Fit,
): Boolean = (platformBackend as? GStreamerKMPlayerBackend)
    ?.renderBgra(pixels, width, height, stride, scale)
    ?: false
