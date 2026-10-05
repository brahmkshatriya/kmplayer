@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brahmkshatriya.kmplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.NativeInteropView
import androidx.compose.ui.viewinterop.NativeView
import dev.brahmkshatriya.kmplayer.KMPlayer
import dev.brahmkshatriya.kmplayer.KMVideoScale
import dev.brahmkshatriya.kmplayer.renderVideoFrameBgra

@Composable
internal actual fun PlatformKMVideoSurface(
    player: KMPlayer,
    modifier: Modifier,
    scale: KMVideoScale,
) {
    key(player, scale) {
        val view = remember(player, scale) {
            NativeInteropView(
                renderer = { target ->
                    player.renderVideoFrameBgra(
                        pixels = target.pixels,
                        width = target.width,
                        height = target.height,
                        stride = target.stride,
                        scale = scale,
                    )
                },
                continuousRendering = true,
            )
        }
        NativeView(
            factory = { view },
            modifier = modifier,
        )
    }
}
