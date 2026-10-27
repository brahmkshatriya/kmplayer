package dev.brahmkshatriya.kmplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.Layout
import dev.brahmkshatriya.kmplayer.KMPlayer
import dev.brahmkshatriya.kmplayer.KMVideoScale
import dev.brahmkshatriya.kmplayer.videoFrames
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode

@Composable
internal actual fun PlatformKMVideoSurface(player: KMPlayer, modifier: Modifier, scale: KMVideoScale) {
    val frame by player.videoFrames.collectAsState()
    val image = remember(frame) {
        frame?.let {
            Image.makeRaster(ImageInfo(it.width, it.height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), it.pixels, it.stride)
        }
    }
    DisposableEffect(image) { onDispose { image?.close() } }
    Layout(content = {}, modifier = modifier.drawBehind {
        drawRect(Color.Black)
        val current = image ?: return@drawBehind
        if (size.width <= 0 || size.height <= 0) return@drawBehind
        val x = size.width / current.width
        val y = size.height / current.height
        val ratio = if (scale == KMVideoScale.Crop) maxOf(x, y) else minOf(x, y)
        val width = if (scale == KMVideoScale.Fill) size.width else current.width * ratio
        val height = if (scale == KMVideoScale.Fill) size.height else current.height * ratio
        val left = (size.width - width) / 2
        val top = (size.height - height) / 2
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.clipRect(Rect.makeWH(size.width, size.height))
            native.drawImageRect(current, Rect.makeWH(current.width.toFloat(), current.height.toFloat()),
                Rect.makeXYWH(left, top, width, height), SamplingMode.LINEAR, null, true)
            native.restore()
        }
    }) { _, constraints ->
        layout(constraints.minWidth, constraints.minHeight) {}
    }
}
