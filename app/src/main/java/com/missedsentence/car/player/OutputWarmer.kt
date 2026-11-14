package com.missedsentence.car.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * 시연 모드에서 소리 출력이 쉬지 않도록 무음을 계속 내보냅니다.
 *
 * 안드로이드는 몇 초 동안 아무 소리가 없으면 소리 출력을 쉬게 합니다. 에뮬레이터에서는 쉬었다가 다시 시작한
 * 처음 몇 초의 소리가 조각나서, 멈췄다가 다시 나오는 문장이 깨져 들립니다. 2026년 11월 12일 녹화를 앱에 든
 * 원본 음성과 문장마다 맞춰 보아 확인했습니다. 무음이 흐르는 동안에는 출력이 쉬지 않습니다.
 * 실제 차량에서는 필요 없는 대책이라 시연 모드에서만 켭니다. 소리 내용은 0뿐이라 다른 소리에 영향이 없습니다.
 */
class OutputWarmer {

    private var track: AudioTrack? = null
    private var failed = false

    val active: Boolean get() = track != null

    fun set(on: Boolean) {
        if (on == (track != null)) return
        if (on) start() else stop()
    }

    private fun start() {
        if (failed) return
        track = runCatching {
            // 0.5초 길이의 무음을 끝없이 되풀이합니다.
            val frames = SAMPLE_RATE / 2
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(frames * 2)
                .build()
            t.write(ShortArray(frames), 0, frames)
            t.setLoopPoints(0, frames, -1)
            t.play()
            t
        }.getOrNull()
        if (track == null) failed = true
    }

    private fun stop() {
        val t = track ?: return
        track = null
        runCatching { t.pause() }
        runCatching { t.release() }
    }

    private companion object {
        const val SAMPLE_RATE = 44100
    }
}
