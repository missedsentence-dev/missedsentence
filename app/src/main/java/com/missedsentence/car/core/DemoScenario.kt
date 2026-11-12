package com.missedsentence.car.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 시연용 출근길. 에뮬레이터에서 길안내를 켜지 않고도 장면을 보여 줄 수 있게,
 * 정해 둔 길을 달리는 작은 주행 모형이 속도와 내비 정보를 만들어 냅니다.
 * 만든 값은 실제 NaviHelper 이벤트와 같은 통로([NavSink])로 들어갑니다.
 * 방향지시등, 브레이크, 조향은 Virtual Console에서 넣은 실제 값과 합쳐 씁니다.
 */
class DemoScenario(val route: Route) {

    sealed class Ev {
        /** [atM] 지점의 회전. [stopBeforeM]만큼 앞에서 [stopMs] 동안 섭니다(신호 대기, 보행자 대기). */
        data class Turn(val atM: Float, val type: TurnType, val stopBeforeM: Float = 0f, val stopMs: Long = 0L) : Ev()

        /** 어린이보호구역 구간 */
        data class Zone(val fromM: Float, val toM: Float) : Ev()
    }

    class Route(val id: String, val title: String, val lengthM: Float, val cruiseKmh: Float, val events: List<Ev>)

    data class Frame(
        val speedKmh: Float,
        val brake: Boolean,
        val steeringDeg: Float,
        val turnLeft: Boolean,
        val turnRight: Boolean,
        val nextTurn: NextTurn?,
        val schoolCautionM: Float?,
        val speedLimitKmh: Int,
        val positionM: Float,
        val waitingAtStop: Boolean,
        val finished: Boolean
    )

    private var x = 0f
    private var v = 0f                 // m/s
    private var stopElapsed = 0L
    private val stopsDone = HashSet<Ev.Turn>()
    private var waiting: Ev.Turn? = null

    var finished = false
        private set
    val elapsedMs: Long get() = elapsed
    private var elapsed = 0L

    /** 다음 순간으로 넘어갑니다. [realBrake]는 Virtual Console에서 밟고 있는 브레이크입니다. */
    fun step(dtMs: Long, realBrake: Boolean): Frame {
        val dt = dtMs / 1000f
        elapsed += dtMs
        val cruise = route.cruiseKmh / 3.6f
        var target = cruise
        var braking = false

        // 끝 지점에서 섭니다.
        target = min(target, approachSpeed(0f, route.lengthM - x))

        for (ev in route.events) {
            when (ev) {
                is Ev.Turn -> {
                    // 회전 지점 앞뒤로는 천천히
                    if (x < ev.atM + TURN_AFTER_M) {
                        val toSlow = ev.atM - TURN_BEFORE_M - x
                        target = min(target, if (toSlow > 0) approachSpeed(TURN_MPS, toSlow) else TURN_MPS)
                    }
                    if (ev.stopMs > 0 && ev !in stopsDone) {
                        val toStop = ev.atM - ev.stopBeforeM - x
                        if (toStop <= STOP_EPS_M) {
                            target = 0f
                            waiting = ev
                        } else {
                            target = min(target, approachSpeed(0f, toStop))
                        }
                    }
                }
                is Ev.Zone -> {
                    if (x < ev.toM) {
                        val toZone = ev.fromM - x
                        target = min(target, if (toZone > 0) approachSpeed(ZONE_MPS, toZone) else ZONE_MPS)
                    }
                }
            }
        }

        val w = waiting
        if (w != null) {
            v = 0f
            stopElapsed += dtMs
            // 정해 둔 시간이 지나고 브레이크를 밟고 있지 않으면 출발합니다. 밟고 있으면 최대 60초까지 기다립니다.
            if ((stopElapsed >= w.stopMs && !realBrake) || stopElapsed >= MAX_WAIT_MS) {
                stopsDone.add(w)
                waiting = null
                stopElapsed = 0L
            }
        } else {
            if (target < v - 0.01f) braking = true
            v = if (target >= v) min(target, v + ACCEL * dt) else max(target, v - DECEL * 1.6f * dt)
            x += v * dt
        }
        if (x >= route.lengthM - 0.6f && v < 0.3f) {
            v = 0f
            finished = true
        }

        var steering = 0f
        var left = false
        var right = false
        var next: NextTurn? = null
        for (ev in route.events) {
            if (ev !is Ev.Turn) continue
            val d = ev.atM - x
            if (next == null && d > -PASS_M) next = NextTurn(ev.type, max(0f, d))
            val leftSide = ev.type == TurnType.LEFT || ev.type == TurnType.UTURN || ev.type == TurnType.MERGE
            if (d < SIGNAL_BEFORE_M && d > -SIGNAL_AFTER_M) {
                if (leftSide) left = true else right = true
            }
            if (d < 6f && d > -TURN_AFTER_M) {
                val big = if (ev.type == TurnType.MERGE || ev.type == TurnType.EXIT || ev.type == TurnType.FORK) 25f else 200f
                steering = if (leftSide) -big else big
            }
        }

        var caution: Float? = null
        var limit = OPEN_LIMIT
        for (ev in route.events) {
            if (ev !is Ev.Zone) continue
            if (x >= ev.fromM && x < ev.toM) limit = 30
            val d = ev.fromM - x
            if (d > 0 && d <= CAUTION_AHEAD_M) caution = d
            if (d <= 0 && d > -ZONE_ENTER_REPORT_M) caution = 0f
        }

        return Frame(
            speedKmh = v * 3.6f,
            brake = braking || waiting != null,
            steeringDeg = steering,
            turnLeft = left,
            turnRight = right,
            nextTurn = next,
            schoolCautionM = caution,
            speedLimitKmh = limit,
            positionM = x,
            waitingAtStop = waiting != null,
            finished = finished
        )
    }

    /** [distance] 앞에서 [endSpeed]가 되도록 부드럽게 줄일 때 지금 낼 수 있는 속도 */
    private fun approachSpeed(endSpeed: Float, distance: Float): Float =
        sqrt(max(0f, endSpeed * endSpeed + 2f * DECEL * max(0f, distance)))

    companion object {
        private const val ACCEL = 1.5f
        private const val DECEL = 2.0f
        private const val TURN_MPS = 4.2f          // 회전은 시속 15 안팎
        private const val ZONE_MPS = 7.8f          // 어린이보호구역은 시속 28
        private const val TURN_BEFORE_M = 12f
        private const val TURN_AFTER_M = 8f
        private const val STOP_EPS_M = 0.5f
        private const val PASS_M = 2f
        private const val SIGNAL_BEFORE_M = 45f
        private const val SIGNAL_AFTER_M = 8f
        private const val CAUTION_AHEAD_M = 200f
        private const val ZONE_ENTER_REPORT_M = 15f
        private const val OPEN_LIMIT = 50
        private const val MAX_WAIT_MS = 60_000L

        fun leftTurn() = Route(
            "left", "좌회전과 신호 대기", 300f, 45f,
            listOf(Ev.Turn(180f, TurnType.LEFT, stopBeforeM = 12f, stopMs = 10_000L))
        )

        fun schoolZone() = Route(
            "school", "어린이보호구역", 430f, 45f,
            listOf(Ev.Zone(150f, 330f))
        )

        fun rightTurn() = Route(
            "right", "우회전과 보행자 대기", 260f, 40f,
            listOf(Ev.Turn(150f, TurnType.RIGHT, stopBeforeM = 8f, stopMs = 4_000L))
        )

        /** 세 장면을 30초 넘게 띄워 이어 붙인 출근길. 약 3분 */
        fun commute() = Route(
            "commute", "출근길 전체", 1_500f, 45f,
            listOf(
                Ev.Turn(180f, TurnType.LEFT, stopBeforeM = 12f, stopMs = 10_000L),
                Ev.Zone(700f, 880f),
                Ev.Turn(1_400f, TurnType.RIGHT, stopBeforeM = 8f, stopMs = 4_000L)
            )
        )

        fun all(): List<Route> = listOf(leftTurn(), schoolZone(), rightTurn(), commute())
    }
}
