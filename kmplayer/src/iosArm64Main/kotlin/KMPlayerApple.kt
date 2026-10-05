@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brahmkshatriya.kmplayer

import dev.brahmkshatriya.kmplayer.internal.AvFoundationKMPlayerBackend
import platform.AVFoundation.AVPlayer

/** Backing AVPlayer for AVPlayerLayer, AVPlayerView, or AVPlayerViewController integration. */
public val KMPlayer.avPlayer: AVPlayer?
    get() = (platformBackend as? AvFoundationKMPlayerBackend)?.player
