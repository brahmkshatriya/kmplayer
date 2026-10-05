package dev.brahmkshatriya.kmplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.brahmkshatriya.kmplayer.KMPlayer
import dev.brahmkshatriya.kmplayer.KMVideoScale
import dev.brahmkshatriya.kmplayer.exoPlayer

@Composable
internal actual fun PlatformKMVideoSurface(
    player: KMPlayer,
    modifier: Modifier,
    scale: KMVideoScale,
) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                this.player = player.exoPlayer
                resizeMode = scale.toResizeMode()
            }
        },
        modifier = modifier,
        update = { view ->
            view.player = player.exoPlayer
            view.resizeMode = scale.toResizeMode()
        },
        onRelease = { view ->
            view.player = null
        },
    )
}

private fun KMVideoScale.toResizeMode(): Int = when (this) {
    KMVideoScale.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    KMVideoScale.Crop -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    KMVideoScale.Fill -> AspectRatioFrameLayout.RESIZE_MODE_FILL
}
