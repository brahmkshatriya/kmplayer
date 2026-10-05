package dev.brahmkshatriya.kmplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.HtmlElementView
import dev.brahmkshatriya.kmplayer.KMPlayer
import dev.brahmkshatriya.kmplayer.KMVideoScale
import dev.brahmkshatriya.kmplayer.htmlVideoElement
import org.w3c.dom.HTMLVideoElement

@Composable
internal actual fun PlatformKMVideoSurface(
    player: KMPlayer,
    modifier: Modifier,
    scale: KMVideoScale,
) {
    HtmlElementView(
        factory = {
            requireNotNull(player.htmlVideoElement) { "KMPlayer does not expose an HTML video element" }
                .also { configureVideo(it, scale) }
        },
        modifier = modifier,
        update = { video -> configureVideo(video, scale) },
        onRelease = { video -> video.remove() },
    )
}

private fun configureVideo(video: HTMLVideoElement, scale: KMVideoScale) {
    video.controls = false
    video.style.width = "100%"
    video.style.height = "100%"
    video.style.objectFit = when (scale) {
        KMVideoScale.Fit -> "contain"
        KMVideoScale.Crop -> "cover"
        KMVideoScale.Fill -> "fill"
    }
}
