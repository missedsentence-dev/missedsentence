import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.FakeClock
import android.speech.tts.TextToSpeech
import com.missedsentence.car.content.Pack
import com.missedsentence.car.content.Sentence
import com.missedsentence.car.player.SentencePlayer
import kotlin.system.exitProcess

/**
 * 실제 SentencePlayer.kt 를 가짜 Android 클래스(tools/playertest/fake)와 함께 컴파일해
 * 가짜 시계로 시간을 흘려보내며 문장 단위 재생이 약속대로 움직이는지 봅니다.
 * 소리 자체는 내지 않고, 어느 문장이 언제 시작하고 멈추는지만 확인합니다.
 */
private var passed = 0
private var failed = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) {
        passed++
    } else {
        failed++
        println("   실패: $name ${if (detail.isNotEmpty()) "[$detail]" else ""}")
    }
}

private class Rig(durations: List<Long>, withAudio: Boolean = true, tts: Boolean = false, texts: List<String>? = null) {
    val context = Context()
    val events = ArrayList<String>()
    val player: SentencePlayer
    val pack: Pack

    init {
        FakeClock.reset()
        MediaPlayer.active = 0
        MediaPlayer.last = null
        TextToSpeech.available = tts
        TextToSpeech.spoken.clear()
        val sentences = durations.mapIndexed { i, ms ->
            val name = "s${i + 1}.ogg"
            if (withAudio) context.assets.durations["packs/test/$name"] = ms
            Sentence(i, texts?.get(i) ?: "문장 ${i + 1}", if (withAudio) name else null, if (withAudio) ms else 0L)
        }
        pack = Pack("test", "검사", Pack.Kind.STORY, "ko", "", "", 1, sentences, "packs/test")
        player = SentencePlayer(context)
        player.listener = object : SentencePlayer.Listener {
            override fun onSentenceStarted(index: Int) { events.add("S$index") }
            override fun onSentenceCompleted(index: Int, fromQueue: Boolean) { events.add(if (fromQueue) "Q$index" else "E$index") }
            override fun onFocusPause(midSentence: Boolean) { events.add(if (midSentence) "FP-mid" else "FP") }
            override fun onFocusBack() { events.add("FB") }
            override fun onFocusGone() { events.add("FG") }
            override fun onPreviousKey() { events.add("PREV") }
            override fun onPackFinished() { events.add("FIN") }
            override fun onPackLooped() { events.add("LOOP") }
            override fun onDeferBecameCut() { events.add("D2C") }
            override fun onPlayRequest(mediaId: String) { events.add("REQ:$mediaId") }
            override fun onSearchRequest(query: String) { events.add("FIND:$query") }
        }
        FakeClock.advance(1)   // 음성 합성 준비 알림을 받습니다.
        player.load(pack, 0)
    }

    fun go(ms: Long) = FakeClock.advance(ms)
    fun log() = events.joinToString(" ")
}

private fun sequence() {
    println("1. 문장을 차례로 재생")
    val r = Rig(listOf(2000, 2000, 2000))
    r.player.play()
    r.go(100)
    check("첫 문장이 소리 남", r.player.speaking && r.player.index == 0 && r.player.backend == SentencePlayer.Backend.FILE)
    r.go(2000)
    check("문장이 끝나면 번호가 넘어가고 잠깐 쉼", !r.player.speaking && r.player.index == 1, r.log())
    r.go(500)
    check("0.45초 뒤 다음 문장", r.player.speaking && r.player.index == 1)
    r.go(6000)
    check("마지막 문장까지 들으면 멈춤", !r.player.wantPlay && r.player.index == 0 && r.log().endsWith("E2 FIN"), r.log())
    check("문장이 겹쳐 나온 적 없음", MediaPlayer.active == 0)
    check("포커스를 한 번 받고 한 번 돌려줌", r.context.audio.requests == 1 && r.context.audio.abandons == 1)
}

private fun deferAtBoundary() {
    println("2. 문장이 끝나는 자리에서 미룸")
    val r = Rig(listOf(3000, 3000, 3000))
    r.player.play()
    r.go(1000)
    check("남은 2초가 4초 안이라 문장 사이 미룸", r.player.engineDefer(4000))
    check("예약만 하고 문장은 계속", r.player.speaking && r.player.deferPending && !r.player.engineHeld)
    r.go(2100)
    check("문장이 끝나자 붙잡음", !r.player.speaking && r.player.engineHeld && r.player.index == 1 && !r.player.heldMid)
    r.go(10_000)
    check("붙잡는 동안 조용함", !r.player.speaking && r.log() == "S0 E0", r.log())
    check("붙잡는 동안 포커스를 돌려주지 않음", r.context.audio.abandons == 0 && r.context.audio.holder != null)
    r.player.engineResume()
    check("풀면 다음 문장부터", r.player.speaking && r.player.index == 1)
    check("처음에는 소리가 0", MediaPlayer.last?.volume == 0f)
    r.go(500)
    check("0.5초 뒤 절반쯤", (MediaPlayer.last?.volume ?: 0f) in 0.4f..0.6f, "${MediaPlayer.last?.volume}")
    r.go(600)
    check("1초 뒤 원래 크기", MediaPlayer.last?.volume == 1f)

    val g = Rig(listOf(2000, 2000))
    g.player.play()
    g.go(2200)   // 문장 사이의 쉼
    check("문장 사이에서 받은 미룸은 바로 붙잡음", g.player.engineDefer(4000) && g.player.engineHeld && !g.player.speaking)
    g.go(3000)
    check("쉼 뒤에도 다음 문장이 나오지 않음", !g.player.speaking && g.log() == "S0 E0", g.log())
}

private fun cutAndRestart() {
    println("3. 문장이 길면 끊고, 풀리면 그 문장 처음부터")
    val r = Rig(listOf(8000, 2000))
    r.player.play()
    r.go(1000)
    check("남은 7초가 4초를 넘어 끊음", !r.player.engineDefer(4000))
    check("끊긴 상태", !r.player.speaking && r.player.engineHeld && r.player.index == 0 && r.player.heldMid)
    r.go(5000)
    r.player.engineResume()
    r.go(100)
    check("같은 문장이 처음부터", r.player.speaking && r.player.index == 0 && r.player.remainingMs() in 7800L..8000L, "${r.player.remainingMs()}")
    check("끝난 문장은 아직 없음", r.log() == "S0 S0", r.log())
}

private fun waitingResumeThenCut() {
    println("4. 신호 대기 중 다시 재생, 출발 순간 끊음")
    val r = Rig(listOf(3000, 3000, 3000))
    r.player.play()
    r.go(1500)
    r.player.engineDefer(4000)
    r.go(2000)
    r.player.engineResume()          // 서 있는 동안 다시 재생
    r.go(1200)
    check("대기 중 둘째 문장 재생", r.player.speaking && r.player.index == 1)
    check("출발 순간 끊으면 문장 중간", r.player.engineCut() && r.player.heldMid)
    r.go(9000)
    r.player.engineResume()
    r.go(100)
    check("회전 뒤 둘째 문장 처음부터", r.player.speaking && r.player.index == 1 && r.log() == "S0 E0 S1 S1", r.log())
    check("문장이 겹치지 않음", MediaPlayer.active == 1, "${MediaPlayer.active}")
}

private fun audioFocus() {
    println("5. 안내 음성: 포커스를 잠시 잃으면 멈추고, 돌아오면 그 문장 처음부터")
    val r = Rig(listOf(4000, 4000))
    r.player.play()
    r.go(1500)
    r.context.audio.fire(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
    check("소리를 낮추지 않고 멈춤", !r.player.speaking && r.player.focusLost && r.player.wantPlay && r.log().endsWith("FP-mid"), r.log())
    r.go(4000)
    check("안내가 이어지는 동안 조용함", !r.player.speaking)
    r.context.audio.fire(AudioManager.AUDIOFOCUS_GAIN)
    r.go(100)
    check("돌아오면 같은 문장 처음부터", r.player.speaking && r.player.index == 0 && r.player.remainingMs() > 3800, r.log())

    r.player.engineCut()
    r.context.audio.fire(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
    r.context.audio.fire(AudioManager.AUDIOFOCUS_GAIN)
    r.go(1000)
    check("엔진이 붙잡는 중에는 포커스가 돌아와도 조용함", !r.player.speaking && r.player.engineHeld)
    r.player.engineResume()
    check("엔진이 풀면 재생", r.player.speaking)

    r.context.audio.fire(AudioManager.AUDIOFOCUS_LOSS)
    check("다른 앱이 재생을 시작하면 멈추고 기다리지 않음", !r.player.wantPlay && !r.player.speaking && r.log().endsWith("FG"), r.log())

    val d = Rig(listOf(3000, 3000))
    d.player.play()
    d.go(1000)
    d.player.engineDefer(4000)
    d.context.audio.fire(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
    d.context.audio.fire(AudioManager.AUDIOFOCUS_GAIN)
    d.go(5000)
    check("미룸을 예약한 문장이 안내 음성에 끊기면 그대로 붙잡음", !d.player.speaking && d.player.engineHeld && d.player.index == 0 && d.player.heldMid)

    val f = Rig(listOf(3000))
    f.context.audio.nextResult = AudioManager.AUDIOFOCUS_REQUEST_FAILED
    f.player.play()
    check("포커스를 못 받으면 시작하지 않음", !f.player.wantPlay && !f.player.speaking)
    f.context.audio.nextResult = AudioManager.AUDIOFOCUS_REQUEST_DELAYED
    f.player.play()
    check("나중에 주겠다고 하면 기다림", f.player.wantPlay && !f.player.speaking && f.player.focusLost)
    f.context.audio.fire(AudioManager.AUDIOFOCUS_GAIN)
    check("포커스가 오면 시작", f.player.speaking)
}

private fun previousKey() {
    println("6. 핸들 이전 버튼은 한 문장 다시")
    val r = Rig(listOf(4000, 4000, 4000))
    r.player.play()
    r.go(4000 + 450 + 2500)
    val session = MediaSession.last!!
    session.callback!!.onSkipToPrevious()
    r.go(50)
    check("2초 넘게 들은 문장은 그 문장 처음부터", r.player.speaking && r.player.index == 1 && r.player.remainingMs() > 3800 && r.events.contains("PREV"), r.log())
    r.go(800)
    session.callback!!.onSkipToPrevious()
    r.go(50)
    check("막 시작한 문장에서는 앞 문장으로", r.player.speaking && r.player.index == 0)
    session.callback!!.onPause()
    check("멈춤 키", !r.player.wantPlay && !r.player.speaking)
    session.callback!!.onPlay()
    check("재생 키는 그 문장 처음부터", r.player.speaking && r.player.index == 0)
    r.player.engineCut()
    session.callback!!.onSkipToPrevious()
    r.go(1000)
    check("붙잡힌 중에 누르면 자리만 옮기고 조용함", !r.player.speaking && r.player.index == 0 && r.player.engineHeld)
    check("재생 상태는 재생 중으로 알림", session.state?.state == android.media.session.PlaybackState.STATE_PLAYING && session.state?.speed == 0f)
}

private fun relistenQueue() {
    println("7. 다시 들을 문장부터 이어 듣기")
    val r = Rig(listOf(1000, 1000, 1000, 1000, 1000))
    r.player.load(r.pack, 3)
    r.player.setQueue(listOf(1))
    check("이어 들을 자리는 그대로", r.player.bookmark == 3 && r.player.fromQueue && r.player.index == 1)
    r.player.play()
    r.go(1000 + 450 + 100)
    check("남겨 둔 문장을 먼저 듣고 원래 자리로", r.log() == "S1 Q1 S3" && !r.player.fromQueue, r.log())

    val m = Rig(listOf(1000, 1000, 1000, 1000))
    m.player.play()
    m.go(300)
    m.player.setQueue(listOf(3))
    m.go(700 + 450 + 1000 + 450 + 100)
    check("재생 중에 넣으면 지금 문장 다음에 듣고 돌아옴", m.log() == "S0 E0 S3 Q3 S1", m.log())
}

private fun rangeListen() {
    println("8. 앞뒤 문장과 함께 다시 듣기")
    val r = Rig(listOf(1000, 1000, 1000, 1000, 1000, 1000))
    r.player.load(r.pack, 5)
    r.player.playRange(1, 3)
    r.go(100)
    check("앞 문장부터 시작", r.player.speaking && r.player.index == 1 && r.player.inRange)
    r.go(6000)
    check("세 문장을 듣고 멈춘 뒤 원래 자리", r.log() == "S1 E1 S2 E2 S3 E3" && !r.player.wantPlay && r.player.index == 5 && !r.player.inRange, r.log())
    r.player.playRange(-1, 1)
    r.go(100)
    check("첫 문장 앞은 없으므로 첫 문장부터", r.player.index == 0 && r.player.speaking)
    r.player.pause()
    check("도중에 멈추면 원래 자리", r.player.index == 5 && !r.player.inRange)
}

private fun loopAndTextOnly() {
    println("9. 처음부터 다시 듣기, 녹음이 없는 묶음")
    val r = Rig(listOf(1000, 1000))
    r.player.loop = true
    r.player.play()
    r.go(1000 + 450 + 1000 + 1500 + 100)
    check("마지막 문장 뒤 처음부터 다시", r.log() == "S0 E0 S1 E1 LOOP S0" && r.player.wantPlay, r.log())

    val t = Rig(listOf(0, 0, 0), withAudio = false)
    t.player.play()
    t.go(100)
    check("녹음도 음성 합성도 없으면 소리 없이 시간만", t.player.speaking && t.player.backend == SentencePlayer.Backend.SILENT)
    t.go(1200 + 450 + 100)
    check("예상 시간이 지나면 다음 문장", t.player.index == 1 && t.player.speaking, t.log())

    val s = Rig(listOf(0, 0), withAudio = false, tts = true, texts = listOf("짧은 문장", "둘째 문장"))
    s.player.play()
    s.go(100)
    check("녹음이 없고 음성 합성이 있으면 음성 합성", s.player.backend == SentencePlayer.Backend.TTS && TextToSpeech.spoken == listOf("짧은 문장"))
    s.go(500 + 450 + 100)
    check("음성 합성이 끝났다고 알리면 다음 문장", s.player.index == 1 && TextToSpeech.spoken.size == 2, s.log())
}

private fun deferDeadline() {
    println("10. 예상보다 문장이 길면 약속한 시간에 끊음")
    // 음성 합성은 남은 시간을 글자 수로 어림합니다. 실제가 더 길 때를 봅니다.
    TextToSpeech.msPerChar = 1000L
    val r = Rig(listOf(0, 0), withAudio = false, tts = true, texts = listOf("열 글자짜리 문장임", "둘째"))
    r.player.play()
    r.go(200)
    check("어림으로는 곧 끝나므로 미룸 예약", r.player.engineDefer(4000) && r.player.deferPending)
    r.go(4500)
    check("4초가 지나도 안 끝나면 끊음", !r.player.speaking && r.player.engineHeld && r.player.heldMid && r.log().endsWith("D2C"), r.log())
    r.player.engineResume()
    check("풀리면 그 문장 처음부터", r.player.speaking && r.player.index == 0)
    TextToSpeech.msPerChar = 100L
}

private fun pauseWhileHeld() {
    println("11. 붙잡힌 동안의 멈춤과 재생")
    val r = Rig(listOf(3000, 3000))
    r.player.play()
    r.go(500)
    r.player.engineCut()
    r.player.pause()
    r.player.play()
    r.go(2000)
    check("운전자가 다시 눌러도 엔진이 풀 때까지 조용함", r.player.wantPlay && !r.player.speaking)
    r.player.engineResume()
    check("엔진이 풀면 재생", r.player.speaking && r.player.index == 0)
    r.player.pause()
    r.player.engineCut()
    r.player.engineResume()
    check("운전자가 멈춘 상태면 엔진이 풀어도 재생하지 않음", !r.player.speaking && !r.player.wantPlay)
}

private fun carMediaScreen() {
    println("12. 차량 미디어 화면에 알리는 내용")
    val r = Rig(listOf(1000, 1000, 1000, 1000))
    val session = MediaSession.last!!
    fun text(key: String) = session.lastMetadata?.getString(key)
    check(
        "제목은 묶음 이름, 둘째 줄은 기본으로 앱 이름",
        text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) == "검사" && text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) == "놓친문장"
    )
    r.player.statusLine = "좌회전이 끝나면 이어서 들려줘요"
    check(
        "쉬는 이유를 넣으면 둘째 줄이 바뀜",
        text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) == "좌회전이 끝나면 이어서 들려줘요" &&
            text(MediaMetadata.METADATA_KEY_ARTIST) == "좌회전이 끝나면 이어서 들려줘요"
    )
    check("진행 막대 길이는 문장 수", session.lastMetadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) == 4000L)
    r.player.play()
    r.go(1500)
    check("진행 위치는 문장 번호이고 스스로 흐르지 않음", session.state?.position == 1000L && session.state?.speed == 0f, "${session.state?.position}")
    val actions = session.state?.actions ?: 0L
    check(
        "묶음을 골라 재생하는 요청과 음성 검색 재생을 받는다고 알림",
        actions and PlaybackState.ACTION_PLAY_FROM_MEDIA_ID != 0L && actions and PlaybackState.ACTION_PLAY_FROM_SEARCH != 0L
    )
    r.player.pause()
    session.callback!!.onPlayFromMediaId("other", null)
    session.callback!!.onPlayFromSearch("영어", null)
    check("묶음 고르기와 음성 검색 요청을 앱에 넘김", r.events.contains("REQ:other") && r.events.contains("FIND:영어"), r.log())
    session.callback!!.onPlayFromMediaId(null, null)
    check("묶음 이름 없이 오면 듣던 것을 재생", r.player.wantPlay)
    val before = r.context.audio.abandons
    r.context.audio.fire(AudioManager.AUDIOFOCUS_LOSS)
    check("다른 앱이 재생을 시작하면 멈추고 포커스 요청을 거둠", !r.player.wantPlay && r.context.audio.abandons == before + 1)
}

fun main() {
    sequence()
    deferAtBoundary()
    cutAndRestart()
    waitingResumeThenCut()
    audioFocus()
    previousKey()
    relistenQueue()
    rangeListen()
    loopAndTextOnly()
    deferDeadline()
    pauseWhileHeld()
    carMediaScreen()
    println("재생기 검사: 통과 $passed, 실패 $failed")
    if (failed > 0) exitProcess(1)
}
