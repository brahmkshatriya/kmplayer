@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brahmkshatriya.kmplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AppKitView
import dev.brahmkshatriya.kmplayer.KMPlayer
import dev.brahmkshatriya.kmplayer.KMVideoScale
import dev.brahmkshatriya.kmplayer.avPlayer
import platform.AVFoundation.AVLayerVideoGravityResize
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVKit.AVPlayerView
import platform.AVKit.AVPlayerViewControlsStyleNone

@Composable
internal actual fun PlatformKMVideoSurface(
    player: KMPlayer,
    modifier: Modifier,
    scale: KMVideoScale,
) {
    AppKitView(
        factory = {
            AVPlayerView().apply {
                controlsStyle = AVPlayerViewControlsStyleNone
                this.player = player.avPlayer
                videoGravity = scale.toVideoGravity()
            }
        },
        modifier = modifier,
        update = { view ->
            view.player = player.avPlayer
            view.videoGravity = scale.toVideoGravity()
        },
        onRelease = { view ->
            view.player = null
        },
    )
}

private fun KMVideoScale.toVideoGravity(): String = when (this) {
    KMVideoScale.Fit -> AVLayerVideoGravityResizeAspect!!
    KMVideoScale.Crop -> AVLayerVideoGravityResizeAspectFill!!
    KMVideoScale.Fill -> AVLayerVideoGravityResize!!
}
