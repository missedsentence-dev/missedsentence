package android.media.session

import android.content.Context
import android.media.MediaMetadata
import android.os.Bundle
import android.os.Handler

class MediaSession(context: Context, tag: String) {
    abstract class Callback {
        open fun onPlay() {}
        open fun onPause() {}
        open fun onStop() {}
        open fun onSkipToNext() {}
        open fun onSkipToPrevious() {}
        open fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {}
        open fun onPlayFromSearch(query: String?, extras: Bundle?) {}
    }

    class Token

    var callback: Callback? = null
    var isActive = false
    var state: PlaybackState? = null
    var lastMetadata: MediaMetadata? = null
    val sessionToken = Token()

    init {
        last = this
    }

    fun setCallback(c: Callback?, h: Handler) {
        callback = c
    }
    fun setPlaybackState(s: PlaybackState) {
        state = s
    }
    fun setMetadata(m: MediaMetadata) {
        lastMetadata = m
    }
    fun release() {}

    companion object {
        var last: MediaSession? = null
    }
}

class PlaybackState(val state: Int, val position: Long, val speed: Float, val actions: Long) {
    class Builder {
        private var actions = 0L
        private var state = 0
        private var position = 0L
        private var speed = 0f
        fun setActions(a: Long): Builder {
            actions = a
            return this
        }
        fun setState(s: Int, p: Long, sp: Float): Builder {
            state = s
            position = p
            speed = sp
            return this
        }
        fun build() = PlaybackState(state, position, speed, actions)
    }

    companion object {
        const val STATE_PAUSED = 2
        const val STATE_PLAYING = 3
        const val ACTION_STOP = 1L
        const val ACTION_PAUSE = 2L
        const val ACTION_PLAY = 4L
        const val ACTION_SKIP_TO_PREVIOUS = 16L
        const val ACTION_SKIP_TO_NEXT = 32L
        const val ACTION_PLAY_PAUSE = 512L
        const val ACTION_PLAY_FROM_MEDIA_ID = 1024L
        const val ACTION_PLAY_FROM_SEARCH = 2048L
    }
}
