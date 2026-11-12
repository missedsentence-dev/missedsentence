package android.speech.tts

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.FakeClock
import java.util.Locale

abstract class UtteranceProgressListener {
    abstract fun onStart(utteranceId: String?)
    abstract fun onDone(utteranceId: String?)
    abstract fun onError(utteranceId: String?)
}

/** 검사용 음성 합성. [available]을 켜 두면 글자 수에 [msPerChar]를 곱한 시간 뒤에 끝났다고 알립니다. */
class TextToSpeech(context: Context, private val init: OnInitListener) {
    fun interface OnInitListener {
        fun onInit(status: Int)
    }

    private var listener: UtteranceProgressListener? = null
    private var pending: Runnable? = null

    init {
        if (available) FakeClock.post({ init.onInit(SUCCESS) }, 0L)
    }

    fun setAudioAttributes(a: AudioAttributes): Int = SUCCESS
    fun setOnUtteranceProgressListener(l: UtteranceProgressListener): Int {
        listener = l
        return SUCCESS
    }
    fun setLanguage(l: Locale): Int = 0
    fun speak(text: CharSequence, mode: Int, params: Bundle?, id: String): Int {
        stop()
        spoken.add(text.toString())
        val r = Runnable { listener?.onDone(id) }
        pending = r
        FakeClock.post(r, text.length * msPerChar)
        return SUCCESS
    }
    fun stop(): Int {
        pending?.let { FakeClock.remove(it) }
        pending = null
        return SUCCESS
    }
    fun shutdown() {}

    companion object {
        const val SUCCESS = 0
        const val ERROR = -1
        const val QUEUE_FLUSH = 0
        const val LANG_MISSING_DATA = -1
        const val LANG_NOT_SUPPORTED = -2
        var available = false
        var msPerChar = 100L
        val spoken = ArrayList<String>()
    }
}
