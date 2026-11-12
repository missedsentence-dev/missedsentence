package com.missedsentence.navprompt

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * 내비 안내 음성을 흉내 냅니다.
 * 내비 안내 용도로 오디오 포커스를 잠깐 요청하고, 약 4초 동안 안내음을 낸 뒤 포커스를 돌려줍니다.
 * 글이나 음성 합성 없이 직접 만든 소리만 씁니다.
 */
object Prompt {

    const val DURATION_MS = 4_000L
    private const val SAMPLE_RATE = 22_050

    private val main = Handler(Looper.getMainLooper())
    private var busy = false

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /**
     * 안내음을 냅니다. 포커스를 받았는지와 상태 설명을 [onState]로 알려 줍니다.
     * [exclusive]가 true면 소리를 낮추는 것도 허락하지 않는 요청으로 바꿉니다(다른 소리가 반드시 멈춤).
     */
    fun play(context: Context, exclusive: Boolean = false, onState: (String) -> Unit) {
        if (busy) {
            onState("안내음이 나오는 중이에요")
            return
        }
        val audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // 소리를 낮춰도 된다고 요청합니다. 놓친문장은 낮추는 대신 멈추겠다고 알려 두었으므로 시스템이 멈춤으로 전달합니다.
        val gain = if (exclusive) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        val request = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener({ }, main)
            .build()
        val result = audio.requestAudioFocus(request)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            onState("오디오 포커스를 받지 못했어요")
            return
        }
        busy = true
        onState("안내 음성 포커스를 받았어요. 4초 동안 안내음을 내요")
        val track = runCatching { buildTrack() }.getOrNull()
        runCatching { track?.play() }
        main.postDelayed({
            runCatching { track?.stop() }
            runCatching { track?.release() }
            audio.abandonAudioFocusRequest(request)
            busy = false
            onState("포커스를 돌려줬어요")
        }, DURATION_MS)
    }

    /** 딩동 소리 두 번과 낮은 안내음. 16비트 모노 */
    private fun buildTrack(): AudioTrack {
        val total = (SAMPLE_RATE * DURATION_MS / 1000).toInt()
        val pcm = ShortArray(total)
        fun tone(startMs: Int, lengthMs: Int, hz: Double, gain: Double) {
            val start = SAMPLE_RATE * startMs / 1000
            val n = SAMPLE_RATE * lengthMs / 1000
            for (i in 0 until n) {
                val idx = start + i
                if (idx >= total) break
                val fade = min(1.0, min(i, n - i) / (SAMPLE_RATE * 0.02))
                val v = sin(2 * PI * hz * i / SAMPLE_RATE) * gain * fade
                pcm[idx] = (pcm[idx] + (v * Short.MAX_VALUE).toInt()).coerceIn(-32768, 32767).toShort()
            }
        }
        tone(0, 280, 880.0, 0.5)
        tone(300, 420, 660.0, 0.5)
        // 말소리 길이만큼 이어지는 낮은 음
        var t = 900
        var i = 0
        val notes = doubleArrayOf(392.0, 440.0, 392.0, 330.0)
        while (t < DURATION_MS - 500) {
            tone(t, 300, notes[i % notes.size], 0.28)
            t += 380
            i++
        }
        tone((DURATION_MS - 450).toInt(), 300, 660.0, 0.4)
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        track.write(pcm, 0, pcm.size)
        return track
    }
}
