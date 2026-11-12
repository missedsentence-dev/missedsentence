package com.missedsentence.car

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.missedsentence.car.content.Pack
import com.missedsentence.car.content.PackRepo
import com.missedsentence.car.core.Decision
import com.missedsentence.car.core.DemoScenario
import com.missedsentence.car.core.Level
import com.missedsentence.car.core.LoadEngine
import com.missedsentence.car.core.NavState
import com.missedsentence.car.core.Reason
import com.missedsentence.car.core.SchoolZoneJudge
import com.missedsentence.car.core.SignalReader
import com.missedsentence.car.core.Signals
import com.missedsentence.car.core.TurnType
import com.missedsentence.car.player.SentencePlayer
import com.missedsentence.car.service.PlaybackService
import com.missedsentence.car.vehicle.CarSignals
import com.missedsentence.car.vehicle.NaviBridge
import com.missedsentence.car.vehicle.VehicleProbe
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumMap
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 차량 신호와 내비 정보를 모아 엔진에 넣고, 엔진의 결정을 재생기에 전합니다.
 * 시연 출근길이 만든 값도 실제 값과 같은 길로 들어갑니다.
 * 판단은 메인 스레드에서 0.2초마다 하고, 차량 값 읽기는 따로 둔 스레드에서 합니다.
 */
class Controller(context: Context) : SentencePlayer.Listener {

    /** 주행 화면에 띄우는 이유 아이콘 종류 */
    enum class HoldIcon { SCHOOL, RIGHT, LEFT, UTURN, MERGE, LANE, BRAKE, VOICE }

    class LogLine(val clock: String, val signal: String, val action: String)

    class RelistenItem(val index: Int, val reason: Reason)

    class Support(val name: String, val ok: Boolean?, val detail: String)

    /** 오늘 멈춘 이유. 위치는 담지 않습니다. */
    class Today {
        var date = ""
        val pauses: EnumMap<Reason, Int> = EnumMap(Reason::class.java)
        var boundary = 0
        var cuts = 0
        var waiting = 0
        var budgetSkips = 0
        var focusPauses = 0
        var quietMs = 0L
        var listenedMs = 0L

        fun count(r: Reason): Int = pauses[r] ?: 0
        val total: Int get() = pauses.values.sum()
        val turns: Int get() = count(Reason.COMPLEX_TURN)
        val pedestrian: Int get() = count(Reason.RIGHT_TURN) + count(Reason.SCHOOL_ZONE)
    }

    private class CarSnap(
        val speedKmh: Float? = null,
        val gear: String? = null,
        val steeringDeg: Float? = null,
        val uxRestricted: Boolean? = null,
        val values: Map<String, String> = emptyMap(),
        val carAvailable: Boolean = false,
        val screenAllowed: Boolean? = null
    )

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("missed-vehicle").apply { start() }
    private val bg = Handler(thread.looper)
    private val prefs: SharedPreferences = app.getSharedPreferences("missed", Context.MODE_PRIVATE)

    val packs: List<Pack> = PackRepo.loadAll(app)
    val player = SentencePlayer(app)
    val engine = LoadEngine()
    private val judge = SchoolZoneJudge(loadZones())
    val nav = NavState(judge)
    val navi = NaviBridge(app, nav)
    private val car = CarSignals(app)
    private val probe = VehicleProbe(app, bg)

    @Volatile private var snap = CarSnap()

    var pack: Pack? = null
        private set
    val today = Today()
    private val relisten = HashMap<String, MutableList<RelistenItem>>()
    private val logLines = ArrayDeque<LogLine>()

    // ---- 화면이 읽는 지금 상태 ----
    var speedKmh: Float? = null
        private set
    var gear: String? = null
        private set
    var parked = true
        private set
    var turnKnown = false
        private set
    var turnLeft = false
        private set
    var turnRight = false
        private set
    var brake: Boolean? = null
        private set
    var steeringDeg: Float? = null
        private set
    var routeMode = false
        private set
    var frame: DemoScenario.Frame? = null
        private set
    var lastSignals: Signals? = null
        private set

    // ---- 시연 도구 ----
    var demoMode = true
        private set
    var engineEnabled = true
        private set
    var virtualSpeed: Float? = null
        private set
    var preview = false
        private set
    var scenario: DemoScenario? = null
        private set

    /** 목록, 콘텐츠, 설정처럼 정차 화면을 다시 그려야 하는 변화가 생길 때마다 늘어납니다. */
    var structure = 0
        private set

    private val tickListeners = ArrayList<() -> Unit>()
    private var started = false
    private var lastTick = 0L
    private var tickCount = 0L
    private var waitingPlay = false
    private var holdTurnType: TurnType? = null
    private var rangeItem: Int? = null
    private var lastSaveMs = 0L
    private var lastSavedToday = ""

    val level: Level get() = engine.level

    // ------------------------------------------------------------------ 시작

    fun start() {
        if (started) return
        started = true
        loadState()
        player.listener = this
        probe.start()
        bg.post { car.connect() }
        bg.post(carPoll)
        navi.connect()
        navi.onPlacesChanged = { main.post { bump() } }
        lastTick = SystemClock.elapsedRealtime()
        main.post(tickTask)
    }

    fun addTickListener(l: () -> Unit) {
        tickListeners.add(l)
    }

    fun removeTickListener(l: () -> Unit) {
        tickListeners.remove(l)
    }

    private fun bump() {
        structure++
    }

    private val carPoll: Runnable = object : Runnable {
        private var count = 0
        private var allowed: Boolean? = null
        override fun run() {
            try {
                if (!car.available && count % 50 == 0) car.connect()
                // 화면 표시 허용 여부는 자주 바뀌지 않으므로 5초에 한 번만 묻습니다.
                if (count % 25 == 0) allowed = car.screenAllowedWhileDriving(APP_PACKAGE, MAIN_SCREEN)
                count++
                snap = CarSnap(
                    speedKmh = car.speedKmh(),
                    gear = car.gear(),
                    steeringDeg = car.steeringDeg(),
                    uxRestricted = car.uxRestricted(),
                    values = probe.snapshot(),
                    carAvailable = car.available,
                    screenAllowed = allowed
                )
            } catch (t: Throwable) {
                Log.w(TAG, "car poll failed", t)
            }
            bg.postDelayed(this, CAR_POLL_MS)
        }
    }

    private val tickTask: Runnable = object : Runnable {
        override fun run() {
            try {
                tick()
            } catch (t: Throwable) {
                Log.e(TAG, "tick failed", t)
            }
            main.postDelayed(this, TICK_MS)
        }
    }

    // ------------------------------------------------------------------ 0.2초마다

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val dt = (now - lastTick).coerceIn(0L, 1_000L)
        lastTick = now
        tickCount++
        if (tickCount % 150 == 1L) rollDate()

        player.loop = pack?.kind == Pack.Kind.LANGUAGE || scenario != null

        val s0 = snap
        val realTurn = SignalReader.turn(s0.values)
        val realBrake = SignalReader.brakePedal(s0.values)
        val realSteer = s0.steeringDeg ?: SignalReader.steeringDeg(s0.values)

        // 정차 화면은 기어 P이고 주행 규제가 풀렸을 때만. 기어를 모르면 규제 여부만 봅니다.
        val wasParked = parked
        parked = (s0.gear == "P" || s0.gear == null) && s0.uxRestricted != true
        if (wasParked && !parked) onDeparture()
        if (wasParked != parked) bump()

        var speed = s0.speedKmh
        var engineGear = s0.gear
        var left = realTurn.left
        var right = realTurn.right
        var brakeNow = realBrake
        var steer = realSteer

        val sc = scenario
        if (sc != null) {
            // 시연 출근길이 만든 값. 내비 정보는 실제 이벤트와 같은 통로로 넣습니다.
            val f = sc.step(dt, realBrake == true)
            frame = f
            nav.onNextTurn(f.nextTurn, now)
            if (f.schoolCautionM != null) nav.onSchoolCaution(f.schoolCautionM, now)
            nav.onSpeedLimit(f.speedLimitKmh, now)
            speed = f.speedKmh
            engineGear = "D"
            left = left || f.turnLeft
            right = right || f.turnRight
            brakeNow = f.brake || realBrake == true
            steer = if (realSteer != null && abs(realSteer) > 5f) realSteer else f.steeringDeg
        } else {
            val v = virtualSpeed
            if (v != null && !showPark) {
                speed = v
                engineGear = "D"
            }
            // 주행 화면 미리보기에서는 기어가 P여도 규칙이 돌게 합니다.
            if (preview && engineGear == "P") engineGear = "D"
            if (tickCount % 10 == 0L) {
                if (navi.connected) navi.poll() else if (tickCount % 50 == 0L) navi.connect()
            }
        }

        speedKmh = speed
        gear = s0.gear
        turnKnown = realTurn.known
        turnLeft = left
        turnRight = right
        brake = brakeNow
        steeringDeg = steer
        routeMode = nav.routeActive(now)

        val zone = nav.zone(now)
        val signals = Signals(
            tMs = now,
            speedKmh = speed,
            gear = engineGear,
            turnLeft = left,
            turnRight = right,
            brakePedal = brakeNow,
            steeringDeg = steer,
            routeActive = routeMode,
            nextTurn = nav.nextTurn(now),
            inSchoolZone = zone.inZone,
            schoolAheadM = zone.aheadM
        )
        lastSignals = signals
        for (d in engine.update(signals)) apply(d, signals)

        if (player.wantPlay && player.engineHeld) today.quietMs += dt
        if (player.speaking) today.listenedMs += dt

        if (sc != null && sc.finished) endScenario("도착")
        if (now - lastSaveMs > SAVE_EVERY_MS) saveState()
        player.statusLine = statusLine()
        if (demoMode) {
            if (tickCount % 5 == 0L) Log.i(TAG, stateLine())
            if (tickCount % 50 == 0L) Log.i(TAG, "SUPPORT " + supportRows().joinToString(" | ") { "${it.name}=${it.ok}(${it.detail})" })
        }
        for (l in ArrayList(tickListeners)) l()
    }

    /**
     * 시연 모드에서 1초마다 기록에 남기는 한 줄. 에뮬레이터에서 차량 값이 제대로 읽히는지 확인하는 용도입니다.
     * 위치와 콘텐츠 내용은 담지 않습니다.
     */
    private fun stateLine(): String {
        val raw = probe.diagnostics()
            .filter { r -> RAW_PREFIXES.any { r.key.startsWith(it) } }
            .joinToString("; ") { "${it.key}=${it.value ?: "오류 ${it.error}"}" }
        val line = "STATE gear=$gear speed=${speedKmh?.roundToInt()} parked=$parked ux=${snap.uxRestricted} blocked=$screenBlocked route=$routeMode " +
            "play=${player.wantPlay} speaking=${player.speaking} held=${player.engineHeld} reason=${engine.holdReason} " +
            "turnKnown=$turnKnown left=$turnLeft right=$turnRight brake=$brake steer=${steeringDeg?.roundToInt()} | $raw"
        return if (line.length > 900) line.substring(0, 900) else line
    }

    /** 지금 정차 화면을 보여 줄 상황인지 */
    val showPark: Boolean get() = parked && !preview && scenario == null

    /**
     * 주행 규제가 걸렸는데 이 앱 화면이 주행 중 표시를 인정받지 못해 차량이 화면을 가린 상태.
     * 이때는 보이지 않는 화면을 고쳐 그리지 않습니다. 소리와 판단은 그대로 이어집니다.
     */
    val screenBlocked: Boolean get() = snap.uxRestricted == true && snap.screenAllowed == false

    private fun rollDate() {
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (today.date != d) {
            today.date = d
            today.pauses.clear()
            today.boundary = 0
            today.cuts = 0
            today.waiting = 0
            today.budgetSkips = 0
            today.focusPauses = 0
            today.quietMs = 0L
            today.listenedMs = 0L
            bump()
        }
    }

    // ------------------------------------------------------------------ 엔진의 결정을 재생기로

    private fun apply(d: Decision, s: Signals) {
        if (!engineEnabled) {
            // 비교용: 규칙을 끄면 신호만 기록하고 아무것도 하지 않습니다.
            when (d) {
                is Decision.Defer -> log(signalText(d.reason, s), "규칙 끔: 계속 재생")
                is Decision.Cut -> if (waitingPlay) waitingPlay = false else log(signalText(d.reason, s), "규칙 끔: 계속 재생")
                is Decision.Relisten -> log(signalText(d.reason, s), "규칙 끔: 계속 재생")
                is Decision.Resume -> waitingPlay = d.whileWaiting
            }
            return
        }
        val listening = player.wantPlay
        when (d) {
            is Decision.Defer -> {
                waitingPlay = false
                holdTurnType = s.nextTurn?.type
                val boundary = player.engineDefer(d.graceMs)
                if (listening) {
                    today.pauses[d.reason] = today.count(d.reason) + 1
                    if (boundary) today.boundary++ else today.cuts++
                }
                log(signalText(d.reason, s), if (boundary) "문장이 끝나는 자리에서 미룸" else "문장이 길어 바로 끊음")
            }
            is Decision.Cut -> {
                val departure = waitingPlay
                waitingPlay = false
                if (!departure) holdTurnType = s.nextTurn?.type
                val mid = player.engineCut()
                if (listening) {
                    if (!departure) today.pauses[d.reason] = today.count(d.reason) + 1
                    if (mid) today.cuts++ else if (!departure) today.boundary++
                }
                if (departure) {
                    log("출발: 브레이크에서 발을 뗌", if (mid) "끊음. 회전 뒤 그 문장 처음부터" else "조용히 기다림")
                } else {
                    log(signalText(d.reason, s), if (mid) "끊음. 지나가면 그 문장 처음부터" else "조용히 기다림")
                }
            }
            is Decision.Resume -> {
                if (d.whileWaiting) {
                    waitingPlay = true
                    today.waiting++
                    player.engineResume()
                    log("회전 전 5초 넘게 정차", "서 있는 동안 다시 재생")
                } else {
                    waitingPlay = false
                    val mid = player.heldMid
                    player.engineResume()
                    log(resumeText(d.reason), if (mid) "끊긴 문장 처음부터 다시 재생" else "다음 문장부터 이어 재생")
                }
            }
            is Decision.Relisten -> {
                today.budgetSkips++
                // 지금 들리는 문장, 문장 사이라면 곧 나올 문장을 남깁니다.
                val idx = if (player.engineHeld || player.focusLost) null else player.audibleIndex() ?: player.index
                val p = pack
                if (idx != null && p != null && listening && !player.fromQueue && !player.inRange) {
                    addRelisten(p.id, idx, d.reason)
                    log("예산을 넘은 차로 변경", "멈추지 않고 다시 들을 문장에 남김")
                } else {
                    log("예산을 넘은 차로 변경", "멈추지 않음")
                }
            }
        }
        bump()
    }

    private fun signalText(reason: Reason, s: Signals): String {
        val v = s.speedKmh?.roundToInt()
        val turn = s.nextTurn
        val eta = if (turn != null && s.speedKmh != null && s.speedKmh > 3f) {
            (turn.distanceM / (s.speedKmh / 3.6f)).roundToInt()
        } else null
        return when (reason) {
            Reason.SCHOOL_ZONE ->
                if (s.inSchoolZone) "어린이보호구역 진입" else "어린이보호구역 ${s.schoolAheadM?.roundToInt() ?: 0}m 앞"
            Reason.RIGHT_TURN ->
                if (s.routeActive && turn != null && eta != null) "우회전 ${eta}초 전"
                else "오른쪽 방향지시등, 시속 ${v ?: 0}km"
            Reason.COMPLEX_TURN ->
                if (s.routeActive && turn != null && eta != null) "${turnLabel(turn.type)} ${eta}초 전"
                else "왼쪽 방향지시등, 시속 ${v ?: 0}km"
            Reason.LANE_CHANGE -> "방향지시등, 시속 ${v ?: 0}km: 차로 변경"
            Reason.HARD_BRAKE -> "급제동"
        }
    }

    private fun resumeText(reason: Reason): String = when (reason) {
        Reason.SCHOOL_ZONE -> "어린이보호구역을 벗어남"
        Reason.RIGHT_TURN -> "우회전을 마치고 조향 안정 2초"
        Reason.COMPLEX_TURN -> "회전을 마치고 조향 안정 3초"
        Reason.LANE_CHANGE -> "방향지시등이 꺼지고 2초"
        Reason.HARD_BRAKE -> "3초 동안 잦아듦"
    }

    private fun turnLabel(type: TurnType): String = when (type) {
        TurnType.LEFT -> "좌회전"
        TurnType.RIGHT -> "우회전"
        TurnType.UTURN -> "유턴"
        TurnType.MERGE -> "합류"
        TurnType.EXIT -> "진출"
        TurnType.FORK -> "분기"
        TurnType.STRAIGHT -> "직진"
        TurnType.OTHER -> "회전"
    }

    /** 주행 화면에 띄울 이유 아이콘. 조용한 이유가 없으면 null */
    fun holdIcon(): HoldIcon? {
        if (!player.wantPlay) return null
        if (player.focusLost) return HoldIcon.VOICE
        if (!player.engineHeld) return null
        return when (engine.holdReason) {
            Reason.SCHOOL_ZONE -> HoldIcon.SCHOOL
            Reason.RIGHT_TURN -> HoldIcon.RIGHT
            Reason.LANE_CHANGE -> HoldIcon.LANE
            Reason.HARD_BRAKE -> HoldIcon.BRAKE
            Reason.COMPLEX_TURN -> when (holdTurnType) {
                TurnType.UTURN -> HoldIcon.UTURN
                TurnType.MERGE, TurnType.EXIT, TurnType.FORK -> HoldIcon.MERGE
                else -> HoldIcon.LEFT
            }
            null -> null
        }
    }

    /** 정차 화면에서 이유를 글로 보여 줄 때 */
    fun holdLabel(): String? = when (holdIcon()) {
        HoldIcon.SCHOOL -> "어린이보호구역"
        HoldIcon.RIGHT -> "우회전"
        HoldIcon.LEFT -> "좌회전"
        HoldIcon.UTURN -> "유턴"
        HoldIcon.MERGE -> "합류와 진출"
        HoldIcon.LANE -> "차로 변경"
        HoldIcon.BRAKE -> "급제동"
        HoldIcon.VOICE -> "다른 앱의 안내 음성"
        null -> null
    }

    /**
     * 차량 미디어 화면의 둘째 줄. 쉬는 이유가 있으면 언제 이어지는지를, 없으면 묶음 종류와 문장 수를 적습니다.
     * 주행 중에는 앱 화면이 보이지 않을 수 있어, 운전자가 조용한 이유를 알 수 있는 자리는 이 줄입니다.
     */
    fun statusLine(): String {
        val p = pack ?: return ""
        return when (holdIcon()) {
            HoldIcon.SCHOOL -> "어린이보호구역을 지나면 이어서 들려줘요"
            HoldIcon.RIGHT -> "우회전이 끝나면 이어서 들려줘요"
            HoldIcon.LEFT -> "좌회전이 끝나면 이어서 들려줘요"
            HoldIcon.UTURN -> "유턴이 끝나면 이어서 들려줘요"
            HoldIcon.MERGE -> "합류와 진출이 끝나면 이어서 들려줘요"
            HoldIcon.LANE -> "차로 변경이 끝나면 이어서 들려줘요"
            HoldIcon.BRAKE -> "급제동이 지나가면 이어서 들려줘요"
            HoldIcon.VOICE -> "안내 음성이 끝나면 이어서 들려줘요"
            null -> "${p.kind.label}, 문장 ${p.sentences.size}개"
        }
    }

    private fun log(signal: String, action: String) {
        val clock = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logLines.addLast(LogLine(clock, signal, action))
        while (logLines.size > MAX_LOG) logLines.removeFirst()
    }

    fun recentLog(n: Int): List<LogLine> = logLines.toList().takeLast(n)

    // ------------------------------------------------------------------ 재생기에서 오는 일

    override fun onSentenceCompleted(index: Int, fromQueue: Boolean) {
        val p = pack ?: return
        if (fromQueue) removeRelisten(p.id, index)
        val item = rangeItem
        if (item != null && !player.inRange) {
            removeRelisten(p.id, item)
            rangeItem = null
        }
        prefs.edit().putInt("bookmark_${p.id}", player.bookmark).apply()
    }

    override fun onDeferBecameCut() {
        if (today.boundary > 0) today.boundary--
        today.cuts++
        log("문장이 4초 안에 끝나지 않음", "끊음. 지나가면 그 문장 처음부터")
        bump()
    }

    override fun onFocusPause(midSentence: Boolean) {
        today.focusPauses++
        log("다른 앱의 안내 음성", if (midSentence) "멈춤. 끝나면 그 문장 처음부터" else "멈춤")
        bump()
    }

    override fun onFocusBack() {
        log("안내 음성이 끝남", "그 문장 처음부터 다시 재생")
    }

    override fun onFocusGone() {
        log("다른 앱이 재생을 시작함", "멈춤")
        bump()
    }

    override fun onPreviousKey() {
        log("핸들의 이전 버튼", "한 문장 다시")
    }

    override fun onPackLooped() {
        pack?.let { prefs.edit().putInt("bookmark_${it.id}", 0).apply() }
        log("마지막 문장까지 들음", "처음부터 다시")
    }

    override fun onPackFinished() {
        pack?.let { prefs.edit().putInt("bookmark_${it.id}", 0).apply() }
        log("마지막 문장까지 들음", "멈춤")
        bump()
    }

    override fun onPlayRequest(mediaId: String) {
        if (mediaId != pack?.id) selectPack(mediaId)
        log("차량 미디어 화면에서 묶음을 고름", "재생")
        if (!player.wantPlay) player.play()
    }

    override fun onSearchRequest(query: String) {
        val q = query.trim()
        val hit = if (q.isEmpty()) null else packs.firstOrNull {
            !it.isEmpty && (it.title.contains(q, true) || q.contains(it.title, true) || q.contains(it.kind.label))
        }
        if (hit != null && hit.id != pack?.id) selectPack(hit.id)
        log(if (q.isEmpty()) "음성으로 재생을 요청" else "음성으로 '$q' 을 찾음", "재생")
        if (!player.wantPlay) player.play()
    }

    private var lastWantPlay = false

    override fun onStateChanged() {
        if (rangeItem != null && !player.inRange) rangeItem = null
        if (player.wantPlay != lastWantPlay) {
            lastWantPlay = player.wantPlay
            if (lastWantPlay) PlaybackService.start(app)
            bump()
        }
    }

    // ------------------------------------------------------------------ 운전자가 하는 일

    fun selectPack(id: String) {
        val p = packs.firstOrNull { it.id == id } ?: return
        if (p.isEmpty) return
        val wasPlaying = player.wantPlay
        if (wasPlaying) player.pause()
        releaseHold()
        pack = p
        player.load(p, prefs.getInt("bookmark_${p.id}", 0))
        prefs.edit().putString("pack", p.id).apply()
        bump()
    }

    fun setLevel(l: Level) {
        engine.level = l
        prefs.edit().putString("level", l.name).apply()
        bump()
    }

    fun relistenItems(packId: String? = pack?.id): List<RelistenItem> =
        if (packId == null) emptyList() else relisten[packId]?.sortedBy { it.index } ?: emptyList()

    private fun addRelisten(packId: String, index: Int, reason: Reason) {
        val list = relisten.getOrPut(packId) { ArrayList() }
        if (list.none { it.index == index }) list.add(RelistenItem(index, reason))
        saveRelisten(packId)
    }

    fun removeRelisten(packId: String, index: Int) {
        val list = relisten[packId] ?: return
        if (list.removeAll { it.index == index }) {
            saveRelisten(packId)
            bump()
        }
    }

    /** 이야기 콘텐츠: 다시 들을 문장을 앞뒤 한 문장과 함께 듣습니다. */
    fun listenAround(index: Int) {
        rangeItem = index
        player.playRange(index - 1, index + 1)
        bump()
    }

    /** 어학 콘텐츠: 다시 들을 문장부터 이어 듣습니다. 출발할 때 자동으로 불립니다. */
    fun queueRelisten(): Int {
        val p = pack ?: return 0
        if (p.kind != Pack.Kind.LANGUAGE) return 0
        val items = relistenItems(p.id)
        if (items.isEmpty()) return 0
        player.setQueue(items.map { it.index })
        return items.size
    }

    private fun onDeparture() {
        val n = queueRelisten()
        if (n > 0) log("출발", "다시 들을 문장 ${n}개부터 이어 듣기")
    }

    /** 평일 아침 '회사로 안내 시작'. 즐겨찾기에 회사가 없으면 null을 돌려주고 화면이 목적지를 묻습니다. */
    fun routeToWork(): NaviBridge.Place? {
        val place = navi.workPlace() ?: return null
        return if (navi.routeTo(place)) place else null
    }

    // ------------------------------------------------------------------ 시연 도구

    fun setDemoMode(on: Boolean) {
        demoMode = on
        if (!on) {
            endScenario(null)
            virtualSpeed = null
            preview = false
            if (!engineEnabled) setEngineEnabled(true)
        }
        prefs.edit().putBoolean("demo", on).apply()
        bump()
    }

    /** 끄면 휴대폰을 블루투스로 연결해 들을 때처럼 어떤 순간에도 계속 재생합니다. */
    fun setEngineEnabled(on: Boolean) {
        if (engineEnabled == on) return
        engineEnabled = on
        engine.reset()
        waitingPlay = false
        if (player.engineHeld || player.deferPending) player.engineResume()
        log(if (on) "규칙 켬" else "규칙 끔", if (on) "놓친문장 방식" else "휴대폰을 블루투스로 연결해 들을 때와 같음")
        bump()
    }

    fun setVirtualSpeed(kmh: Float?) {
        if (virtualSpeed == kmh) return
        virtualSpeed = kmh
        // 속도가 갑자기 바뀌는 것을 급제동으로 보지 않도록 엔진의 기억을 비웁니다. 차로 변경 예산은 그대로 둡니다.
        releaseHold()
        bump()
    }

    fun setPreview(on: Boolean) {
        preview = on
        bump()
    }

    fun startScene(route: DemoScenario.Route) {
        endScenario(null)
        releaseHold()
        nav.clear()
        navi.muted = true
        scenario = DemoScenario(route)
        log("시연 장면: ${route.title}", if (engineEnabled) "규칙 켬" else "규칙 끔")
        if (!player.wantPlay) player.play()
        bump()
    }

    fun endScenario(why: String?) {
        if (scenario == null) return
        scenario = null
        frame = null
        navi.muted = false
        nav.clear()
        releaseHold()
        preview = false
        if (why != null) log("시연 장면 끝: $why", "실제 차량 값으로 돌아감")
        bump()
    }

    /** 처음 문장으로 돌아가고 오늘 기록과 다시 들을 문장을 지웁니다. 같은 장면을 다시 찍을 때 씁니다. */
    fun resetForRetake() {
        val p = pack
        if (player.wantPlay) player.pause()
        releaseHold()
        today.date = ""
        rollDate()
        logLines.clear()
        engine.resetBudget()
        if (p != null) {
            relisten.remove(p.id)
            saveRelisten(p.id)
            prefs.edit().putInt("bookmark_${p.id}", 0).apply()
            player.load(p, 0)
        }
        saveState()
        bump()
    }

    private fun releaseHold() {
        engine.reset()
        waitingPlay = false
        if (player.engineHeld || player.deferPending) player.engineResume()
    }

    // ------------------------------------------------------------------ 지원 확인 결과

    /** 시작할 때 확인한 신호별 지원 여부. 없는 신호의 규칙은 꺼집니다. */
    fun supportRows(): List<Support> {
        val s0 = snap
        val rows = probe.diagnostics()
        val out = ArrayList<Support>()

        fun domain(prefix: String, filter: (String) -> Boolean): Pair<Boolean?, String> {
            val mine = rows.filter { it.key.startsWith(prefix) && filter(it.key.lowercase()) }
            val ok = mine.firstOrNull { it.value != null }
            if (ok != null) return true to "${ok.key} = ${ok.value}"
            val err = mine.firstOrNull { it.error != null }
            if (err != null) return false to "${err.key}: ${err.error}"
            val st = probe.stats()
            return if (st.connected) null to "확인 중" else false to (st.initError ?: "Vehicle SDK에 연결하지 못함")
        }

        val turn = domain("TurnSignal.") { true }
        out.add(Support("방향지시등", turn.first, turn.second))
        val pedal = domain("Brake.") { it.contains("pedal") }
        out.add(Support("브레이크 페달", pedal.first, pedal.second))
        val angle = domain("SteeringWheel.") { it.contains("angle") }
        out.add(
            if (s0.steeringDeg != null) Support("조향각", true, "AAOS 조향각 = ${s0.steeringDeg.roundToInt()}도")
            else Support("조향각", angle.first, angle.second)
        )
        // 속도는 차량 속도 권한을 허용해야 읽힙니다. 기어만 읽히고 속도가 비면 권한이 없는 경우가 많습니다.
        out.add(
            Support(
                "속도", if (s0.carAvailable) s0.speedKmh != null else false,
                when {
                    !s0.carAvailable -> car.lastError ?: "AAOS Car API에 연결하지 못함"
                    s0.speedKmh != null -> "시속 ${s0.speedKmh.roundToInt()}"
                    else -> "값 없음. 차량 속도 권한을 허용했는지 확인해 주세요"
                }
            )
        )
        out.add(
            Support(
                "기어", if (s0.carAvailable) s0.gear != null else false,
                if (s0.carAvailable) (s0.gear ?: "값 없음") else car.lastError ?: "AAOS Car API에 연결하지 못함"
            )
        )
        out.add(
            Support(
                "주행 규제 상태", s0.uxRestricted != null,
                when (s0.uxRestricted) {
                    true -> "규제 중"
                    false -> "규제 없음"
                    null -> "값 없음"
                }
            )
        )
        out.add(
            Support(
                "내비 연결", navi.connected,
                if (navi.connected) (if (routeMode) "길안내 중, 회전 안내 받는 중" else "연결됨, 길안내 없음")
                else navi.lastError ?: "NaviHelper에 연결하지 못함"
            )
        )
        val zone = nav.lastZone
        out.add(
            Support(
                "어린이보호구역 판정", zone.source != SchoolZoneJudge.Source.OFF, zone.source.label
            )
        )
        out.add(Support("문장 소리", true, player.backend.label))
        return out
    }

    /** 지원 확인 결과에 따라 지금 켜져 있는 규칙 */
    fun ruleNotes(): List<String> {
        val out = ArrayList<String>()
        out.add(if (routeMode) "경로 모드: 회전 안내로 미리 미룸" else "기본 모드: 방향지시등과 속도로 판단")
        out.add(if (turnKnown) "방향지시등 규칙 켬" else "방향지시등 값이 없어 차로 변경과 기본 모드 회전 규칙 끔")
        out.add(if (brake != null) "신호 대기 출발은 브레이크 페달로 판단" else "브레이크 페달 값이 없어 출발은 움직임으로 판단")
        out.add(
            when (nav.lastZone.source) {
                SchoolZoneJudge.Source.CAUTION -> "어린이보호구역 규칙 켬: 주의 지점의 구역 종류"
                SchoolZoneJudge.Source.PUBLIC_DATA -> "어린이보호구역 규칙 켬: 공공데이터 좌표와 제한속도"
                SchoolZoneJudge.Source.OFF -> "어린이보호구역 규칙 끔: 판정에 쓸 값이 아직 없음"
            }
        )
        return out
    }

    // ------------------------------------------------------------------ 저장 (위치는 저장하지 않습니다)

    private fun loadZones(): List<DoubleArray> = try {
        app.assets.open("zones/school_zones.csv").use {
            SchoolZoneJudge.parseCsv(it.readBytes().toString(Charsets.UTF_8))
        }
    } catch (t: Throwable) {
        emptyList()
    }

    private fun loadState() {
        demoMode = prefs.getBoolean("demo", true)
        engine.level = runCatching { Level.valueOf(prefs.getString("level", null) ?: "") }.getOrDefault(Level.NORMAL)
        for (p in packs) {
            val raw = prefs.getString("relisten_${p.id}", null) ?: continue
            val list = ArrayList<RelistenItem>()
            for (part in raw.split(',')) {
                val bits = part.split(':')
                val idx = bits.getOrNull(0)?.toIntOrNull() ?: continue
                val reason = runCatching { Reason.valueOf(bits.getOrNull(1) ?: "") }.getOrDefault(Reason.LANE_CHANGE)
                if (idx in p.sentences.indices) list.add(RelistenItem(idx, reason))
            }
            if (list.isNotEmpty()) relisten[p.id] = list
        }
        runCatching {
            val o = JSONObject(prefs.getString("today", null) ?: "{}")
            today.date = o.optString("date", "")
            for (r in Reason.values()) {
                val n = o.optInt("p_${r.name}", 0)
                if (n > 0) today.pauses[r] = n
            }
            today.boundary = o.optInt("boundary", 0)
            today.cuts = o.optInt("cuts", 0)
            today.waiting = o.optInt("waiting", 0)
            today.budgetSkips = o.optInt("budgetSkips", 0)
            today.focusPauses = o.optInt("focusPauses", 0)
            today.quietMs = o.optLong("quietMs", 0L)
            today.listenedMs = o.optLong("listenedMs", 0L)
        }
        rollDate()
        val wanted = prefs.getString("pack", null)
        val first = packs.firstOrNull { it.id == wanted && !it.isEmpty } ?: packs.firstOrNull { !it.isEmpty }
        if (first != null) {
            pack = first
            player.load(first, prefs.getInt("bookmark_${first.id}", 0))
        }
    }

    private fun saveRelisten(packId: String) {
        val text = relisten[packId]?.joinToString(",") { "${it.index}:${it.reason.name}" } ?: ""
        prefs.edit().putString("relisten_$packId", text).apply()
    }

    private fun saveState() {
        lastSaveMs = SystemClock.elapsedRealtime()
        val o = JSONObject()
        o.put("date", today.date)
        for ((r, n) in today.pauses) o.put("p_${r.name}", n)
        o.put("boundary", today.boundary)
        o.put("cuts", today.cuts)
        o.put("waiting", today.waiting)
        o.put("budgetSkips", today.budgetSkips)
        o.put("focusPauses", today.focusPauses)
        o.put("quietMs", today.quietMs)
        o.put("listenedMs", today.listenedMs)
        val text = o.toString()
        // 달라진 것이 없으면 쓰지 않습니다.
        if (text == lastSavedToday) return
        lastSavedToday = text
        prefs.edit().putString("today", text).apply()
    }

    companion object {
        private const val TAG = "MissedSentence"
        private const val TICK_MS = 200L
        private const val CAR_POLL_MS = 200L
        private const val MAX_LOG = 40
        private const val SAVE_EVERY_MS = 20_000L
        private const val APP_PACKAGE = "com.missedsentence.car"
        private const val MAIN_SCREEN = "com.missedsentence.car.ui.MainActivity"
        private val RAW_PREFIXES = listOf("TurnSignal.", "Brake.", "SteeringWheel.")
    }
}
