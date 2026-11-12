package android.media

import android.os.FakeClock
import android.os.Handler
import java.io.FileDescriptor

class AudioAttributes {
    class Builder {
        fun setUsage(u: Int) = this
        fun setContentType(c: Int) = this
        fun build() = AudioAttributes()
    }

    companion object {
        const val USAGE_MEDIA = 1
        const val CONTENT_TYPE_SPEECH = 1
    }
}

class AudioFocusRequest(val gain: Int, val listener: AudioManager.OnAudioFocusChangeListener?, val pauseWhenDucked: Boolean) {
    class Builder(private val gain: Int) {
        private var listener: AudioManager.OnAudioFocusChangeListener? = null
        private var pause = false
        fun setAudioAttributes(a: AudioAttributes) = this
        fun setWillPauseWhenDucked(b: Boolean): Builder {
            pause = b
            return this
        }
        fun setAcceptsDelayedFocusGain(b: Boolean) = this
        fun setOnAudioFocusChangeListener(l: AudioManager.OnAudioFocusChangeListener, h: Handler): Builder {
            listener = l
            return this
        }
        fun build() = AudioFocusRequest(gain, listener, pause)
    }
}

class AudioManager {
    fun interface OnAudioFocusChangeListener {
        fun onAudioFocusChange(focusChange: Int)
    }

    var nextResult = AUDIOFOCUS_REQUEST_GRANTED
    var holder: AudioFocusRequest? = null
    var requests = 0
    var abandons = 0

    fun requestAudioFocus(r: AudioFocusRequest): Int {
        requests++
        if (nextResult != AUDIOFOCUS_REQUEST_FAILED) holder = r
        return nextResult
    }

    fun abandonAudioFocusRequest(r: AudioFocusRequest): Int {
        abandons++
        if (holder === r) holder = null
        return AUDIOFOCUS_REQUEST_GRANTED
    }

    /** 검사에서 포커스 변화를 일으킵니다. */
    fun fire(change: Int) {
        holder?.listener?.onAudioFocusChange(change)
    }

    companion object {
        const val AUDIOFOCUS_GAIN = 1
        const val AUDIOFOCUS_LOSS = -1
        const val AUDIOFOCUS_LOSS_TRANSIENT = -2
        const val AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK = -3
        const val AUDIOFOCUS_REQUEST_FAILED = 0
        const val AUDIOFOCUS_REQUEST_GRANTED = 1
        const val AUDIOFOCUS_REQUEST_DELAYED = 2
    }
}

class MediaMetadata(private val values: Map<String, Any>) {
    class Builder {
        private val values = HashMap<String, Any>()
        fun putString(k: String, v: String): Builder {
            values[k] = v
            return this
        }
        fun putLong(k: String, v: Long): Builder {
            values[k] = v
            return this
        }
        fun putBitmap(k: String, v: android.graphics.Bitmap): Builder {
            values[k] = v
            return this
        }
        fun build() = MediaMetadata(HashMap(values))
    }

    fun getString(k: String): String? = values[k] as? String
    fun getLong(k: String): Long = values[k] as? Long ?: 0L

    companion object {
        const val METADATA_KEY_TITLE = "title"
        const val METADATA_KEY_ARTIST = "artist"
        const val METADATA_KEY_ALBUM = "album"
        const val METADATA_KEY_NUM_TRACKS = "tracks"
        const val METADATA_KEY_DURATION = "duration"
        const val METADATA_KEY_MEDIA_ID = "mediaId"
        const val METADATA_KEY_DISPLAY_TITLE = "displayTitle"
        const val METADATA_KEY_DISPLAY_SUBTITLE = "displaySubtitle"
        const val METADATA_KEY_ALBUM_ART = "albumArt"
        const val METADATA_KEY_ALBUM_ART_URI = "albumArtUri"
        const val METADATA_KEY_DISPLAY_ICON_URI = "displayIconUri"
    }
}

class MediaPlayer {
    fun interface OnCompletionListener {
        fun onCompletion(mp: MediaPlayer)
    }

    fun interface OnErrorListener {
        fun onError(mp: MediaPlayer, what: Int, extra: Int): Boolean
    }

    private var completion: OnCompletionListener? = null
    private var startedAt = -1L
    private var released = false
    private val done = Runnable { if (!released) completion?.onCompletion(this) }

    var duration = 0
        private set
    var volume = 1f
        private set
    val currentPosition: Int get() = if (startedAt < 0) 0 else (FakeClock.now - startedAt).toInt().coerceAtMost(duration)

    fun setAudioAttributes(a: AudioAttributes) {}
    fun setDataSource(fd: FileDescriptor, offset: Long, length: Long) {
        duration = length.toInt()
    }
    fun setOnCompletionListener(l: OnCompletionListener?) {
        completion = l
    }
    fun setOnErrorListener(l: OnErrorListener?) {}
    fun prepare() {}
    fun setVolume(l: Float, r: Float) {
        volume = l
    }
    fun start() {
        startedAt = FakeClock.now
        last = this
        active++
        FakeClock.post(done, duration.toLong())
    }
    fun release() {
        if (!released && startedAt >= 0) active--
        released = true
        FakeClock.remove(done)
    }

    companion object {
        var last: MediaPlayer? = null
        /** 지금 소리 내고 있는 재생기 수. 1을 넘으면 문장이 겹친 것입니다. */
        var active = 0
    }
}
