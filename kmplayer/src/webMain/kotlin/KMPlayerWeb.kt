package dev.brahmkshatriya.kmplayer

import dev.brahmkshatriya.kmplayer.internal.WebKMPlayerBackend
import org.w3c.dom.HTMLMediaElement
import org.w3c.dom.HTMLVideoElement

/** Backing browser media element (`audio` in audio-only mode, otherwise `video`). */
public val KMPlayer.htmlMediaElement: HTMLMediaElement?
    get() = (platformBackend as? WebKMPlayerBackend)?.media

/** The backing browser video element. Attach this element to your DOM or Compose web interop surface. */
public val KMPlayer.htmlVideoElement: HTMLVideoElement?
    get() = (platformBackend as? WebKMPlayerBackend)?.media as? HTMLVideoElement
