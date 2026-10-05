@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brahmkshatriya.kmplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitViewController
import dev.brahmkshatriya.kmplayer.KMPlayer
import dev.brahmkshatriya.kmplayer.KMVideoScale
import dev.brahmkshatriya.kmplayer.avPlayer
import platform.AVFoundation.AVLayerVideoGravityResize
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVKit.AVPlayerViewController

@Composable
internal actual fun PlatformKMVideoSurface(
    player: KMPlayer,
    modifier: Modifier,
    scale: KMVideoScale,
) {
    UIKitViewController(
        factory = {
            AVPlayerViewController().apply {
                showsPlaybackControls = false
                this.player = player.avPlayer
                videoGravity = scale.toVideoGravity()
            }
        },
        modifier = modifier,
        update = { controller ->
            controller.player = player.avPlayer
            controller.videoGravity = scale.toVideoGravity()
        },
        onRelease = { controller ->
            controller.player = null
        },
    )
}

private fun KMVideoScale.toVideoGravity(): String = when (this) {
    KMVideoScale.Fit -> AVLayerVideoGravityResizeAspect!!
    KMVideoScale.Crop -> AVLayerVideoGravityResizeAspectFill!!
    KMVideoScale.Fill -> AVLayerVideoGravityResize!!
}
