package com.missedsentence.car.player

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.missedsentence.car.content.Pack
import com.missedsentence.car.content.Sentence
import java.util.Locale

/**
 * 문장 단위 재생기.
 * [index]는 늘 '다음에 처음부터 틀 문장'을 가리킵니다. 문장 중간에 끊으면 번호가 그대로라 그 문장 처음부터 다시 나오고,
 * 문장이 끝난 자리에서 미루면 번호가 넘어가 다음 문장부터 이어집니다.
 * 소리는 앱에 든 녹음, 기기 음성 합성, 소리 없이 시간만 흐르는 자리 표시 순으로 고릅니다.
 * 모든 함수는 메인 스레드에서 부릅니다.
 */
class SentencePlayer(context: Context) : TextToSpeech.OnInitListener {

    enum class Backend(val label: String) {
        FILE("앱에 든 녹음"),
        TTS("기기 음성 합성"),
        SILENT("소리 없이 시간만 흐름")
    }

    interface Listener {
        fun onSentenceStarted(index: Int) {}
        fun onSentenceCompleted(index: Int, fromQueue: Boolean) {}
        /** 다른 앱의 소리 때문에 잠시 멈춤. [midSentence]는 문장 중간이었는지 */
        fun onFocusPause(midSentence: Boolean) {}
        fun onFocusBack() {}
        fun onFocusGone() {}
        fun onPreviousKey() {}
        fun onPackFinished() {}
        fun onPackLooped() {}
        /** 문장 사이에서 미루려 했지만 문장이 약속한 시간 안에 끝나지 않아 끊음 */
        fun onDeferBecameCut() {}
        fun onStateChanged() {}
        /** 차량 미디어 화면에서 묶음을 골라 재생을 요청함. [mediaId]는 묶음 id */
        fun onPlayRequest(mediaId: String) {}
        /** 음성 검색으로 재생을 요청함. 말이 비어 있으면 듣던 것을 이어 재생합니다. */
        fun onSearchRequest(query: String) {}
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    var listener: Listener? = null

    var pack: Pack? = null
        private set

    /** 다음에 처음부터 틀 문장 */
    var index = 0
        private set

    /** 운전자가 재생을 원하고 있는지. 엔진이 붙잡고 있어도 true입니다. */
    var wantPlay = false
        private set

    /** 엔진이 붙잡고 있어 조용한 상태 */
    var engineHeld = false
        private set

    /** 다른 앱(내비 안내 음성 등)에 소리를 잠시 내준 상태 */
    var focusLost = false
        private set

    /** 지금 문장이 소리 나고 있는지 */
    var speaking = false
        private set

    /** 지금 문장이 끝나면 붙잡기로 한 상태(문장 사이 미룸 예약) */
    var deferPending = false
        private set

    /** 지금 붙잡힌 것이 문장 중간에 끊긴 것인지. 풀리면 그 문장 처음부터 다시 나옵니다. */
    var heldMid = false
        private set

    var backend = Backend.SILENT
        private set

    /** 마지막 문장까지 들으면 처음부터 다시 들을지. 어학 묶음과 시연 장면에서 켭니다. */
    var loop = false

    /** 다시 들을 문장을 먼저 트는 중인지 */
    var fromQueue = false
        private set

    val queueSize: Int get() = queue.size + if (fromQueue) 1 else 0

    /**
     * 차량 미디어 화면의 둘째 줄에 보일 글. 쉬는 이유가 있으면 이유를 적습니다.
     * 주행 중에는 앱 화면 대신 차량 미디어 화면이 보이므로 이 줄이 운전자에게 보이는 유일한 설명입니다.
     */
    var statusLine: String = ""
        set(value) {
            if (field == value) return
            field = value
            publishMetadata()
        }

    /** 차량 미디어 화면에 보일 그림 */
    var artwork: Bitmap? = null
        set(value) {
            field = value
            publishMetadata()
        }

    /** 앞뒤 문장과 함께 다시 듣는 중인지 */
    val inRange: Boolean get() = rangeEnd >= 0

    private val queue = ArrayDeque<Int>()
    private var mainNext = 0
    private var rangeEnd = -1
    private var savedIndex = 0
    private var savedFromQueue = false

    private var token = 0
    private var player: MediaPlayer? = null
    private var sentenceStartMs = 0L
    private var sentenceDurMs = 0L
    private var hasFocus = false

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    // 말소리 콘텐츠로 포커스를 요청합니다. 소리를 낮추는 대신 멈추겠다고 알려 두면
    // 내비 안내 음성이 나올 때 시스템이 잠시 포커스를 가져갑니다.
    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attrs)
        .setWillPauseWhenDucked(true)
        .setAcceptsDelayedFocusGain(true)
        .setOnAudioFocusChangeListener({ change -> onFocusChange(change) }, main)
        .build()

    private val session: MediaSession = MediaSession(appContext, "MissedSentence")

    val sessionToken: MediaSession.Token get() = session.sessionToken

    private val gapTask = Runnable { maybeStart(false) }
    private val deferDeadline = Runnable {
        // 예상보다 문장이 길어지면 약속한 시간에 끊습니다.
        if (deferPending && speaking) {
            deferPending = false
            engineHeld = true
            heldMid = true
            stopSound()
            listener?.onDeferBecameCut()
            changed()
        }
    }

    init {
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = play()
            override fun onPause() = pause()
            override fun onStop() = pause()
            override fun onSkipToNext() = next()
            override fun onSkipToPrevious() {
                // 핸들의 '이전' 버튼은 한 문장 다시 듣기입니다.
                previous()
                listener?.onPreviousKey()
            }
            override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                val l = listener
                if (mediaId.isNullOrBlank() || l == null) play() else l.onPlayRequest(mediaId)
            }
            override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                val l = listener
                if (l == null) play() else l.onSearchRequest(query.orEmpty())
            }
        }, main)
        runCatching { session.isActive = true }
        tts = try {
            TextToSpeech(appContext, this)
        } catch (t: Throwable) {
            null
        }
        publishState()
    }

    override fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) return
        runCatching { engine.setAudioAttributes(attrs) }
        runCatching {
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    main.post { if (utteranceId == "s$token") onSentenceDone(token) }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    main.post { if (utteranceId == "s$token") onSentenceDone(token) }
                }
            })
        }
        ttsReady = true
    }

    // ------------------------------------------------------------------ 운전자가 하는 일

    val size: Int get() = pack?.sentences?.size ?: 0

    /** 이어 들을 자리. 다시 들을 문장을 먼저 트는 중에는 원래 자리를 돌려줍니다. */
    val bookmark: Int get() = if (fromQueue || inRange) (if (inRange) savedIndex else mainNext) else index

    fun load(newPack: Pack, bookmarkIndex: Int) {
        stopSound()
        main.removeCallbacks(gapTask)
        pack = newPack
        queue.clear()
        fromQueue = false
        rangeEnd = -1
        deferPending = false
        index = bookmarkIndex.coerceIn(0, maxOf(0, newPack.sentences.size - 1))
        mainNext = index
        publishMetadata()
        changed()
    }

    fun play() {
        val p = pack ?: return
        if (p.isEmpty || wantPlay) return
        if (!requestFocus()) {
            changed()
            return
        }
        wantPlay = true
        maybeStart(false)
        changed()
    }

    fun pause() {
        if (!wantPlay) return
        wantPlay = false
        deferPending = false
        main.removeCallbacks(gapTask)
        stopSound()
        if (inRange) endRange()
        abandonFocus()
        changed()
    }

    fun toggle() = if (wantPlay) pause() else play()

    /** 한 문장 다시. 지금 문장을 2초 넘게 들었으면 지금 문장 처음부터, 아니면 앞 문장부터 */
    fun previous() {
        if (pack == null) return
        val heard = if (speaking) SystemClock.elapsedRealtime() - sentenceStartMs else 0L
        val target = if (fromQueue || (speaking && heard > RESTART_MS)) index else index - 1
        jump(target)
    }

    fun next() {
        if (pack == null) return
        jump(index + 1)
    }

    private fun jump(target: Int) {
        if (inRange) endRange()
        main.removeCallbacks(gapTask)
        stopSound()
        deferPending = false
        index = target.coerceIn(0, maxOf(0, size - 1))
        if (!fromQueue) mainNext = index
        maybeStart(false)
        changed()
    }

    /** 다시 들을 문장을 원래 자리보다 먼저 틉니다. */
    fun setQueue(indices: List<Int>) {
        queue.clear()
        indices.filter { it in 0 until size }.forEach { queue.addLast(it) }
        if (!speaking && !fromQueue && !inRange && queue.isNotEmpty()) {
            mainNext = index
            index = queue.removeFirst()
            fromQueue = true
        }
        changed()
    }

    /** 이야기 콘텐츠: 다시 들을 문장을 앞뒤 한 문장과 함께 듣고 원래 자리로 돌아옵니다. */
    fun playRange(from: Int, to: Int) {
        val p = pack ?: return
        if (p.isEmpty) return
        main.removeCallbacks(gapTask)
        stopSound()
        if (!inRange) {
            savedIndex = if (fromQueue) mainNext else index
            savedFromQueue = false
        }
        fromQueue = false
        rangeEnd = to.coerceIn(0, size - 1)
        index = from.coerceIn(0, rangeEnd)
        if (!wantPlay) {
            if (!requestFocus()) {
                endRange()
                changed()
                return
            }
            wantPlay = true
        }
        maybeStart(false)
        changed()
    }

    private fun endRange() {
        rangeEnd = -1
        index = savedIndex
        mainNext = savedIndex
        fromQueue = savedFromQueue
    }

    // ------------------------------------------------------------------ 엔진이 하는 일

    /**
     * 문장이 끝나는 자리에서 미룹니다. [graceMs] 안에 지금 문장이 끝나지 않으면 바로 끊습니다.
     * 문장 사이에서 미뤘으면 true, 문장 중간에 끊었으면 false
     */
    fun engineDefer(graceMs: Long): Boolean {
        if (engineHeld) return true
        if (!wantPlay || focusLost || !speaking) {
            engineHeld = true
            heldMid = false
            main.removeCallbacks(gapTask)
            changed()
            return true
        }
        if (remainingMs() <= graceMs) {
            deferPending = true
            main.removeCallbacks(deferDeadline)
            main.postDelayed(deferDeadline, graceMs + 400L)
            changed()
            return true
        }
        engineCut()
        return false
    }

    /** 바로 끊습니다. 문장 중간이었으면 true */
    fun engineCut(): Boolean {
        val mid = speaking && wantPlay
        if (mid) heldMid = true else if (!engineHeld) heldMid = false
        deferPending = false
        engineHeld = true
        main.removeCallbacks(gapTask)
        stopSound()
        changed()
        return mid
    }

    /** 붙잡은 것을 풉니다. 소리는 1초에 걸쳐 커집니다. */
    fun engineResume() {
        main.removeCallbacks(deferDeadline)
        deferPending = false
        if (!engineHeld) {
            changed()
            return
        }
        engineHeld = false
        maybeStart(true)
        changed()
    }

    // ------------------------------------------------------------------ 진행 상태

    /** 지금 문장이 끝나기까지 남은 시간 */
    fun remainingMs(): Long {
        if (!speaking) return 0L
        val mp = player
        if (mp != null) {
            val left = runCatching { mp.duration - mp.currentPosition }.getOrDefault(0)
            return left.toLong().coerceAtLeast(0L)
        }
        return (sentenceDurMs - (SystemClock.elapsedRealtime() - sentenceStartMs)).coerceAtLeast(0L)
    }

    /** 묶음 전체에서 지금 어디쯤인지 0~1 */
    fun progress(): Float {
        val n = size
        if (n == 0) return 0f
        var within = 0f
        if (speaking && !fromQueue && !inRange && sentenceDurMs > 0) {
            within = (1f - remainingMs().toFloat() / sentenceDurMs).coerceIn(0f, 1f)
        }
        return ((bookmark + within) / n).coerceIn(0f, 1f)
    }

    /** 지금 소리 나고 있는 문장 번호. 조용하면 null */
    fun audibleIndex(): Int? = if (speaking) index else null

    fun release() {
        stopSound()
        main.removeCallbacksAndMessages(null)
        abandonFocus()
        runCatching { session.isActive = false }
        runCatching { session.release() }
        runCatching { tts?.shutdown() }
    }

    // ------------------------------------------------------------------ 문장 재생

    private fun maybeStart(fadeIn: Boolean) {
        if (!wantPlay || engineHeld || focusLost || speaking) return
        startSentence(fadeIn)
    }

    private fun startSentence(fadeIn: Boolean) {
        val p = pack ?: return
        val s = p.sentences.getOrNull(index)
        if (s == null) {
            finishPack()
            return
        }
        val my = ++token
        speaking = true
        sentenceStartMs = SystemClock.elapsedRealtime()
        sentenceDurMs = estimateMs(s, p.language)
        backend = when {
            startFile(p.open(appContext, s), my, fadeIn) -> Backend.FILE
            startTts(s, p.language, my) -> Backend.TTS
            else -> {
                main.postDelayed({ onSentenceDone(my) }, sentenceDurMs)
                Backend.SILENT
            }
        }
        listener?.onSentenceStarted(index)
        changed()
    }

    private fun startFile(afd: AssetFileDescriptor?, my: Int, fadeIn: Boolean): Boolean {
        if (afd == null) return false
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(attrs)
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            mp.setOnCompletionListener { onSentenceDone(my) }
            mp.setOnErrorListener { _, _, _ ->
                onSentenceDone(my)
                true
            }
            mp.prepare()
            sentenceDurMs = mp.duration.toLong().coerceAtLeast(300L)
            val v = if (fadeIn) 0f else 1f
            mp.setVolume(v, v)
            mp.start()
            player = mp
            if (fadeIn) fade(my)
            true
        } catch (t: Throwable) {
            runCatching { mp.release() }
            false
        } finally {
            runCatching { afd.close() }
        }
    }

    private fun fade(my: Int) {
        val start = SystemClock.elapsedRealtime()
        val step = object : Runnable {
            override fun run() {
                if (token != my) return
                val f = ((SystemClock.elapsedRealtime() - start) / FADE_MS.toFloat()).coerceIn(0f, 1f)
                runCatching { player?.setVolume(f, f) }
                if (f < 1f) main.postDelayed(this, 50L)
            }
        }
        main.post(step)
    }

    private fun startTts(s: Sentence, language: String, my: Int): Boolean {
        val engine = tts ?: return false
        if (!ttsReady || s.text.isBlank()) return false
        return try {
            val locale = if (language.startsWith("en")) Locale.US else Locale.KOREAN
            val supported = engine.setLanguage(locale)
            if (supported == TextToSpeech.LANG_MISSING_DATA || supported == TextToSpeech.LANG_NOT_SUPPORTED) {
                false
            } else {
                engine.speak(s.text, TextToSpeech.QUEUE_FLUSH, null, "s$my") == TextToSpeech.SUCCESS
            }
        } catch (t: Throwable) {
            false
        }
    }

    private fun onSentenceDone(my: Int) {
        if (my != token || !speaking) return
        speaking = false
        releasePlayer()
        val done = index
        val wasQueue = fromQueue
        val wasRange = inRange
        if (wasRange) {
            if (done >= rangeEnd) {
                // 앞뒤 문장까지 다 들었으면 원래 자리로 돌아가 멈춥니다.
                endRange()
                wantPlay = false
                abandonFocus()
                listener?.onSentenceCompleted(done, false)
                changed()
                return
            }
            index = done + 1
        } else {
            if (!wasQueue) mainNext = done + 1
            val q = queue.removeFirstOrNull()
            if (q != null) {
                index = q
                fromQueue = true
            } else {
                index = mainNext
                fromQueue = false
            }
        }
        listener?.onSentenceCompleted(done, wasQueue)
        var gap = GAP_MS
        if (index >= size) {
            if (!loop) {
                finishPack()
                return
            }
            // 마지막 문장 다음에는 조금 더 쉬고 처음부터 다시 듣습니다.
            index = 0
            mainNext = 0
            fromQueue = false
            gap = LOOP_GAP_MS
            listener?.onPackLooped()
        }
        if (deferPending) {
            // 문장이 끝난 자리에서 미룹니다. 포커스는 그대로 쥐고 있습니다.
            main.removeCallbacks(deferDeadline)
            deferPending = false
            engineHeld = true
            heldMid = false
            changed()
            return
        }
        changed()
        main.postDelayed(gapTask, gap)
    }

    private fun finishPack() {
        speaking = false
        wantPlay = false
        deferPending = false
        fromQueue = false
        index = 0
        mainNext = 0
        abandonFocus()
        listener?.onPackFinished()
        changed()
    }

    private fun stopSound() {
        token++
        speaking = false
        releasePlayer()
        runCatching { tts?.stop() }
    }

    private fun releasePlayer() {
        val mp = player ?: return
        player = null
        runCatching { mp.setOnCompletionListener(null) }
        runCatching { mp.release() }
    }

    private fun estimateMs(s: Sentence, language: String): Long {
        if (s.ms > 0) return s.ms
        val letters = s.text.count { !it.isWhitespace() }
        val per = if (language.startsWith("en")) 62L else 175L
        return (letters * per).coerceIn(1_200L, 15_000L)
    }

    // ------------------------------------------------------------------ 오디오 포커스

    private fun requestFocus(): Boolean {
        if (hasFocus) return true
        return when (runCatching { audio.requestAudioFocus(focusRequest) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                hasFocus = true
                focusLost = false
                true
            }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                // 통화처럼 지금은 낼 수 없는 상황. 끝나면 시작합니다.
                hasFocus = true
                focusLost = true
                true
            }
            else -> false
        }
    }

    private fun abandonFocus() {
        if (!hasFocus) return
        hasFocus = false
        focusLost = false
        runCatching { audio.abandonAudioFocusRequest(focusRequest) }
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (focusLost) {
                    focusLost = false
                    listener?.onFocusBack()
                    // 안내가 끝나면 그 문장 처음부터
                    maybeStart(false)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (!focusLost) {
                    focusLost = true
                    val mid = speaking
                    main.removeCallbacks(gapTask)
                    stopSound()
                    if (deferPending) {
                        main.removeCallbacks(deferDeadline)
                        deferPending = false
                        engineHeld = true
                        heldMid = mid
                    }
                    listener?.onFocusPause(mid)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // 다른 앱이 재생을 시작함. 운전자가 다시 누를 때까지 멈추고, 포커스 요청도 거둡니다.
                runCatching { audio.abandonAudioFocusRequest(focusRequest) }
                hasFocus = false
                focusLost = false
                wantPlay = false
                deferPending = false
                main.removeCallbacks(gapTask)
                stopSound()
                if (inRange) endRange()
                listener?.onFocusGone()
            }
        }
        changed()
    }

    // ------------------------------------------------------------------ MediaSession

    private fun changed() {
        publishState()
        listener?.onStateChanged()
    }

    private fun publishState() {
        val state = when {
            wantPlay && speaking -> PlaybackState.STATE_PLAYING
            wantPlay -> PlaybackState.STATE_PLAYING   // 엔진이 붙잡고 있어도 운전자에게는 재생 중입니다.
            else -> PlaybackState.STATE_PAUSED
        }
        // 진행 막대는 시간이 아니라 문장 수로 채웁니다. 문장 하나를 1초로 치고, 화면이 스스로 흘려보내지 않게 속도는 0으로 둡니다.
        runCatching {
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_STOP or
                            PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PLAY_FROM_SEARCH
                    )
                    .setState(state, bookmark * UNIT_MS, 0f)
                    .build()
            )
        }
    }

    private fun publishMetadata() {
        val p = pack ?: return
        val line = statusLine.ifBlank { APP_NAME }
        runCatching {
            val b = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, p.id)
                .putString(MediaMetadata.METADATA_KEY_TITLE, p.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, line)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, APP_NAME)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, p.title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, line)
                .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, ART_URI)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI, ART_URI)
                .putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, p.sentences.size.toLong())
                .putLong(MediaMetadata.METADATA_KEY_DURATION, p.sentences.size * UNIT_MS)
            artwork?.let { b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
            session.setMetadata(b.build())
        }
    }

    companion object {
        const val APP_NAME = "놓친문장"
        /** 차량 미디어 화면이 읽어 가는 표지 그림 주소 */
        const val ART_URI = "android.resource://com.missedsentence.car/drawable/art_cover"
        /** 진행 막대에서 문장 하나가 차지하는 길이 */
        const val UNIT_MS = 1_000L
        private const val GAP_MS = 450L
        private const val LOOP_GAP_MS = 1_500L
        private const val FADE_MS = 1_000L
        private const val RESTART_MS = 2_000L
    }
}
