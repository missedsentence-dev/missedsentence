package com.missedsentence.car.core

/**
 * Vehicle SDK에서 받은 값 묶음에서 방향지시등, 브레이크 페달, 조향각을 읽습니다.
 * SDK의 함수 이름과 값 표기가 차종과 버전에 따라 다를 수 있어 이름과 낱말로 가려 읽습니다.
 * 키는 "TurnSignal.getCurrentTurnSignalState" 같은 "도메인.함수이름" 꼴입니다.
 */
object SignalReader {

    data class Turn(val left: Boolean, val right: Boolean, val known: Boolean)

    private val FALSY = setOf(
        "false", "off", "0", "none", "null", "inactive", "released", "unpressed",
        "not", "invalid", "unknown", "idle", "no", "disabled"
    )

    /** "ON", "TURN_SIGNAL_OFF", "true", "NOT_PRESSED" 같은 값이 켜짐인지 */
    fun truthy(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val number = value.trim().toDoubleOrNull()
        if (number != null) return number > 0.0
        val tokens = value.lowercase().split(Regex("[^a-z0-9가-힣]+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        return tokens.none { it in FALSY || it == "꺼짐" }
    }

    fun turn(values: Map<String, String>): Turn {
        var left = false
        var right = false
        var known = false
        for ((key, raw) in values) {
            if (!key.startsWith("TurnSignal.")) continue
            val name = key.substringAfter('.').lowercase()
            val hasLeft = name.contains("left")
            val hasRight = name.contains("right")
            if (hasLeft != hasRight) {
                // 좌우가 따로 있는 함수
                known = true
                if (hasLeft && truthy(raw)) left = true
                if (hasRight && truthy(raw)) right = true
                continue
            }
            if (name.contains("hazard") || name.contains("emergency")) continue
            // 한 값에 방향이 담긴 함수
            val v = raw.uppercase()
            val number = raw.trim().toDoubleOrNull()?.toInt()
            known = true
            when {
                number != null -> when (number) {       // AAOS 표기: 0 없음, 1 오른쪽, 2 왼쪽
                    1 -> right = true
                    2 -> left = true
                    3 -> { left = true; right = true }
                }
                v.contains("HAZARD") || v.contains("BOTH") || v.contains("EMERGENCY") -> { left = true; right = true }
                v.contains("LEFT") || v.contains("좌") -> left = left || !v.contains("OFF")
                v.contains("RIGHT") || v.contains("우") -> right = right || !v.contains("OFF")
            }
        }
        return Turn(left, right, known)
    }

    /** 브레이크 페달을 밟고 있는지. 값이 없으면 null */
    fun brakePedal(values: Map<String, String>): Boolean? {
        var found: Boolean? = null
        for ((key, raw) in values) {
            if (!key.startsWith("Brake.")) continue
            val name = key.substringAfter('.').lowercase()
            if (!name.contains("pedal")) continue
            found = (found ?: false) || truthy(raw)
        }
        if (found != null) return found
        for ((key, raw) in values) {
            if (!key.startsWith("Brake.")) continue
            val name = key.substringAfter('.').lowercase()
            if (name.contains("parking") || name.contains("epb") || name.contains("hold") || name.contains("auto")) continue
            found = (found ?: false) || truthy(raw)
        }
        return found
    }

    /** 조향각(도). 값이 없으면 null */
    fun steeringDeg(values: Map<String, String>): Float? {
        for ((key, raw) in values) {
            if (!key.startsWith("SteeringWheel.")) continue
            if (!key.substringAfter('.').lowercase().contains("angle")) continue
            raw.trim().toFloatOrNull()?.let { if (it.isFinite()) return it }
        }
        return null
    }
}
