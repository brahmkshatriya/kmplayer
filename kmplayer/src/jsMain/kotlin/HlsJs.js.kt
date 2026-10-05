package dev.brahmkshatriya.kmplayer.internal

import org.w3c.dom.HTMLMediaElement

@JsModule("hls.js")
@JsNonModule
private external class HlsExternal {
    constructor()

    fun attachMedia(media: HTMLMediaElement)
    fun loadSource(url: String)
    fun destroy()

    companion object {
        fun isSupported(): Boolean
    }
}

private class JsHlsController(private val hls: HlsExternal) : HlsJs {
    override fun attachMedia(media: HTMLMediaElement) {
        hls.attachMedia(media)
    }

    override fun loadSource(uri: String) {
        hls.loadSource(uri)
    }

    override fun destroy() {
        hls.destroy()
    }
}

internal actual fun createHlsJs(): HlsJs? =
    if (HlsExternal.isSupported()) JsHlsController(HlsExternal()) else null
