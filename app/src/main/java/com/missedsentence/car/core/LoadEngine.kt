package com.missedsentence.car.core

import java.util.ArrayDeque
import java.util.EnumMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 내비 회전 안내의 종류. 우회전은 보행자 규칙, 나머지 회전류는 복잡한 회전 규칙을 따릅니다. */
enum class TurnType { LEFT, RIGHT, UTURN, MERGE, EXIT, FORK, STRAIGHT, OTHER }

/** 말소리를 미루거나 끊는 이유 */
enum class Reason(val label: String) {
    SCHOOL_ZONE("어린이보호구역"),
    RIGHT_TURN("우회전"),
    COMPLEX_TURN("좌회전, 유턴, 합류"),
    LANE_CHANGE("차로 변경"),
    HARD_BRAKE("급제동")
}

/**
 * 민감도. 바뀌는 것은 복잡한 회전을 얼마나 미리 아는지와 차로 변경 예산뿐입니다.
 * 보행자와 만나는 순간(우회전, 어린이보호구역)은 모든 단계에서 같습니다.
 */
enum class Level(val label: String, val complexLeadMs: Long, val laneBudget: Int) {
    NORMAL("보통", 10_000L, 2),
    LOW("낮음", 8_000L, 0)
}

data class NextTurn(val type: TurnType, val distanceM: Float)

/** 한 번의 판단에 쓰는 입력. 모르는 값은 null로 둡니다. */
data class Signals(
    val tMs: Long,
    val speedKmh: Float? = null,
    val gear: String? = null,
    val turnLeft: Boolean = false,
    val turnRight: Boolean = false,
    val brakePedal: Boolean? = null,
    val steeringDeg: Float? = null,
    /** 차량 기본 내비가 길안내 중이어서 회전 안내를 받고 있는지 */
    val routeActive: Boolean = false,
    val nextTurn: NextTurn? = null,
    val inSchoolZone: Boolean = false,
    /** 어린이보호구역 시작까지 남은 거리. 미리 알 수 없으면 null */
    val schoolAheadM: Float? = null
)

sealed class Decision {
    /** 지금 문장이 [graceMs] 안에 끝나면 끝까지 듣고 다음 문장을 미룹니다. 안 끝나면 바로 끊습니다. */
    data class Defer(val reason: Reason, val graceMs: Long) : Decision()

    /** 바로 끊습니다. 다시 재생할 때는 그 문장 처음부터입니다. */
    data class Cut(val reason: Reason) : Decision()

    /** 다시 재생합니다. [whileWaiting]이면 신호 대기 중 재생이라 출발하는 순간 다시 끊깁니다. */
    data class Resume(val reason: Reason, val whileWaiting: Boolean) : Decision()

    /** 예산 때문에 멈추지 않았습니다. 지금 문장을 다시 들을 문장에 남깁니다. */
    data class Relisten(val reason: Reason) : Decision()
}

/**
 * 멈춤 규칙 엔진. 안드로이드에 기대지 않는 순수 Kotlin이라 PC에서 시나리오로 검증합니다.
 *
 * 원칙: 보행자와 만나는 순간은 항상, 차와 만나는 복잡한 순간은 미리, 차로 변경은 예산 안에서.
 * 예측한 순간은 문장 사이에서 미루고, 예측할 수 없는 순간만 문장 중간에서 끊습니다.
 */
class LoadEngine(var level: Level = Level.NORMAL) {

    class Stats {
        val pauses: EnumMap<Reason, Int> = EnumMap(Reason::class.java)
        var merged = 0
        var budgetSkips = 0
        var waitingResumes = 0
        fun total(): Int = pauses.values.sum()
        fun count(r: Reason): Int = pauses[r] ?: 0
    }

    private enum class Side { LEFT, RIGHT }

    private class Hold(var reason: Reason, val startMs: Long, val routeBased: Boolean) {
        var movingMs = 0L
        var waitingPlay = false
        var waitArmed = true   // 출발하며 끊은 뒤에는 다시 움직였다가 서야 대기 재생을 허용합니다.
        var turnSeq = -1
        var stableSinceMs = -1L
        var side: Side? = null
        var enteredZone = false
    }

    private class SignalWatch(val side: Side, val onsetMs: Long, val onsetSpeed: Float?, val decelAtOnset: Boolean) {
        var consumed = false
        var asLane = false
        var offAtMs = -1L
    }

    val stats = Stats()

    private var hold: Hold? = null
    private var watch: SignalWatch? = null
    private var lastT = -1L
    private var prevLeft = false
    private var prevRight = false
    private var prevBrake: Boolean? = null
    private val speedHist = ArrayDeque<LongArray>() // [tMs, speed x100]
    private var stoppedMs = 0L
    private var calmMs = 0L
    private val lanePauses = ArrayDeque<Long>()

    // 지금 안내 중인 회전을 가려내기 위한 추적 값
    private var trackType: TurnType? = null
    private var trackMinDist = Float.MAX_VALUE
    private var trackSeq = 0
    private var trackHandled = false
    private var zoneSuppressed = false

    /** 지금 엔진이 말소리를 붙잡고 있는 이유. 없으면 null */
    val holdReason: Reason? get() = hold?.reason

    /** 신호 대기 중이라 잠시 다시 재생하고 있는 상태인지 */
    val waitingPlay: Boolean get() = hold?.waitingPlay == true

    fun reset() {
        hold = null
        watch = null
        lastT = -1L
        prevLeft = false
        prevRight = false
        prevBrake = null
        speedHist.clear()
        stoppedMs = 0L
        calmMs = 0L
        trackType = null
        trackMinDist = Float.MAX_VALUE
        trackHandled = false
        zoneSuppressed = false
    }

    /** 차로 변경 예산을 처음 상태로 돌립니다. 시연 장면을 다시 찍을 때 씁니다. */
    fun resetBudget() {
        lanePauses.clear()
    }

    /** 붙잡고 있던 말소리를 풀어 줍니다. 엔진을 끄거나 정차했을 때 부릅니다. */
    fun release(): List<Decision> {
        val h = hold ?: return emptyList()
        hold = null
        watch?.consumed = true
        return if (h.waitingPlay) emptyList() else listOf(Decision.Resume(h.reason, false))
    }

    fun update(s: Signals): List<Decision> {
        val out = ArrayList<Decision>(2)
        val dt = if (lastT < 0) 0L else (s.tMs - lastT).coerceIn(0L, 2_000L)
        lastT = s.tMs
        trackSpeed(s, dt)
        trackTurn(s)

        val left = s.turnLeft && !s.turnRight   // 양쪽이 다 켜진 비상등은 방향지시등으로 보지 않습니다.
        val right = s.turnRight && !s.turnLeft
        val rising = when {
            left && !prevLeft -> Side.LEFT
            right && !prevRight -> Side.RIGHT
            else -> null
        }
        if (rising != null) watch = SignalWatch(rising, s.tMs, s.speedKmh, decelerating(s))
        watch?.let { w ->
            val on = if (w.side == Side.LEFT) left else right
            if (on) w.offAtMs = -1L else if (w.offAtMs < 0) w.offAtMs = s.tMs
        }
        val brakeReleased = prevBrake == true && s.brakePedal == false
        prevLeft = left
        prevRight = right
        prevBrake = s.brakePedal ?: prevBrake
        if (!s.inSchoolZone && s.schoolAheadM == null) zoneSuppressed = false

        if (s.gear == "P") {
            out.addAll(release())
            watch = null
            return out
        }

        val h = hold
        if (h != null) evaluateHold(h, s, dt, brakeReleased, out) else evaluateTriggers(s, out)

        // 꺼진 지 오래된 방향지시등은 잊습니다.
        watch?.let { w -> if (w.offAtMs >= 0 && s.tMs - w.offAtMs > 15_000L && hold?.side == null) watch = null }
        return out
    }

    // ------------------------------------------------------------------ 새로 멈출지 판단

    private fun evaluateTriggers(s: Signals, out: MutableList<Decision>) {
        // 1. 보행자: 어린이보호구역
        if (!zoneSuppressed) {
            if (s.inSchoolZone) {
                start(Hold(Reason.SCHOOL_ZONE, s.tMs, false).apply { enteredZone = true })
                out.add(Decision.Cut(Reason.SCHOOL_ZONE))
                return
            }
            val ahead = s.schoolAheadM
            if (ahead != null) {
                val eta = etaMs(ahead, s.speedKmh)
                if (eta <= PEDESTRIAN_LEAD_MS) {
                    start(Hold(Reason.SCHOOL_ZONE, s.tMs, false))
                    out.add(Decision.Defer(Reason.SCHOOL_ZONE, grace(eta, 1_000L)))
                    return
                }
            }
        }
        // 2. 안전 반응: 급제동
        if (hardBrake(s)) {
            start(Hold(Reason.HARD_BRAKE, s.tMs, false))
            out.add(Decision.Cut(Reason.HARD_BRAKE))
            return
        }
        // 3. 내비 회전 안내로 미리 아는 회전
        val turn = s.nextTurn
        val w = watch
        if (s.routeActive && turn != null && !trackHandled) {
            val signalNear = w != null && !w.consumed && w.offAtMs < 0 &&
                turn.distanceM <= SIGNAL_NEAR_TURN_M && sideMatches(w.side, turn.type)
            // 회전 종류를 모르는 안내는 남은 거리와 방향지시등 방향으로 판단합니다.
            val reason = turnReason(turn.type)
                ?: if (turn.type == TurnType.OTHER && signalNear && w != null) sideReason(w.side) else null
            if (reason != null) {
                val lead = if (reason == Reason.RIGHT_TURN) PEDESTRIAN_LEAD_MS else level.complexLeadMs
                val eta = if (turn.type == TurnType.OTHER) Long.MAX_VALUE / 4 else etaMs(turn.distanceM, s.speedKmh)
                if (eta <= lead || signalNear) {
                    trackHandled = true
                    if (signalNear) w?.consumed = true
                    start(Hold(reason, s.tMs, true).apply { turnSeq = trackSeq })
                    out.add(Decision.Defer(reason, if (eta <= lead) grace(eta, 2_000L) else BASIC_GRACE_MS))
                    return
                }
            }
        }
        // 4. 방향지시등만으로 판단하는 순간
        if (w != null && w.offAtMs < 0) {
            if (!w.consumed) {
                w.consumed = true
                val v = w.onsetSpeed
                val isTurn = v == null || v <= TURN_SPEED_KMH || (w.decelAtOnset && v < LANE_SPEED_KMH)
                if (isTurn) {
                    val reason = sideReason(w.side)
                    start(Hold(reason, s.tMs, false).apply { side = w.side })
                    out.add(Decision.Defer(reason, BASIC_GRACE_MS))
                } else {
                    w.asLane = true
                    if (laneBudgetOk(s.tMs)) {
                        lanePauses.addLast(s.tMs)
                        start(Hold(Reason.LANE_CHANGE, s.tMs, false).apply { side = w.side })
                        out.add(Decision.Cut(Reason.LANE_CHANGE))
                    } else {
                        stats.budgetSkips++
                        out.add(Decision.Relisten(Reason.LANE_CHANGE))
                    }
                }
                return
            }
            // 차로 변경으로 봤는데 10초 안에 크게 꺾거나 느려지면 회전으로 다시 봅니다.
            if (w.asLane && looksLikeTurn(w, s)) {
                w.asLane = false
                val reason = sideReason(w.side)
                start(Hold(reason, s.tMs, false).apply { side = w.side })
                out.add(Decision.Cut(reason))
            }
        }
    }

    private fun start(h: Hold) {
        hold = h
        stats.pauses[h.reason] = stats.count(h.reason) + 1
    }

    // ------------------------------------------------------------------ 멈춘 뒤 다시 재생할지 판단

    private fun evaluateHold(h: Hold, s: Signals, dt: Long, brakeReleased: Boolean, out: MutableList<Decision>) {
        val moving = (s.speedKmh ?: 0f) > MOVING_KMH
        if (moving) h.movingMs += dt
        watch?.let { w -> if (w.offAtMs < 0) w.consumed = true }

        // 차로 변경으로 멈췄는데 회전으로 드러난 경우
        if (h.reason == Reason.LANE_CHANGE) {
            val w = watch
            if (w != null && w.asLane && w.offAtMs < 0 && looksLikeTurn(w, s)) {
                w.asLane = false
                stats.pauses[Reason.LANE_CHANGE] = max(0, stats.count(Reason.LANE_CHANGE) - 1)
                if (lanePauses.isNotEmpty()) lanePauses.removeLast() // 예산을 돌려줍니다.
                h.reason = sideReason(w.side)
                stats.pauses[h.reason] = stats.count(h.reason) + 1
            }
        }

        var done = false
        when (h.reason) {
            Reason.SCHOOL_ZONE -> {
                if (s.inSchoolZone) h.enteredZone = true
                done = if (h.enteredZone) !s.inSchoolZone
                else s.schoolAheadM == null && s.tMs - h.startMs > ZONE_FALSE_ALARM_MS
            }
            Reason.HARD_BRAKE -> done = calmMs >= CALM_MS
            Reason.LANE_CHANGE -> {
                val w = watch
                done = w == null || (w.offAtMs >= 0 && s.tMs - w.offAtMs >= LANE_RESUME_MS)
            }
            Reason.RIGHT_TURN, Reason.COMPLEX_TURN -> {
                val passed = if (h.routeBased) {
                    !s.routeActive || s.nextTurn == null || trackSeq != h.turnSeq
                } else {
                    val w = watch
                    w == null || w.side != h.side || w.offAtMs >= 0
                }
                if (!passed) {
                    h.stableSinceMs = -1L
                    if (h.reason == Reason.COMPLEX_TURN) {
                        // 신호 대기: 5초 넘게 서 있으면 다시 재생하고, 출발하는 순간 끊습니다.
                        if (moving) h.waitArmed = true
                        if (!h.waitingPlay && h.waitArmed && stoppedMs > WAIT_RESUME_MS) {
                            h.waitingPlay = true
                            stats.waitingResumes++
                            out.add(Decision.Resume(h.reason, true))
                        } else if (h.waitingPlay && (brakeReleased || moving)) {
                            h.waitingPlay = false
                            h.waitArmed = false
                            out.add(Decision.Cut(h.reason))
                        }
                    }
                } else {
                    if (h.waitingPlay) {
                        // 서서 듣던 중에 회전 안내가 사라졌으면 그대로 이어 듣습니다.
                        hold = null
                        return
                    }
                    val steerOk = s.steeringDeg == null || abs(s.steeringDeg) <= STEER_STABLE_DEG
                    if (steerOk) {
                        if (h.stableSinceMs < 0) h.stableSinceMs = s.tMs
                    } else {
                        h.stableSinceMs = -1L
                    }
                    val need = if (h.reason == Reason.RIGHT_TURN) RIGHT_STABLE_MS else COMPLEX_STABLE_MS
                    done = h.stableSinceMs >= 0 && s.tMs - h.stableSinceMs >= need
                }
            }
        }

        // 확인된 어린이보호구역이 아니면 움직인 시간 60초에서 풉니다.
        var capped = false
        if (!done && h.reason != Reason.SCHOOL_ZONE && h.movingMs >= MAX_MOVING_MS) {
            done = true
            capped = true
        }
        if (!done) return

        if (h.waitingPlay) {
            hold = null
            return
        }
        if (!capped && tryMerge(h, s)) return
        if (h.reason == Reason.SCHOOL_ZONE && s.inSchoolZone) zoneSuppressed = true
        hold = null
        out.add(Decision.Resume(h.reason, false))
    }

    /** 다음 멈춤이 30초 안에 시작될 것이면 다시 틀지 않고 이어서 멈춥니다. */
    private fun tryMerge(h: Hold, s: Signals): Boolean {
        var next: Hold? = null
        if (!zoneSuppressed && h.reason != Reason.SCHOOL_ZONE) {
            if (s.inSchoolZone) {
                next = Hold(Reason.SCHOOL_ZONE, s.tMs, false).apply { enteredZone = true }
            } else {
                val ahead = s.schoolAheadM
                if (ahead != null && etaMs(ahead, s.speedKmh) <= PEDESTRIAN_LEAD_MS + MERGE_WINDOW_MS) {
                    next = Hold(Reason.SCHOOL_ZONE, s.tMs, false)
                }
            }
        }
        val turn = s.nextTurn
        if (next == null && s.routeActive && turn != null && !trackHandled) {
            val reason = turnReason(turn.type)
            if (reason != null) {
                val lead = if (reason == Reason.RIGHT_TURN) PEDESTRIAN_LEAD_MS else level.complexLeadMs
                if (etaMs(turn.distanceM, s.speedKmh) <= lead + MERGE_WINDOW_MS) {
                    trackHandled = true
                    next = Hold(reason, s.tMs, true).apply { turnSeq = trackSeq }
                }
            }
        }
        if (next == null) return false
        next.movingMs = h.movingMs
        hold = next
        stats.merged++
        return true
    }

    // ------------------------------------------------------------------ 도우미

    private fun turnReason(type: TurnType): Reason? = when (type) {
        TurnType.RIGHT -> Reason.RIGHT_TURN
        TurnType.LEFT, TurnType.UTURN, TurnType.MERGE, TurnType.EXIT, TurnType.FORK -> Reason.COMPLEX_TURN
        else -> null
    }

    private fun sideReason(side: Side): Reason =
        if (side == Side.RIGHT) Reason.RIGHT_TURN else Reason.COMPLEX_TURN

    private fun sideMatches(side: Side, type: TurnType): Boolean = when (type) {
        TurnType.LEFT, TurnType.UTURN -> side == Side.LEFT
        TurnType.RIGHT -> side == Side.RIGHT
        else -> true
    }

    private fun looksLikeTurn(w: SignalWatch, s: Signals): Boolean {
        if (s.tMs - w.onsetMs > RECLASSIFY_MS) return false
        val steer = s.steeringDeg
        val speed = s.speedKmh
        return (steer != null && abs(steer) >= TURN_STEER_DEG) || (speed != null && speed <= TURN_SPEED_KMH)
    }

    private fun laneBudgetOk(now: Long): Boolean {
        while (lanePauses.isNotEmpty() && now - lanePauses.first() > BUDGET_WINDOW_MS) lanePauses.removeFirst()
        if (lanePauses.size >= level.laneBudget) return false
        val last = lanePauses.peekLast() ?: return true
        return now - last >= LANE_GAP_MS
    }

    private fun grace(etaMs: Long, keepMs: Long): Long = min(MAX_GRACE_MS, max(0L, etaMs - keepMs))

    private fun etaMs(distanceM: Float, speedKmh: Float?): Long {
        if (speedKmh == null || speedKmh < 5f) return if (distanceM <= NEAR_M) 0L else Long.MAX_VALUE / 4
        return (distanceM / (speedKmh / 3.6f) * 1000f).toLong()
    }

    private fun trackTurn(s: Signals) {
        val turn = if (s.routeActive) s.nextTurn else null
        if (turn == null) {
            if (trackType != null) {
                trackType = null
                trackSeq++
            }
            trackMinDist = Float.MAX_VALUE
            trackHandled = false
            return
        }
        if (turn.type != trackType || turn.distanceM > trackMinDist + NEW_TURN_JUMP_M) {
            trackType = turn.type
            trackMinDist = turn.distanceM
            trackSeq++
            trackHandled = false
        } else {
            trackMinDist = min(trackMinDist, turn.distanceM)
        }
    }

    private fun trackSpeed(s: Signals, dt: Long) {
        val v = s.speedKmh
        if (v == null) {
            stoppedMs = 0L
            calmMs += dt
            return
        }
        speedHist.addLast(longArrayOf(s.tMs, (v * 100f).toLong()))
        while (speedHist.size > 2 && s.tMs - speedHist.first()[0] > 4_000L) speedHist.removeFirst()
        stoppedMs = if (v < 1f) stoppedMs + dt else 0L
        val before = speedAgo(s.tMs, 1_000L)
        calmMs = if (before == null || abs(before - v) < CALM_DELTA_KMH) calmMs + dt else 0L
    }

    /** [ago] 밀리초 전의 속도. 그만큼의 기록이 없으면 null */
    private fun speedAgo(now: Long, ago: Long): Float? {
        var best: LongArray? = null
        for (e in speedHist) {
            if (now - e[0] >= ago) best = e else break
        }
        val b = best ?: return null
        if (now - b[0] > ago + 1_500L) return null
        return b[1] / 100f
    }

    private fun decelerating(s: Signals): Boolean {
        val v = s.speedKmh ?: return false
        val before = speedAgo(s.tMs, 3_000L) ?: speedAgo(s.tMs, 2_000L) ?: return false
        return before - v >= DECEL_KMH
    }

    private fun hardBrake(s: Signals): Boolean {
        val v = s.speedKmh ?: return false
        // 1초 넘게 지난 기록 가운데 가장 가까운 것과 견줍니다. 기록 간격이 벌어졌으면 1초당 감속으로 고쳐 셉니다.
        var best: LongArray? = null
        for (e in speedHist) {
            if (s.tMs - e[0] >= 1_000L) best = e else break
        }
        val b = best ?: return false
        val ageMs = s.tMs - b[0]
        if (ageMs > 2_500L) return false
        val before = b[1] / 100f
        val perSecond = (before - v) * 1_000f / ageMs
        return before >= 20f && perSecond >= HARD_BRAKE_KMH
    }

    companion object {
        const val PEDESTRIAN_LEAD_MS = 6_000L      // 우회전과 어린이보호구역은 6초 전
        const val MAX_GRACE_MS = 4_000L            // 문장이 끝나기를 기다리는 최대 시간
        const val BASIC_GRACE_MS = 1_500L          // 방향지시등으로 알게 된 회전은 이미 가까워서 짧게
        const val WAIT_RESUME_MS = 5_000L          // 회전 전에 이만큼 서 있으면 다시 재생
        const val MAX_MOVING_MS = 60_000L          // 움직인 시간 기준 한 번 멈춤의 상한
        const val MERGE_WINDOW_MS = 30_000L        // 다음 멈춤이 이 안에 시작되면 이어서 멈춤
        const val LANE_GAP_MS = 90_000L            // 차로 변경 멈춤 사이 최소 간격
        const val BUDGET_WINDOW_MS = 30 * 60_000L  // 예산을 세는 구간
        const val LANE_RESUME_MS = 2_000L          // 방향지시등이 꺼지고 다시 재생하기까지
        const val RIGHT_STABLE_MS = 2_000L
        const val COMPLEX_STABLE_MS = 3_000L
        const val CALM_MS = 3_000L                 // 급제동 뒤 속도 변화가 잦아든 시간
        const val RECLASSIFY_MS = 10_000L
        const val ZONE_FALSE_ALARM_MS = 15_000L
        const val TURN_SPEED_KMH = 25f             // 이 속도 이하에서 켠 방향지시등은 회전
        const val LANE_SPEED_KMH = 40f             // 이 속도 이상에서 켠 방향지시등은 차로 변경
        const val DECEL_KMH = 5f                   // 3초 사이 이만큼 느려졌으면 감속 중
        const val HARD_BRAKE_KMH = 12f             // 1초에 이만큼 느려지면 급제동
        const val CALM_DELTA_KMH = 5f
        const val MOVING_KMH = 3f
        const val STEER_STABLE_DEG = 15f
        const val TURN_STEER_DEG = 45f
        const val SIGNAL_NEAR_TURN_M = 150f
        const val NEW_TURN_JUMP_M = 40f
        const val NEAR_M = 30f
    }
}
