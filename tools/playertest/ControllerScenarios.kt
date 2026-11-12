import android.content.Context
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.os.FakeClock
import android.speech.tts.TextToSpeech
import android.util.Log
import com.missedsentence.car.Controller
import com.missedsentence.car.core.DemoScenario
import com.missedsentence.car.core.Level
import com.missedsentence.car.core.NextTurn
import com.missedsentence.car.core.TurnType
import com.missedsentence.car.service.PlaybackService
import com.missedsentence.car.vehicle.FakeCar
import java.io.File
import kotlin.system.exitProcess

/**
 * 실제 Controller.kt, 엔진, 재생기, 묶음 읽기를 가짜 Android 클래스와 가짜 차량 값으로 돌려
 * 시연 장면, 기본 모드, 다시 들을 문장, 정차 요약이 처음부터 끝까지 이어지는지 봅니다.
 * 묶음은 app/src/main/assets 의 실제 pack.json 을 읽습니다. 소리 파일의 길이는 모두 3초로 칩니다.
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

private class World(val context: Context = Context(), fresh: Boolean = true) {
    val c: Controller
    var maxActive = 0
    var everHeld = false

    init {
        if (fresh) {
            FakeClock.reset()
            FakeCar.reset()
            MediaPlayer.active = 0
            TextToSpeech.available = false
            PlaybackService.starts = 0
            Log.errors.clear()
        }
        context.assets.root = File(System.getProperty("assets") ?: "app/src/main/assets")
        c = Controller(context)
        c.start()
        go(1_000)
    }

    fun go(ms: Long) {
        var left = ms
        while (left > 0) {
            FakeClock.advance(200)
            left -= 200
            if (MediaPlayer.active > maxActive) maxActive = MediaPlayer.active
            if (c.player.engineHeld) everHeld = true
        }
    }

    /** 붙잡히지 않고 재생이 이어지는 중인지. 문장 사이의 짧은 쉼도 재생 중으로 칩니다. */
    fun flowing(): Boolean = c.player.wantPlay && !c.player.engineHeld && !c.player.focusLost && !c.player.deferPending

    fun actions(): List<String> = c.recentLog(40).map { it.action }
    fun signals(): List<String> = c.recentLog(40).map { it.signal }
    fun lines(): String = c.recentLog(40).joinToString(" | ") { "${it.signal} → ${it.action}" }

    /** [wanted]가 이 순서대로 들어 있는지 */
    fun actionsInOrder(vararg wanted: String): Boolean {
        var i = 0
        for (a in actions()) if (i < wanted.size && a.contains(wanted[i])) i++
        return i == wanted.size
    }
}

private fun packsAndStart() {
    println("1. 묶음 읽기와 시작 상태")
    val w = World()
    val c = w.c
    check("묶음 세 개", c.packs.size == 3, c.packs.joinToString { it.id })
    val en = c.packs.firstOrNull { it.id == "english_commute" }
    check("영어 묶음 24문장에 소리 24개", en != null && en.sentences.size == 24 && en.audioCount == 24)
    val ko = c.packs.firstOrNull { it.id == "korean_morning" }
    check("한국어 묶음 14문장, 글만", ko != null && ko.sentences.size == 14 && ko.audioCount == 0 && ko.sentences.all { it.text.isNotBlank() })
    check("직접 녹음 자리는 비어 있음", c.packs.firstOrNull { it.id == "my_recording" }?.isEmpty == true)
    check("처음에는 소리가 있는 영어 묶음", c.pack?.id == "english_commute")
    check("기어 P에서는 정차 화면", c.showPark && c.parked)
    check("시연 모드가 기본, 민감도 보통", c.demoMode && c.level == Level.NORMAL && c.engineEnabled)
    val rows = c.supportRows()
    check("지원 확인 결과 9줄", rows.size == 9, rows.joinToString { it.name })
    check("방향지시등과 브레이크 페달을 읽음", rows[0].ok == true && rows[1].ok == true && c.turnKnown && c.brake == false, rows.take(2).joinToString { it.detail })
    check("지금 켜진 규칙 4줄", c.ruleNotes().size == 4)
    check("오류 기록 없음", Log.errors.isEmpty(), Log.errors.joinToString())
}

private fun leftTurnScene() {
    println("2. 장면: 좌회전과 신호 대기 (규칙 켬)")
    val w = World()
    val c = w.c
    c.startScene(DemoScenario.leftTurn())
    w.go(400)
    check("장면을 시작하면 재생이 켜지고 주행 화면", c.player.wantPlay && c.player.speaking && !c.showPark && c.scenario != null)
    check("재생 서비스를 시작함", PlaybackService.starts == 1)
    check("실제 내비 이벤트는 잠시 막음", FakeCar.lastNavi?.muted == true)
    w.go(14_600)
    check("회전 전에는 조용하고 좌회전 아이콘", c.player.engineHeld && !c.player.speaking && c.holdIcon() == Controller.HoldIcon.LEFT, "${c.holdIcon()}")
    check("경로 모드로 판단", c.routeMode)
    w.go(11_000)   // 26초
    check("신호 대기 중에는 다시 재생", w.flowing() && c.frame?.waitingAtStop == true, w.lines())
    w.go(6_000)    // 32초
    check("출발한 뒤 회전하는 동안 조용함", !c.player.speaking && c.player.engineHeld)
    w.go(10_000)   // 42초
    check("회전 뒤 다시 재생", w.flowing())
    w.go(12_000)
    check("장면이 끝나면 정차 화면으로", c.scenario == null && c.showPark && FakeCar.lastNavi?.muted == false)
    check(
        "신호와 동작이 순서대로 남음",
        w.actionsInOrder("문장이 끝나는 자리에서 미룸", "서 있는 동안 다시 재생", "끊음. 회전 뒤 그 문장 처음부터", "끊긴 문장 처음부터 다시 재생"),
        w.lines()
    )
    check("신호 글에 남은 시간이 들어감", w.signals().any { it.startsWith("좌회전 ") && it.endsWith("초 전") }, w.signals().joinToString())
    val t = c.today
    check("오늘 멈춘 이유: 회전 1번", t.turns == 1 && t.pedestrian == 0, "${t.turns}")
    check("문장 사이 미룸 1번, 중간 끊김 1번, 대기 중 재생 1번", t.boundary == 1 && t.cuts == 1 && t.waiting == 1, "${t.boundary} ${t.cuts} ${t.waiting}")
    check("조용했던 시간 22~27초", t.quietMs in 22_000L..27_000L, "${t.quietMs}")
    check("문장이 겹쳐 나온 적 없음", w.maxActive == 1, "${w.maxActive}")
    check("오류 기록 없음", Log.errors.isEmpty(), Log.errors.joinToString())
}

private fun leftTurnWithRealBrake() {
    println("3. 같은 장면에서 Virtual Console 브레이크를 밟고 있다가 뗌")
    val w = World()
    val c = w.c
    c.startScene(DemoScenario.leftTurn())
    w.go(22_000)
    FakeCar.brake(true)
    w.go(14_000)   // 36초: 스스로 출발할 시각이 지났지만 밟고 있음
    check("밟고 있는 동안은 출발하지 않고 계속 재생", c.frame?.waitingAtStop == true && w.flowing() && c.brake == true)
    FakeCar.brake(false)
    w.go(600)
    check("떼는 순간 끊음", !c.player.speaking && c.player.engineHeld, w.lines())
    check("출발 기록", w.signals().contains("출발: 브레이크에서 발을 뗌"))
}

private fun engineOffComparison() {
    println("4. 비교: 규칙 끔")
    val w = World()
    val c = w.c
    c.setEngineEnabled(false)
    c.startScene(DemoScenario.leftTurn())
    w.go(52_000)
    check("어떤 순간에도 붙잡지 않음", !w.everHeld)
    check("신호는 기록하되 계속 재생", w.actions().count { it == "규칙 끔: 계속 재생" } == 1, w.lines())
    check("멈춘 횟수 0", c.today.total == 0 && c.today.quietMs == 0L)
    c.setEngineEnabled(true)
    check("다시 켤 수 있음", c.engineEnabled)
}

private fun schoolAndRightScenes() {
    println("5. 장면: 어린이보호구역, 우회전")
    val w = World()
    val c = w.c
    c.startScene(DemoScenario.schoolZone())
    w.go(25_000)
    check("구역 안에서는 조용하고 어린이보호구역 아이콘", c.player.engineHeld && c.holdIcon() == Controller.HoldIcon.SCHOOL)
    w.go(28_000)
    check("벗어나면 재생, 장면 끝", w.flowing() && c.scenario == null, w.lines())
    check("벗어났다는 기록", w.signals().contains("어린이보호구역을 벗어남"))
    c.startScene(DemoScenario.rightTurn())
    w.go(20_000)
    check("우회전 앞에서 조용하고 우회전 아이콘", c.player.engineHeld && c.holdIcon() == Controller.HoldIcon.RIGHT)
    w.go(23_000)
    check("보행자 순간 2번", c.today.pedestrian == 2 && c.today.turns == 0, "${c.today.pedestrian}")
    check("우회전은 6초 전", w.signals().any { it == "우회전 6초 전" || it == "우회전 5초 전" }, w.signals().joinToString())
}

private fun basicModeAndRelisten() {
    println("6. 기본 모드, 차로 변경 예산, 다시 들을 문장, 이어 듣기")
    val w = World()
    val c = w.c
    FakeCar.gear = "D"
    w.go(400)
    check("기어 D에서는 주행 화면", !c.showPark && !c.parked)
    c.setVirtualSpeed(20f)
    c.player.play()
    w.go(1_000)
    FakeCar.turn("RIGHT")
    w.go(4_000)
    check("시속 20에서 오른쪽 방향지시등은 우회전으로 보고 쉼", c.player.engineHeld && c.holdIcon() == Controller.HoldIcon.RIGHT && !c.routeMode, w.lines())
    FakeCar.turn("OFF")
    w.go(2_600)
    check("끄고 2초 뒤 다시 재생", w.flowing(), w.lines())

    c.setVirtualSpeed(60f)
    w.go(1_000)
    check("가상 속도를 바꿔도 급제동으로 보지 않음", !c.player.engineHeld && c.today.count(com.missedsentence.car.core.Reason.HARD_BRAKE) == 0)
    FakeCar.turn("LEFT")
    w.go(600)
    check("시속 60에서 켜면 차로 변경으로 보고 끊음", c.player.engineHeld && c.holdIcon() == Controller.HoldIcon.LANE, w.lines())
    w.go(2_400)
    FakeCar.turn("OFF")
    w.go(2_600)
    check("끄고 2초 뒤 그 문장 처음부터", w.flowing())
    w.go(4_000)
    FakeCar.turn("LEFT")
    w.go(600)
    check("90초 안의 두 번째는 멈추지 않음", w.flowing())
    check("다시 들을 문장 1개", c.relistenItems().size == 1 && c.today.budgetSkips == 1, w.lines())
    val kept = c.relistenItems().first().index
    FakeCar.turn("OFF")
    w.go(1_000)

    MediaSession.last!!.callback!!.onSkipToPrevious()
    w.go(200)
    check("핸들 이전 버튼 기록", w.signals().contains("핸들의 이전 버튼"))

    c.setVirtualSpeed(null)
    FakeCar.gear = "P"
    w.go(600)
    check("기어 P에서 정차 화면, 목록은 그대로", c.showPark && c.relistenItems().size == 1)
    check("실제 속도로 돌아와도 급제동으로 보지 않음", c.today.count(com.missedsentence.car.core.Reason.HARD_BRAKE) == 0)
    FakeCar.gear = "D"
    w.go(400)
    check("출발하면 남겨 둔 문장을 먼저 듣도록 줄 세움", c.player.queueSize == 1 && w.actions().any { it == "다시 들을 문장 1개부터 이어 듣기" }, w.lines())
    val bookmark = c.player.bookmark
    w.go(9_000)
    check("남겨 둔 문장을 듣고 나면 목록에서 빠짐", c.relistenItems().isEmpty() && !c.player.fromQueue, "kept=$kept")
    check("원래 자리에서 이어 감", c.player.bookmark >= bookmark)

    c.setVirtualSpeed(20f)
    FakeCar.turn("RIGHT")
    w.go(4_000)
    check("다시 쉬는 중", c.player.engineHeld)
    FakeCar.gear = "P"
    w.go(600)
    check("기어 P로 바꾸면 붙잡은 것을 풂", !c.player.engineHeld && c.showPark)
    FakeCar.turn("OFF")
    check("문장이 겹쳐 나온 적 없음", w.maxActive == 1, "${w.maxActive}")
    check("오류 기록 없음", Log.errors.isEmpty(), Log.errors.joinToString())
}

private fun realNavEvents() {
    println("7. 실제 내비 이벤트가 들어오는 길 (경로 모드)")
    val w = World()
    val c = w.c
    val sink = FakeCar.lastNavi!!.sink
    FakeCar.gear = "D"
    FakeCar.speed = 50f
    c.setDemoMode(false)
    c.player.play()
    var dist = 400f
    var heldAt = -1f
    var steps = 0
    while (dist > 0f && steps < 400) {
        sink.onNextTurn(NextTurn(TurnType.LEFT, dist), FakeClock.now)
        w.go(200)
        if (heldAt < 0 && (c.player.engineHeld || c.player.deferPending)) heldAt = dist
        dist -= 50f / 3.6f * 0.2f
        steps++
    }
    check("좌회전 10초 전쯤 미룸을 시작", heldAt in 120f..142f, "$heldAt")
    check("경로 모드 표시", c.routeMode)
    check("2초마다 회전 안내를 다시 물음", (FakeCar.lastNavi?.polls ?: 0) >= 10, "${FakeCar.lastNavi?.polls}")
    FakeCar.steer = -180f
    sink.onNextTurn(NextTurn(TurnType.STRAIGHT, 900f), FakeClock.now)
    w.go(1_000)
    FakeCar.steer = 0f
    var d2 = 880f
    repeat(20) {
        sink.onNextTurn(NextTurn(TurnType.STRAIGHT, d2), FakeClock.now)
        w.go(200)
        d2 -= 2.8f
    }
    check("안내가 다음 단계로 넘어가고 조향이 3초 안정되면 재생", w.flowing(), w.lines())
    check("시연 모드를 꺼도 같은 규칙", !c.demoMode && c.today.turns == 1)
}

private fun storyRelisten() {
    println("8. 이야기 묶음: 앞뒤 문장과 함께 다시 듣기, 다시 찍기, 저장")
    val w = World()
    val c = w.c
    c.selectPack("korean_morning")
    check("이야기 묶음으로 바꿈", c.pack?.id == "korean_morning" && c.player.index == 0)
    FakeCar.gear = "D"
    w.go(400)
    c.setVirtualSpeed(60f)
    c.player.play()
    w.go(12_000)
    FakeCar.turn("LEFT"); w.go(1_000); FakeCar.turn("OFF"); w.go(4_000)
    FakeCar.turn("LEFT"); w.go(1_000); FakeCar.turn("OFF"); w.go(1_000)
    val items = c.relistenItems()
    check("예산을 넘은 차로 변경의 문장이 남음", items.size == 1, w.lines())
    val idx = items.firstOrNull()?.index ?: 0
    c.setVirtualSpeed(null)
    FakeCar.gear = "P"
    w.go(600)
    check("이야기 묶음은 출발해도 자동으로 줄 세우지 않음", c.queueRelisten() == 0)
    c.player.pause()
    val bookmark = c.player.bookmark
    c.listenAround(idx)
    w.go(400)
    check("앞 문장부터 듣기 시작", c.player.inRange && c.player.speaking && c.player.index == maxOf(0, idx - 1), "${c.player.index} $idx")
    w.go(60_000)
    check("세 문장을 듣고 멈춤, 목록에서 빠짐", !c.player.wantPlay && !c.player.inRange && c.relistenItems().isEmpty(), "${c.relistenItems().size}")
    check("듣던 자리는 그대로", c.player.bookmark == bookmark, "${c.player.bookmark} $bookmark")

    c.setLevel(Level.LOW)
    val again = World(w.context, fresh = false)
    check("다시 켜도 묶음, 민감도, 듣던 자리가 남아 있음", again.c.pack?.id == "korean_morning" && again.c.level == Level.LOW && again.c.player.bookmark == bookmark)
    check("오늘 기록도 남아 있음", again.c.today.budgetSkips == 1 && again.c.today.count(com.missedsentence.car.core.Reason.LANE_CHANGE) == 1)
    again.c.resetForRetake()
    check("다시 찍기: 처음 문장, 기록 비움", again.c.player.bookmark == 0 && again.c.today.total == 0 && again.c.today.budgetSkips == 0 && again.c.recentLog(5).isEmpty())
    check("저장소에 위치가 없음", w.context.getSharedPreferences("missed", 0).map.keys.none { it.contains("lat") || it.contains("lon") || it.contains("location") })
    check("오류 기록 없음", Log.errors.isEmpty(), Log.errors.joinToString())
}

private fun fullCommute() {
    println("9. 장면: 출근길 전체")
    val w = World()
    val c = w.c
    c.startScene(DemoScenario.commute())
    w.go(170_000)
    val t = c.today
    check("끝까지 달리고 정차 화면", c.scenario == null && c.showPark)
    check("회전 1번, 보행자 순간 2번", t.turns == 1 && t.pedestrian == 2, "${t.turns} ${t.pedestrian}")
    check("조용했던 시간이 전체의 절반을 넘지 않음", t.quietMs in 40_000L..85_000L, "${t.quietMs}")
    check("영어 묶음은 마지막 문장 뒤 처음부터 다시 들어 끊기지 않음", c.player.wantPlay)
    check("문장이 겹쳐 나온 적 없음", w.maxActive == 1)
    check("오류 기록 없음", Log.errors.isEmpty(), Log.errors.joinToString())
}

private fun carMediaScreen() {
    println("10. 차량 미디어 화면 연동")
    val w = World()
    val c = w.c
    val session = MediaSession.last!!
    fun subtitle() = session.lastMetadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
    val first = c.pack!!
    check("쉬는 이유가 없으면 둘째 줄은 묶음 종류와 문장 수", subtitle() == "${first.kind.label}, 문장 ${first.sentences.size}개", "${subtitle()}")
    check("앱을 켜는 것만으로는 재생하지 않음", !c.player.wantPlay && PlaybackService.starts == 0)
    check("주행 규제가 없으면 화면이 가려진 상태가 아님", !c.screenBlocked)
    FakeCar.gear = "D"
    FakeCar.ux = true
    FakeCar.screenAllowed = false
    w.go(6_000)
    check("주행 규제 중인데 표시를 인정받지 못하면 화면이 가려진 상태로 봄", c.screenBlocked)
    FakeCar.screenAllowed = true
    w.go(6_000)
    check("표시를 인정받으면 주행 중에도 가려진 상태가 아님", !c.screenBlocked)
    FakeCar.gear = "P"
    FakeCar.ux = false
    w.go(1_000)

    val other = c.packs.first { !it.isEmpty && it.id != first.id }
    session.callback!!.onPlayFromMediaId(other.id, null)
    w.go(1_000)
    check("차량 미디어 화면에서 고른 묶음이 재생됨", c.pack?.id == other.id && c.player.wantPlay, "${c.pack?.id}")
    check("재생이 시작되면 재생 서비스가 뜸", PlaybackService.starts >= 1)
    session.callback!!.onPause()
    session.callback!!.onPlayFromSearch(first.title, null)
    w.go(1_000)
    check("음성으로 묶음 이름을 말하면 그 묶음을 재생", c.pack?.id == first.id && c.player.wantPlay, "${c.pack?.id}")
    session.callback!!.onPause()
    session.callback!!.onPlayFromSearch("", null)
    check("말이 비어 있으면 듣던 묶음을 이어 재생", c.pack?.id == first.id && c.player.wantPlay)
    session.callback!!.onPause()

    c.startScene(DemoScenario.leftTurn())
    var waited = 0
    while (!c.player.engineHeld && waited < 60_000) {
        w.go(200)
        waited += 200
    }
    check("좌회전 앞에서 붙잡히면 둘째 줄에 이유가 실림", c.player.engineHeld && subtitle() == "좌회전이 끝나면 이어서 들려줘요", "${subtitle()}")
    waited = 0
    while (c.player.engineHeld && waited < 60_000) {
        w.go(200)
        waited += 200
    }
    w.go(400)
    check("풀리면 둘째 줄이 원래대로 돌아옴", subtitle()?.contains("문장") == true, "${subtitle()}")
    check("오류 기록 없음", Log.errors.isEmpty(), Log.errors.joinToString())
}

fun main() {
    packsAndStart()
    leftTurnScene()
    leftTurnWithRealBrake()
    engineOffComparison()
    schoolAndRightScenes()
    basicModeAndRelisten()
    realNavEvents()
    storyRelisten()
    fullCommute()
    carMediaScreen()
    println("앱 흐름 검사: 통과 $passed, 실패 $failed")
    if (failed > 0) exitProcess(1)
}
