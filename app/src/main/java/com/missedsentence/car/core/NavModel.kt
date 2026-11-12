package com.missedsentence.car.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 내비 정보가 들어오는 한 줄기 통로.
 * 실제 NaviHelper 이벤트와 시연 출근길이 같은 통로로 값을 넣습니다.
 */
interface NavSink {
    fun onNextTurn(turn: NextTurn?, nowMs: Long)
    fun onSchoolCaution(distanceM: Float?, nowMs: Long)
    fun onSpeedLimit(kmh: Int?, nowMs: Long)
    fun onLocation(lat: Double?, lon: Double?, nowMs: Long)
    fun onRouteEnded(nowMs: Long)
}

/** 회전 안내 원문에서 회전 종류를 읽습니다. 필드 이름과 값 표기를 모르므로 낱말로 가려냅니다. */
object TurnParsing {
    fun typeFrom(raw: String?): TurnType {
        if (raw.isNullOrBlank()) return TurnType.OTHER
        val t = raw.uppercase().replace('-', '_').replace(' ', '_')
        fun has(vararg words: String) = words.any { t.contains(it) }
        return when {
            has("U_TURN", "UTURN", "유턴") -> TurnType.UTURN
            has("KEEP_LEFT", "KEEP_RIGHT", "FORK", "BRANCH", "JUNCTION", "분기", "갈림") -> TurnType.FORK
            has("EXIT", "진출", "출구", "OFF_RAMP", "RAMP_OFF") -> TurnType.EXIT
            has("MERGE", "ENTER", "ENTRANCE", "JOIN", "합류", "진입", "ON_RAMP", "RAMP_ON") -> TurnType.MERGE
            has("LEFT", "좌회전", "좌측") -> TurnType.LEFT
            has("RIGHT", "우회전", "우측") -> TurnType.RIGHT
            has("STRAIGHT", "직진") -> TurnType.STRAIGHT
            else -> TurnType.OTHER
        }
    }

    /** 주의 지점 원문이 어린이보호구역을 가리키는지 */
    fun isSchoolZone(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val t = raw.uppercase().replace('-', '_').replace(' ', '_')
        return listOf("SCHOOL", "CHILD", "KIDS", "어린이", "스쿨존", "보호구역").any { t.contains(it) } &&
            !t.contains("노인") && !t.contains("SILVER")
    }
}

object Geo {
    /** 두 좌표 사이의 거리(m) */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }
}

/**
 * 어린이보호구역 판정.
 * 1순위: 주의 지점 이벤트의 구역 종류 값.
 * 2순위: 공공데이터의 보호구역 시설 좌표와 현재 위치(반경 300m), 그리고 제한속도 30 이하.
 * 둘 다 쓸 수 없으면 규칙을 끕니다. 위치는 비교에만 쓰고 저장하지 않습니다.
 */
class SchoolZoneJudge(private val zonePoints: List<DoubleArray> = emptyList()) {

    enum class Source(val label: String) {
        CAUTION("주의 지점의 구역 종류"),
        PUBLIC_DATA("공공데이터 좌표와 제한속도"),
        OFF("판정 수단 없음, 규칙 끔")
    }

    data class Result(val inZone: Boolean, val aheadM: Float?, val source: Source)

    private var cautionSeenMs = -1L       // 구역 종류 값이 한 번이라도 들어온 시각
    private var lastCautionMs = -1L
    private var lastCautionDistM: Float? = null
    private var enteredMs = -1L
    private var limitAtEntry: Int? = null

    fun onSchoolCaution(distanceM: Float?, nowMs: Long) {
        cautionSeenMs = nowMs
        lastCautionMs = nowMs
        lastCautionDistM = distanceM
    }

    fun reset() {
        cautionSeenMs = -1L
        lastCautionMs = -1L
        lastCautionDistM = null
        enteredMs = -1L
    }

    fun judge(nowMs: Long, speedLimit: Int?, lat: Double?, lon: Double?): Result {
        if (cautionSeenMs >= 0) return byCaution(nowMs, speedLimit)
        if (zonePoints.isNotEmpty()) {
            if (lat == null || lon == null || speedLimit == null) return Result(false, null, Source.PUBLIC_DATA)
            val near = speedLimit <= ZONE_LIMIT_KMH && nearestM(lat, lon) <= ZONE_RADIUS_M
            return Result(near, null, Source.PUBLIC_DATA)
        }
        return Result(false, null, Source.OFF)
    }

    private fun byCaution(nowMs: Long, speedLimit: Int?): Result {
        val fresh = lastCautionMs >= 0 && nowMs - lastCautionMs <= CAUTION_FRESH_MS
        val dist = lastCautionDistM
        if (enteredMs < 0) {
            if (fresh && (dist == null || dist <= ENTER_M)) {
                enteredMs = nowMs
                limitAtEntry = speedLimit
            } else {
                return Result(false, if (fresh) dist else null, Source.CAUTION)
            }
        }
        // 구역 안: 제한속도가 30을 넘으면 벗어난 것으로 봅니다.
        // 제한속도를 모르면 주의 지점 소식이 끊기고 일정 시간이 지났을 때 벗어난 것으로 봅니다.
        val out = when {
            speedLimit != null -> speedLimit > ZONE_LIMIT_KMH
            else -> nowMs - maxOf(enteredMs, lastCautionMs) > NO_LIMIT_EXIT_MS
        }
        if (out) {
            enteredMs = -1L
            lastCautionMs = -1L
            lastCautionDistM = null
            return Result(false, null, Source.CAUTION)
        }
        return Result(true, null, Source.CAUTION)
    }

    fun nearestM(lat: Double, lon: Double): Double {
        var best = Double.MAX_VALUE
        for (p in zonePoints) {
            // 먼 점은 위도 차이만으로 먼저 걸러 냅니다.
            if (kotlin.math.abs(p[0] - lat) > 0.01) continue
            val d = Geo.distanceM(lat, lon, p[0], p[1])
            if (d < best) best = d
        }
        return best
    }

    companion object {
        const val ZONE_RADIUS_M = 300.0
        const val ZONE_LIMIT_KMH = 30
        const val ENTER_M = 20f
        const val CAUTION_FRESH_MS = 5_000L
        const val NO_LIMIT_EXIT_MS = 45_000L

        /** 공공데이터 CSV에서 위도와 경도 열을 찾아 좌표 목록으로 바꿉니다. */
        fun parseCsv(text: String): List<DoubleArray> {
            val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
            if (lines.size < 2) return emptyList()
            val header = lines[0].replace("﻿", "").split(',').map { it.trim().trim('"') }
            val latIdx = header.indexOfFirst { it.contains("위도") || it.equals("lat", true) || it.equals("latitude", true) }
            val lonIdx = header.indexOfFirst { it.contains("경도") || it.equals("lon", true) || it.equals("lng", true) || it.equals("longitude", true) }
            if (latIdx < 0 || lonIdx < 0) return emptyList()
            val out = ArrayList<DoubleArray>()
            for (i in 1 until lines.size) {
                val cols = lines[i].split(',')
                val lat = cols.getOrNull(latIdx)?.trim()?.trim('"')?.toDoubleOrNull() ?: continue
                val lon = cols.getOrNull(lonIdx)?.trim()?.trim('"')?.toDoubleOrNull() ?: continue
                if (lat in 32.0..39.5 && lon in 124.0..132.0) out.add(doubleArrayOf(lat, lon))
            }
            return out
        }
    }
}

/** 내비에서 들어온 최신 값을 모아 엔진 입력으로 바꿔 줍니다. */
class NavState(private val judge: SchoolZoneJudge) : NavSink {

    private var turn: NextTurn? = null
    private var turnAtMs = -1L
    private var speedLimit: Int? = null
    private var speedLimitAtMs = -1L
    private var lat: Double? = null
    private var lon: Double? = null
    private var locationAtMs = -1L

    var lastZone: SchoolZoneJudge.Result = SchoolZoneJudge.Result(false, null, SchoolZoneJudge.Source.OFF)
        private set

    override fun onNextTurn(turn: NextTurn?, nowMs: Long) {
        this.turn = turn
        turnAtMs = if (turn == null) -1L else nowMs
    }

    override fun onSchoolCaution(distanceM: Float?, nowMs: Long) = judge.onSchoolCaution(distanceM, nowMs)

    override fun onSpeedLimit(kmh: Int?, nowMs: Long) {
        speedLimit = kmh
        speedLimitAtMs = nowMs
    }

    override fun onLocation(lat: Double?, lon: Double?, nowMs: Long) {
        this.lat = lat
        this.lon = lon
        locationAtMs = nowMs
    }

    override fun onRouteEnded(nowMs: Long) {
        turn = null
        turnAtMs = -1L
    }

    fun clear() {
        turn = null
        turnAtMs = -1L
        speedLimit = null
        lat = null
        lon = null
        judge.reset()
    }

    /** 회전 안내가 최근 8초 안에 들어왔으면 길안내 중으로 봅니다. */
    fun routeActive(nowMs: Long): Boolean = turnAtMs >= 0 && nowMs - turnAtMs <= TURN_FRESH_MS

    fun nextTurn(nowMs: Long): NextTurn? = if (routeActive(nowMs)) turn else null

    fun speedLimit(nowMs: Long): Int? =
        if (speedLimitAtMs >= 0 && nowMs - speedLimitAtMs <= LIMIT_FRESH_MS) speedLimit else null

    fun zone(nowMs: Long): SchoolZoneJudge.Result {
        val fresh = locationAtMs >= 0 && nowMs - locationAtMs <= LOCATION_FRESH_MS
        lastZone = judge.judge(nowMs, speedLimit(nowMs), if (fresh) lat else null, if (fresh) lon else null)
        return lastZone
    }

    companion object {
        const val TURN_FRESH_MS = 8_000L
        const val LIMIT_FRESH_MS = 120_000L
        const val LOCATION_FRESH_MS = 10_000L
    }
}
