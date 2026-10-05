package dev.brahmkshatriya.kmplayer.internal

import org.w3c.dom.HTMLMediaElement

internal interface HlsJs {
    fun attachMedia(media: HTMLMediaElement)
    fun loadSource(uri: String)
    fun destroy()
}

internal expect fun createHlsJs(): HlsJs?
