import com.missedsentence.car.core.Decision
import com.missedsentence.car.core.DemoScenario
import com.missedsentence.car.core.Geo
import com.missedsentence.car.core.NavState
import com.missedsentence.car.core.SchoolZoneJudge
import com.missedsentence.car.core.TurnParsing
import com.missedsentence.car.core.Level
import com.missedsentence.car.core.LoadEngine
import com.missedsentence.car.core.NextTurn
import com.missedsentence.car.core.Reason
import com.missedsentence.car.core.SignalReader
import com.missedsentence.car.core.Signals
import com.missedsentence.car.core.TurnType

/**
 * 멈춤 규칙 엔진 시나리오 검사. 안드로이드 없이 PC에서 돌립니다.
 * 실행 방법은 tools/enginetest/README.md 에 있습니다.
 */

private var passed = 0
private var failed = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) {
        passed++
    } else {
        failed++
        println("  실패: $name $detail")
    }
}

private class Run(level: Level = Level.NORMAL) {
    val engine = LoadEngine(level)
    val log = ArrayList<Pair<Long, Decision>>()
    var t = 0L

    /** [seconds] 동안 0.2초마다 [make]가 만든 입력을 넣습니다. make에는 이 구간이 시작된 뒤 흐른 초가 들어갑니다. */
    fun drive(seconds: Double, make: (Double) -> Signals) {
        val steps = (seconds * 5).toInt()
        for (i in 0 until steps) {
            val s = make(i / 5.0).copy(tMs = t)
            for (d in engine.update(s)) log.add(t to d)
            t += 200
        }
    }

    fun kinds(): String = log.joinToString(" ") { (_, d) ->
        when (d) {
            is Decision.Defer -> "D:${d.reason.name}"
            is Decision.Cut -> "C:${d.reason.name}"
            is Decision.Resume -> if (d.whileWaiting) "W:${d.reason.name}" else "R:${d.reason.name}"
            is Decision.Relisten -> "L:${d.reason.name}"
        }
    }

    fun timeOf(index: Int): Double = log[index].first / 1000.0
}

private fun base(speed: Float, gear: String = "D") = Signals(tMs = 0, speedKmh = speed, gear = gear, brakePedal = false, steeringDeg = 0f)

/** 일정한 속도로 회전 지점에 다가가는 입력. startM에서 시작해 거리가 줄어듭니다. */
private fun approach(type: TurnType, startM: Float, speed: Float): (Double) -> Signals = { sec ->
    val d = startM - (speed / 3.6f) * sec.toFloat()
    base(speed).copy(routeActive = true, nextTurn = NextTurn(type, d.coerceAtLeast(0f)))
}

private fun leftTurnWithSignalWait() {
    println("1. 신호 대기가 있는 좌회전 (경로 모드)")
    val r = Run()
    // 시속 45로 300m 앞 좌회전에 접근: 125m(10초 전)에서 미룸
    r.drive(22.0, approach(TurnType.LEFT, 300f, 45f)) // 22초 뒤 남은 거리 25m
    check("10초 전에 미룸", r.kinds() == "D:COMPLEX_TURN", r.kinds())
    val deferAt = r.timeOf(0)
    check("미룬 시각이 회전 약 10초 전", deferAt in 13.5..14.5, "t=$deferAt")
    check("문장을 기다리는 시간 4초", (r.log[0].second as Decision.Defer).graceMs == 4000L)
    // 정지선에서 12초 대기 (브레이크 밟음)
    r.drive(12.0) { base(0f).copy(routeActive = true, nextTurn = NextTurn(TurnType.LEFT, 25f), brakePedal = true) }
    check("5초 넘게 서 있으면 다시 재생", r.kinds() == "D:COMPLEX_TURN W:COMPLEX_TURN", r.kinds())
    check("다시 재생 시각", r.timeOf(1) - 22.0 in 5.0..5.6, "t=${r.timeOf(1)}")
    // 브레이크를 떼는 순간 끊음 (아직 속도 0)
    r.drive(0.4) { base(0f).copy(routeActive = true, nextTurn = NextTurn(TurnType.LEFT, 25f), brakePedal = false) }
    check("브레이크를 떼는 순간 끊음", r.kinds().endsWith("C:COMPLEX_TURN"), r.kinds())
    // 출발해 회전 (조향 큼), 회전 안내는 다음 지점(800m 앞 직진)으로 넘어감
    r.drive(4.0) { base(15f).copy(routeActive = true, nextTurn = NextTurn(TurnType.LEFT, 10f), steeringDeg = -200f) }
    r.drive(2.0) { base(20f).copy(routeActive = true, nextTurn = NextTurn(TurnType.STRAIGHT, 800f), steeringDeg = -120f) }
    check("회전 중에는 조용", r.log.size == 3, r.kinds())
    r.drive(4.0) { base(35f).copy(routeActive = true, nextTurn = NextTurn(TurnType.STRAIGHT, 780f), steeringDeg = 2f) }
    check("조향이 3초 안정되면 다시 재생", r.kinds() == "D:COMPLEX_TURN W:COMPLEX_TURN C:COMPLEX_TURN R:COMPLEX_TURN", r.kinds())
    check("멈춤 횟수 1", r.engine.stats.total() == 1 && r.engine.stats.waitingResumes == 1)
}

private fun rightTurn() {
    println("2. 우회전 (경로 모드, 보행자 대기 중에도 조용)")
    val r = Run()
    r.drive(13.0, approach(TurnType.RIGHT, 200f, 40f)) // 시속 40에서 6초 전은 약 67m 앞(12초 지점)
    check("6초 전에 미룸", r.kinds() == "D:RIGHT_TURN", r.kinds())
    check("미룬 시각", r.timeOf(0) in 11.8..12.4, "t=${r.timeOf(0)}")
    r.drive(4.0, approach(TurnType.RIGHT, 55f, 40f))
    r.drive(8.0) { base(0f).copy(routeActive = true, nextTurn = NextTurn(TurnType.RIGHT, 8f), brakePedal = true) }
    check("보행자 대기 8초 동안 다시 재생하지 않음", r.kinds() == "D:RIGHT_TURN", r.kinds())
    r.drive(3.0) { base(12f).copy(routeActive = true, nextTurn = NextTurn(TurnType.RIGHT, 3f), steeringDeg = 150f) }
    r.drive(1.0) { base(20f).copy(routeActive = true, nextTurn = NextTurn(TurnType.LEFT, 900f), steeringDeg = 90f) }
    r.drive(3.0) { base(30f).copy(routeActive = true, nextTurn = NextTurn(TurnType.LEFT, 880f), steeringDeg = 0f) }
    check("회전 뒤 조향 안정 2초에 다시 재생", r.kinds() == "D:RIGHT_TURN R:RIGHT_TURN", r.kinds())
    val low = Run(Level.LOW)
    low.drive(13.0, approach(TurnType.RIGHT, 200f, 40f))
    check("낮음 단계에서도 우회전은 6초 전", low.kinds() == "D:RIGHT_TURN" && low.timeOf(0) == r.timeOf(0), "${low.kinds()} ${low.log.firstOrNull()?.first}")
}

private fun schoolZone() {
    println("3. 어린이보호구역")
    val r = Run()
    // 미리 아는 경우: 시속 36(10m/s)로 150m 앞 구역에 접근 → 60m(6초 전)에서 미룸
    r.drive(14.8) { sec -> base(36f).copy(schoolAheadM = (150f - 10f * sec.toFloat()).coerceAtLeast(1f)) }
    check("구역 6초 전에 미룸", r.kinds() == "D:SCHOOL_ZONE", r.kinds())
    check("미룬 시각", r.timeOf(0) in 8.8..9.4, "t=${r.timeOf(0)}")
    // 구역 안에서 90초 주행 (상한이 걸리지 않아야 함), 중간에 신호 대기 20초
    r.drive(40.0) { base(28f).copy(inSchoolZone = true) }
    r.drive(20.0) { base(0f).copy(inSchoolZone = true, brakePedal = true) }
    r.drive(50.0) { base(28f).copy(inSchoolZone = true) }
    check("구역 안에서는 90초를 움직여도 조용", r.kinds() == "D:SCHOOL_ZONE", r.kinds())
    r.drive(1.0) { base(40f) }
    check("구역을 벗어나면 다시 재생", r.kinds() == "D:SCHOOL_ZONE R:SCHOOL_ZONE", r.kinds())

    val r2 = Run(Level.LOW)
    r2.drive(3.0) { base(40f) }
    r2.drive(10.0) { base(28f).copy(inSchoolZone = true) }
    check("진입하는 순간에 알면 바로 끊음 (낮음 단계도 같음)", r2.kinds() == "C:SCHOOL_ZONE" && r2.timeOf(0) == 3.0, r2.kinds())
    r2.drive(1.0) { base(40f) }
    check("벗어나면 다시 재생", r2.kinds() == "C:SCHOOL_ZONE R:SCHOOL_ZONE", r2.kinds())
}

private fun laneChangeBudgetAndSpeedClass() {
    println("4. 차로 변경 예산과 속도로 가르기 (기본 모드)")
    val r = Run()
    fun lane(left: Boolean = true) {
        r.drive(5.0) { base(70f).copy(turnLeft = left, turnRight = !left, steeringDeg = 6f) }
        r.drive(4.0) { base(70f) }
    }
    r.drive(5.0) { base(70f) }
    lane()
    check("첫 차로 변경은 끊고, 꺼지고 2초 뒤 다시 재생", r.kinds() == "C:LANE_CHANGE R:LANE_CHANGE", r.kinds())
    check("다시 재생 시각", r.timeOf(1) - r.timeOf(0) in 6.8..7.4, "${r.timeOf(1) - r.timeOf(0)}")
    r.drive(20.0) { base(70f) }
    lane(false)
    check("90초 안의 두 번째는 멈추지 않고 다시 들을 문장으로", r.kinds().endsWith("L:LANE_CHANGE") && r.log.size == 3, r.kinds())
    r.drive(70.0) { base(70f) }
    lane()
    check("90초가 지난 세 번째는 끊음 (예산 2회)", r.log.size == 5 && r.kinds().endsWith("C:LANE_CHANGE R:LANE_CHANGE"), r.kinds())
    r.drive(100.0) { base(70f) }
    lane()
    check("예산을 다 쓴 뒤에는 다시 들을 문장으로", r.log.size == 6 && r.kinds().endsWith("L:LANE_CHANGE"), r.kinds())
    r.drive(1800.0) { base(70f) }
    lane()
    check("30분이 지나면 예산이 돌아옴", r.kinds().endsWith("C:LANE_CHANGE R:LANE_CHANGE"), r.kinds())
    check("예산 때문에 넘긴 횟수 2", r.engine.stats.budgetSkips == 2)

    val slowLeft = Run()
    slowLeft.drive(4.0) { base(20f) }
    slowLeft.drive(1.0) { base(20f).copy(turnLeft = true) }
    check("시속 25 이하 왼쪽 방향지시등은 복잡한 회전", slowLeft.kinds() == "D:COMPLEX_TURN", slowLeft.kinds())
    val slowRight = Run()
    slowRight.drive(4.0) { base(20f) }
    slowRight.drive(1.0) { base(20f).copy(turnRight = true) }
    check("시속 25 이하 오른쪽 방향지시등은 우회전", slowRight.kinds() == "D:RIGHT_TURN", slowRight.kinds())

    val decel = Run()
    decel.drive(3.0) { base(55f) }
    decel.drive(3.0) { sec -> base(55f - 6f * sec.toFloat()) } // 3초 동안 55 → 37
    decel.drive(1.0) { base(36f).copy(turnRight = true) }
    check("감속 중에 켠 방향지시등은 회전", decel.kinds() == "D:RIGHT_TURN", decel.kinds())

    val mid = Run()
    mid.drive(5.0) { base(32f) }
    mid.drive(2.0) { base(32f).copy(turnLeft = true, steeringDeg = 5f) }
    check("시속 25~40에서 일정하게 달리며 켜면 일단 차로 변경", mid.kinds() == "C:LANE_CHANGE", mid.kinds())
    mid.drive(3.0) { base(28f).copy(turnLeft = true, steeringDeg = -70f) }
    mid.drive(2.0) { base(30f).copy(steeringDeg = -40f) }
    check("크게 꺾으면 회전으로 다시 보고 2초 뒤에 풀지 않음", mid.log.size == 1, mid.kinds())
    mid.drive(4.0) { base(35f).copy(steeringDeg = 0f) }
    check("회전으로 다시 본 뒤에는 조향 안정 3초 뒤 재생", mid.kinds() == "C:LANE_CHANGE R:COMPLEX_TURN", mid.kinds())
    check("회전으로 바뀌면 차로 변경 예산을 돌려줌", mid.engine.stats.count(Reason.LANE_CHANGE) == 0 && mid.engine.stats.count(Reason.COMPLEX_TURN) == 1)

    val late = Run()
    late.drive(5.0) { base(70f) }
    late.drive(5.0) { base(70f).copy(turnLeft = true) }
    late.drive(3.0) { base(70f) }
    late.drive(10.0) { base(70f) }
    late.drive(2.0) { base(70f).copy(turnRight = true, steeringDeg = 3f) }   // 예산 간격 때문에 넘김
    late.drive(2.0) { base(60f).copy(turnRight = true, steeringDeg = 80f) }  // 그런데 크게 꺾음
    check("넘긴 차로 변경이 회전으로 드러나면 그때 끊음", late.kinds() == "C:LANE_CHANGE R:LANE_CHANGE L:LANE_CHANGE C:RIGHT_TURN", late.kinds())

    val again = Run()
    again.drive(5.0) { base(70f) }
    again.drive(5.0) { base(70f).copy(turnLeft = true) }
    again.drive(6.0) { base(70f) }
    again.engine.reset()
    again.engine.resetBudget()
    again.drive(5.0) { base(70f) }
    again.drive(5.0) { base(70f).copy(turnLeft = true) }
    check("예산을 처음으로 돌리면 간격 90초 안이어도 다시 끊음", again.kinds() == "C:LANE_CHANGE R:LANE_CHANGE C:LANE_CHANGE", again.kinds())

    val hazard = Run()
    hazard.drive(3.0) { base(50f) }
    hazard.drive(5.0) { base(50f).copy(turnLeft = true, turnRight = true) }
    check("비상등은 방향지시등으로 보지 않음", hazard.log.isEmpty(), hazard.kinds())
}

private fun hardBrake() {
    println("5. 급제동")
    val r = Run()
    r.drive(5.0) { base(60f) }
    r.drive(1.0) { sec -> base(60f - 22f * sec.toFloat()) } // 1초에 22km/h 감속
    check("급제동이면 바로 끊음", r.kinds() == "C:HARD_BRAKE", r.kinds())
    r.drive(1.0) { base(38f) }
    check("아직 조용", r.log.size == 1)
    r.drive(3.4) { base(38f) }
    check("속도 변화가 3초 잦아들면 다시 재생", r.kinds() == "C:HARD_BRAKE R:HARD_BRAKE", r.kinds())
    val gentle = Run()
    gentle.drive(5.0) { base(60f) }
    gentle.drive(4.0) { sec -> base(60f - 6f * sec.toFloat()) }
    check("부드러운 감속은 반응 없음", gentle.log.isEmpty(), gentle.kinds())
    // 기기가 느려 입력이 2초 넘게 끊겼다가 한꺼번에 들어와도 1초당 감속으로 따집니다.
    val sparse = Run()
    sparse.drive(5.0) { base(40f) }
    sparse.t += 2_200                      // 2.4초 동안 입력이 없다가
    sparse.drive(0.2) { base(22f) }        // 18km/h 느려진 값이 들어옴: 1초당 7.5km/h
    check("입력이 끊겼다 들어온 완만한 감속은 급제동이 아님", sparse.log.isEmpty(), sparse.kinds())
}

private fun merging() {
    println("6. 30초 안 합치기")
    val r = Run()
    r.drive(10.0, approach(TurnType.LEFT, 150f, 45f))
    r.drive(3.0) { base(15f).copy(routeActive = true, nextTurn = NextTurn(TurnType.LEFT, 5f), steeringDeg = -180f) }
    // 좌회전을 지난 뒤 다음 우회전이 250m 앞(시속 45로 20초): 6초 전 미룸까지 14초라 30초 안
    r.drive(16.0, approach(TurnType.RIGHT, 250f, 45f))
    check("다음 멈춤이 30초 안이면 다시 틀지 않음", r.kinds() == "D:COMPLEX_TURN", r.kinds())
    r.drive(4.0) { base(12f).copy(routeActive = true, nextTurn = NextTurn(TurnType.RIGHT, 4f), steeringDeg = 160f) }
    r.drive(4.0) { base(40f).copy(routeActive = true, nextTurn = NextTurn(TurnType.STRAIGHT, 2000f), steeringDeg = 0f) }
    check("두 회전을 한 번의 멈춤으로", r.kinds() == "D:COMPLEX_TURN R:RIGHT_TURN" && r.engine.stats.merged == 1 && r.engine.stats.total() == 1, "${r.kinds()} merged=${r.engine.stats.merged}")

    val far = Run()
    far.drive(10.0, approach(TurnType.LEFT, 150f, 45f))
    far.drive(3.0) { base(15f).copy(routeActive = true, nextTurn = NextTurn(TurnType.LEFT, 5f), steeringDeg = -180f) }
    far.drive(8.0, approach(TurnType.RIGHT, 900f, 45f)) // 72초 앞
    check("멀면 사이에 다시 재생", far.kinds() == "D:COMPLEX_TURN R:COMPLEX_TURN", far.kinds())
    far.drive(70.0, approach(TurnType.RIGHT, 800f, 45f))
    check("그 뒤 우회전에서 다시 미룸", far.kinds() == "D:COMPLEX_TURN R:COMPLEX_TURN D:RIGHT_TURN", far.kinds())

    val zone = Run()
    zone.drive(10.0, approach(TurnType.LEFT, 150f, 45f))
    zone.drive(3.0) { base(15f).copy(routeActive = true, nextTurn = NextTurn(TurnType.LEFT, 5f), steeringDeg = -180f) }
    zone.drive(6.0) { base(28f).copy(inSchoolZone = true) }
    check("좌회전 직후 어린이보호구역이면 이어서 조용", zone.kinds() == "D:COMPLEX_TURN", zone.kinds())
    zone.drive(1.0) { base(40f) }
    check("구역을 벗어나야 다시 재생", zone.kinds() == "D:COMPLEX_TURN R:SCHOOL_ZONE", zone.kinds())
}

private fun lowLevel() {
    println("7. 낮음 단계")
    val normal = Run()
    normal.drive(20.0, approach(TurnType.LEFT, 300f, 45f))
    val low = Run(Level.LOW)
    low.drive(20.0, approach(TurnType.LEFT, 300f, 45f))
    val tn = normal.timeOf(0)
    val tl = low.timeOf(0)
    check("복잡한 회전은 보통 10초 전, 낮음 8초 전", tl - tn in 1.8..2.2, "normal=$tn low=$tl")
    val lane = Run(Level.LOW)
    lane.drive(5.0) { base(70f) }
    lane.drive(5.0) { base(70f).copy(turnLeft = true) }
    lane.drive(4.0) { base(70f) }
    check("낮음 단계의 차로 변경은 멈추지 않고 다시 들을 문장으로", lane.kinds() == "L:LANE_CHANGE", lane.kinds())
}

private fun basicMode() {
    println("8. 기본 모드 (길안내 없음)")
    val r = Run()
    // 빨간불에 서 있다가 왼쪽 방향지시등: 서 있는 동안은 계속 듣고, 출발하는 순간 끊음
    r.drive(8.0) { base(0f).copy(brakePedal = true) }
    r.drive(6.0) { base(0f).copy(brakePedal = true, turnLeft = true) }
    check("서 있는 동안 켠 방향지시등은 미뤘다가 바로 다시 재생", r.kinds() == "D:COMPLEX_TURN W:COMPLEX_TURN", r.kinds())
    check("다시 재생이 바로 이어짐", r.timeOf(1) - r.timeOf(0) <= 0.4, "${r.timeOf(1) - r.timeOf(0)}")
    r.drive(0.4) { base(0f).copy(brakePedal = false, turnLeft = true) }
    check("출발하는 순간 끊음", r.kinds().endsWith("C:COMPLEX_TURN"), r.kinds())
    r.drive(5.0) { base(18f).copy(turnLeft = true, steeringDeg = -220f) }
    r.drive(2.0) { base(25f).copy(steeringDeg = -60f) }
    r.drive(3.4) { base(35f).copy(steeringDeg = 0f) }
    check("방향지시등이 꺼지고 조향 안정 3초 뒤 재생", r.kinds() == "D:COMPLEX_TURN W:COMPLEX_TURN C:COMPLEX_TURN R:COMPLEX_TURN", r.kinds())

    val right = Run()
    right.drive(5.0) { base(18f) }
    right.drive(3.0) { base(15f).copy(turnRight = true, steeringDeg = 5f) }
    right.drive(6.0) { base(0f).copy(turnRight = true, brakePedal = true) } // 보행자 대기
    check("우회전은 서 있어도 조용", right.kinds() == "D:RIGHT_TURN", right.kinds())
    check("방향지시등으로 안 회전은 문장을 1.5초만 기다림", (right.log[0].second as Decision.Defer).graceMs == 1500L)
    right.drive(4.0) { base(15f).copy(turnRight = true, steeringDeg = 170f) }
    right.drive(2.4) { base(25f).copy(steeringDeg = 3f) }
    check("꺼지고 조향 안정 2초 뒤 재생", right.kinds() == "D:RIGHT_TURN R:RIGHT_TURN", right.kinds())

    val unknown = Run()
    unknown.drive(3.0) { Signals(tMs = 0, gear = "D") }
    unknown.drive(2.0) { Signals(tMs = 0, gear = "D", turnLeft = true) }
    check("속도를 모르면 회전으로 봄", unknown.kinds() == "D:COMPLEX_TURN", unknown.kinds())
    unknown.drive(4.0) { Signals(tMs = 0, gear = "D") }
    check("조향을 몰라도 꺼지고 3초 뒤 재생", unknown.kinds() == "D:COMPLEX_TURN R:COMPLEX_TURN", unknown.kinds())
}

private fun capAndParking() {
    println("9. 60초 상한과 정차")
    val r = Run()
    r.drive(10.0, approach(TurnType.LEFT, 150f, 45f))
    // 회전 지점 앞에서 시속 8로 80초 동안 기어감 (정체)
    r.drive(80.0) { base(8f).copy(routeActive = true, nextTurn = NextTurn(TurnType.LEFT, 20f)) }
    check("움직인 시간 60초에서 풀림", r.kinds() == "D:COMPLEX_TURN R:COMPLEX_TURN", r.kinds())
    check("풀린 시각", r.timeOf(1) - r.timeOf(0) in 59.0..62.0, "${r.timeOf(1) - r.timeOf(0)}")
    r.drive(20.0) { base(8f).copy(routeActive = true, nextTurn = NextTurn(TurnType.LEFT, 15f)) }
    check("같은 회전으로 다시 멈추지 않음", r.log.size == 2, r.kinds())

    val waitNotCounted = Run()
    waitNotCounted.drive(13.0, approach(TurnType.RIGHT, 200f, 40f))
    waitNotCounted.drive(100.0) { base(0f).copy(routeActive = true, nextTurn = NextTurn(TurnType.RIGHT, 8f), brakePedal = true) }
    check("서 있는 시간은 상한에 넣지 않음", waitNotCounted.kinds() == "D:RIGHT_TURN", waitNotCounted.kinds())

    val park = Run()
    park.drive(3.0) { base(40f) }
    park.drive(3.0) { base(28f).copy(inSchoolZone = true) }
    park.drive(1.0) { base(0f, gear = "P").copy(inSchoolZone = true) }
    check("기어 P에서는 풀어 줌", park.kinds() == "C:SCHOOL_ZONE R:SCHOOL_ZONE", park.kinds())
    park.drive(5.0) { base(0f, gear = "P").copy(turnLeft = true) }
    check("정차 중에는 방향지시등에 반응하지 않음", park.log.size == 2, park.kinds())
}


/** 시연 출근길을 실제 앱과 같은 순서(주행 모형 → 내비 통로 → 엔진)로 돌려 결정 순서를 봅니다. */
private fun runDemo(route: DemoScenario.Route, level: Level = Level.NORMAL): Triple<String, Double, LoadEngine.Stats> {
    val scenario = DemoScenario(route)
    val nav = NavState(SchoolZoneJudge())
    val r = Run(level)
    var t = 0L
    while (!scenario.finished && t < 600_000L) {
        val f = scenario.step(200, realBrake = false)
        nav.onNextTurn(f.nextTurn, t)
        if (f.schoolCautionM != null) nav.onSchoolCaution(f.schoolCautionM, t)
        nav.onSpeedLimit(f.speedLimitKmh, t)
        val zone = nav.zone(t)
        val s = Signals(
            tMs = t, speedKmh = f.speedKmh, gear = "D",
            turnLeft = f.turnLeft, turnRight = f.turnRight, brakePedal = f.brake, steeringDeg = f.steeringDeg,
            routeActive = nav.routeActive(t), nextTurn = nav.nextTurn(t),
            inSchoolZone = zone.inZone, schoolAheadM = zone.aheadM
        )
        for (d in r.engine.update(s)) r.log.add(t to d)
        t += 200
    }
    return Triple(r.kinds() + " @" + r.log.joinToString(",") { "%.0f".format(it.first / 1000.0) }, t / 1000.0, r.engine.stats)
}

private fun demoScenes() {
    println("10. 시연 출근길 (주행 모형, 내비 통로, 엔진을 이어서)")
    val (left, leftSec, _) = runDemo(DemoScenario.leftTurn())
    check("좌회전 장면: 미룸, 대기 중 재생, 출발 때 끊음, 회전 뒤 재생", left.startsWith("D:COMPLEX_TURN W:COMPLEX_TURN C:COMPLEX_TURN R:COMPLEX_TURN @"), left)
    check("좌회전 장면 길이 40~70초", leftSec in 40.0..70.0, "sec=$leftSec")
    val (school, schoolSec, _) = runDemo(DemoScenario.schoolZone())
    check("어린이보호구역 장면: 진입 전 미룸, 벗어나면 재생", school.startsWith("D:SCHOOL_ZONE R:SCHOOL_ZONE @"), school)
    check("어린이보호구역 장면 길이 35~65초", schoolSec in 35.0..65.0, "sec=$schoolSec")
    val (right, rightSec, _) = runDemo(DemoScenario.rightTurn())
    check("우회전 장면: 미룸, 대기 중에도 조용, 회전 뒤 재생", right.startsWith("D:RIGHT_TURN R:RIGHT_TURN @"), right)
    check("우회전 장면 길이 25~50초", rightSec in 25.0..50.0, "sec=$rightSec")
    val (all, allSec, stats) = runDemo(DemoScenario.commute())
    check(
        "출근길 전체: 세 장면이 합쳐지지 않고 차례로",
        all.startsWith("D:COMPLEX_TURN W:COMPLEX_TURN C:COMPLEX_TURN R:COMPLEX_TURN D:SCHOOL_ZONE R:SCHOOL_ZONE D:RIGHT_TURN R:RIGHT_TURN @") && stats.merged == 0,
        "$all merged=${stats.merged}"
    )
    check("출근길 전체 길이 2분 30초~3분 30초", allSec in 150.0..210.0, "sec=$allSec")
    println("   좌회전 ${leftSec}초: $left")
    println("   어린이보호구역 ${schoolSec}초: $school")
    println("   우회전 ${rightSec}초: $right")
    println("   출근길 전체 ${allSec}초: $all")
    val (low, _, _) = runDemo(DemoScenario.commute(), Level.LOW)
    check("낮음 단계에서도 보행자 순간은 그대로", low.contains("D:SCHOOL_ZONE R:SCHOOL_ZONE D:RIGHT_TURN R:RIGHT_TURN"), low)
}

private fun parsingAndZones() {
    println("11. 회전 안내 원문 읽기와 어린이보호구역 판정")
    check("LEFT", TurnParsing.typeFrom("TURN_LEFT") == TurnType.LEFT)
    check("우회전", TurnParsing.typeFrom("우회전") == TurnType.RIGHT)
    check("U_TURN", TurnParsing.typeFrom("u-turn left") == TurnType.UTURN)
    check("EXIT_RIGHT는 진출", TurnParsing.typeFrom("EXIT_RIGHT") == TurnType.EXIT)
    check("KEEP_LEFT는 분기", TurnParsing.typeFrom("KEEP_LEFT") == TurnType.FORK)
    check("STRAIGHT는 직진", TurnParsing.typeFrom("GO_STRAIGHT") == TurnType.STRAIGHT)
    check("모르는 값", TurnParsing.typeFrom("17") == TurnType.OTHER && TurnParsing.typeFrom(null) == TurnType.OTHER)
    check("어린이보호구역 낱말", TurnParsing.isSchoolZone("SCHOOL_ZONE") && TurnParsing.isSchoolZone("어린이 보호구역") && !TurnParsing.isSchoolZone("급커브") && !TurnParsing.isSchoolZone("노인 보호구역"))

    // 회전 종류를 모르는 안내는 방향지시등 방향으로
    val r = Run()
    r.drive(5.0) { sec -> base(40f).copy(routeActive = true, nextTurn = NextTurn(TurnType.OTHER, 200f - 11f * sec.toFloat())) }
    check("종류를 모르면 거리만으로는 멈추지 않음", r.log.isEmpty(), r.kinds())
    r.drive(2.0) { base(40f).copy(routeActive = true, nextTurn = NextTurn(TurnType.OTHER, 120f), turnRight = true) }
    check("가까운 회전 안내와 오른쪽 방향지시등이면 우회전", r.kinds() == "D:RIGHT_TURN", r.kinds())

    val judge = SchoolZoneJudge()
    check("판정 수단이 없으면 끔", judge.judge(0, 30, null, null).source == SchoolZoneJudge.Source.OFF && !judge.judge(0, 30, null, null).inZone)
    judge.onSchoolCaution(120f, 1_000)
    val ahead = judge.judge(1_200, 50, null, null)
    check("구역 종류 값이 오면 남은 거리를 알려 줌", !ahead.inZone && ahead.aheadM == 120f && ahead.source == SchoolZoneJudge.Source.CAUTION)
    judge.onSchoolCaution(10f, 9_000)
    check("20m 안이면 진입", judge.judge(9_200, 30, null, null).inZone)
    check("소식이 끊겨도 제한속도 30이면 구역 안", judge.judge(40_000, 30, null, null).inZone)
    check("제한속도가 30을 넘으면 벗어남", !judge.judge(41_000, 50, null, null).inZone)
    val noLimit = SchoolZoneJudge()
    noLimit.onSchoolCaution(5f, 0)
    check("제한속도를 모르면 45초 뒤 벗어난 것으로", noLimit.judge(1_000, null, null, null).inZone && !noLimit.judge(47_000, null, null, null).inZone)

    val csv = "시설명,위도,경도\n가나초등학교,37.2800,127.0450\n다라유치원,37.5000,127.1000\n"
    val points = SchoolZoneJudge.parseCsv(csv)
    check("공공데이터 CSV 읽기", points.size == 2 && points[0][0] == 37.28)
    val pub = SchoolZoneJudge(points)
    val d = Geo.distanceM(37.2800, 127.0450, 37.2815, 127.0450)
    check("거리 계산 약 167m", d in 160.0..175.0, "d=$d")
    check("300m 안이고 제한속도 30이면 구역", pub.judge(0, 30, 37.2815, 127.0450).inZone)
    check("300m 안이어도 제한속도 50이면 아님", !pub.judge(0, 50, 37.2815, 127.0450).inZone)
    check("제한속도 30이어도 멀면 아님", !pub.judge(0, 30, 37.2900, 127.0450).inZone)
    check("위치를 모르면 아님", !pub.judge(0, 30, null, null).inZone && pub.judge(0, 30, null, null).source == SchoolZoneJudge.Source.PUBLIC_DATA)
}

private fun signalReading() {
    println("12. Vehicle SDK 값 읽기")
    val a = SignalReader.turn(mapOf("TurnSignal.getCurrentTurnSignalState" to "LEFT"))
    check("한 값에 LEFT", a.left && !a.right && a.known)
    val b = SignalReader.turn(mapOf("TurnSignal.getCurrentTurnSignalState" to "TURN_SIGNAL_OFF"))
    check("OFF", !b.left && !b.right && b.known)
    val c = SignalReader.turn(mapOf("TurnSignal.isLeftTurnSignalOn" to "false", "TurnSignal.isRightTurnSignalOn" to "true"))
    check("좌우가 따로 있는 함수", !c.left && c.right)
    val d = SignalReader.turn(mapOf("TurnSignal.getCurrentTurnSignalState" to "2"))
    check("숫자 표기 2는 왼쪽", d.left && !d.right)
    val e = SignalReader.turn(mapOf("TurnSignal.getCurrentTurnSignalState" to "HAZARD"))
    check("비상등은 양쪽", e.left && e.right)
    check("값이 없으면 모름", !SignalReader.turn(emptyMap()).known)
    check("브레이크 페달 true", SignalReader.brakePedal(mapOf("Brake.getCurrentBrakePedalPressed" to "true")) == true)
    check("브레이크 페달 NOT_PRESSED", SignalReader.brakePedal(mapOf("Brake.getCurrentBrakePedalState" to "NOT_PRESSED")) == false)
    check("브레이크 페달 위치 0", SignalReader.brakePedal(mapOf("Brake.getCurrentBrakePedalPosition" to "0")) == false)
    check("주차 브레이크는 페달이 아님", SignalReader.brakePedal(mapOf("Brake.getCurrentParkingBrakeState" to "ON")) == null)
    check("브레이크 값이 없으면 null", SignalReader.brakePedal(emptyMap()) == null)
    check("조향각", SignalReader.steeringDeg(mapOf("SteeringWheel.getCurrentSteeringAngle" to "-35.5")) == -35.5f)
    check("핸들 열선은 조향각이 아님", SignalReader.steeringDeg(mapOf("SteeringWheel.getCurrentSteeringWheelHeating" to "1")) == null)
}

fun main() {
    leftTurnWithSignalWait()
    rightTurn()
    schoolZone()
    laneChangeBudgetAndSpeedClass()
    hardBrake()
    merging()
    lowLevel()
    basicMode()
    capAndParking()
    demoScenes()
    parsingAndZones()
    signalReading()
    println("엔진 시나리오 검사: 통과 $passed, 실패 $failed")
    if (failed > 0) System.exit(1)
}
