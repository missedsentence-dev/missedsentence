package com.missedsentence.car.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Brake
import ai.pleos.playground.vehicle.api.SteeringWheel
import ai.pleos.playground.vehicle.api.TurnSignal
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.MirrorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WheelArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.MirrorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WheelValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import android.content.Context
import android.os.Handler
import android.os.SystemClock
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Pleos Vehicle SDK에서 운전 동작 신호(방향지시등, 브레이크 페달, 조향각)를 읽습니다.
 *
 * 1. 도메인 객체(TurnSignal, Brake, SteeringWheel)는 공식 샘플과 같은 방식으로 Vehicle에서 얻습니다.
 * 2. 각 조회 함수(getCurrentXxx)마다 짝이 되는 checkXxxCapabilities를 먼저 불러
 *    이 차량이 지원하는지 확인한 뒤 지원하는 것만 조회합니다.
 *    지원 여부를 확인할 수 없으면 직접 조회해 봅니다.
 * 3. 실패한 항목은 점점 간격을 늘려 다시 시도합니다.
 *
 * 차량 제어 API는 쓰지 않습니다. 모든 호출과 상태 변경은 전달받은 [handler]의 스레드에서 처리합니다.
 */
class VehicleProbe(context: Context, private val handler: Handler) {

    data class Stats(
        val connected: Boolean,
        val total: Int,
        val responding: Int,
        val unsupported: Int,
        val initError: String?
    )

    data class DiagRow(val key: String, val value: String?, val error: String?)

    private class Probe(
        val key: String,
        val method: Method,
        val target: Any,
        val args: Array<Any?>,
        val fast: Boolean
    ) {
        val callbackCount: Int = args.count { it === CALLBACK }
        var lastCall = 0L
        var inFlight = false
        var callId = 0
        var failures = 0
        var everSucceeded = false
        var retryAt = 0L
    }

    /** 짝이 되는 지원 여부 확인 함수를 기다리는 조회 함수 */
    private class Pending(
        val domain: String,
        val getter: Method,
        val target: Any,
        val check: Method,
        val areaIndex: Int
    ) {
        var sentAt = 0L
        var done = false
        var retries = 0
        var notBefore = 0L
    }

    private val appContext = context.applicationContext
    private var vehicle: Vehicle? = null
    private val probes = ArrayList<Probe>()
    private val probeKeys = HashSet<String>()
    private val pending = ArrayList<Pending>()
    private val values = ConcurrentHashMap<String, String>()
    private val errors = ConcurrentHashMap<String, String>()

    @Volatile private var connected = false
    @Volatile private var initError: String? = null
    @Volatile private var probeCount = 0
    @Volatile private var unsupportedCount = 0
    private var running = false
    private var initAttempts = 0
    private var slowCursor = 0

    fun start() {
        handler.post {
            if (!running) {
                running = true
                connect()
            }
        }
    }

    fun stop() {
        handler.post {
            running = false
            handler.removeCallbacks(pollTask)
            runCatching { vehicle?.release() }
            vehicle = null
            connected = false
        }
    }

    /** 가장 최근에 받은 값 전체. key -> value */
    fun snapshot(): Map<String, String> = HashMap(values)

    fun stats(): Stats = Stats(connected, probeCount, values.size, unsupportedCount, initError)

    fun diagnostics(): List<DiagRow> {
        val keys = (values.keys + errors.keys).toSortedSet()
        return keys.map { DiagRow(it, values[it], errors[it]) }
    }

    private fun connect() {
        initAttempts++
        var created: Vehicle? = null
        try {
            val v = Vehicle(appContext)
            created = v
            v.initialize()
            vehicle = v
            connected = true
            initError = null
            // 차량 서비스 연결이 끝날 시간을 조금 둔 뒤 조회 목록을 만듭니다.
            handler.postDelayed({
                buildProbes()
                handler.removeCallbacks(pollTask)
                handler.post(pollTask)
            }, 800)
        } catch (t: Throwable) {
            runCatching { created?.release() }
            connected = false
            initError = describe(t)
            if (running && initAttempts < MAX_INIT_ATTEMPTS) {
                handler.postDelayed({ connect() }, 5000)
            }
        }
    }

    // ------------------------------------------------------------------ 조회 목록 만들기

    private fun buildProbes() {
        val v = vehicle ?: return
        probes.clear()
        probeKeys.clear()
        pending.clear()
        addDomain("Brake", Brake::class.java) { v.getBrake() }
        addDomain("SteeringWheel", SteeringWheel::class.java) { v.getSteeringWheel() }
        addDomain("TurnSignal", TurnSignal::class.java) { v.getTurnSignal() }
        probeCount = probes.size + pending.size
    }

    private fun addDomain(domain: String, apiClass: Class<*>, instance: () -> Any) {
        val target = try {
            instance()
        } catch (t: Throwable) {
            errors["$domain.*"] = describe(t)
            return
        }
        val methods = try {
            apiClass.methods.filter { it.declaringClass != Any::class.java }.sortedBy { it.name }
        } catch (t: Throwable) {
            errors["$domain.*"] = describe(t)
            return
        }
        // 기능 이름 -> 지원 여부 확인 함수
        val checks = HashMap<String, Method>()
        for (m in methods) {
            if (!m.name.startsWith("check") || "$domain.${m.name}" in BLOCKED || isStaticOrSynthetic(m)) continue
            if (m.parameterTypes.all { isCallback(it) } && m.parameterTypes.isNotEmpty()) {
                checks[featureKey(m.name)] = m
            }
        }
        for (m in methods) {
            if (!isReadable(domain, m)) continue
            val types = m.parameterTypes
            if (types.any { !isCallback(it) && areaOptions(it) == null && !it.isEnum }) continue
            val areaIndexes = types.indices.filter { areaOptions(types[it]) != null }
            val enumIndexes = types.indices.filter { types[it].isEnum && areaOptions(types[it]) == null }
            val check = checks[featureKey(m.name)]
            if (check != null && areaIndexes.size <= 1 && enumIndexes.isEmpty()) {
                pending.add(Pending(domain, m, target, check, areaIndexes.firstOrNull() ?: -1))
            } else {
                addFallbackProbes(domain, m, target)
            }
        }
    }

    /** 지원 여부를 확인하지 못한 함수는 대표적인 area와 enum 값 몇 개로 직접 조회합니다. */
    private fun addFallbackProbes(domain: String, m: Method, target: Any) {
        val combos = argumentCombos(m) ?: return
        for ((suffix, args) in combos) {
            addProbe("$domain.${m.name}$suffix", m, target, args)
        }
    }

    private fun addProbe(key: String, m: Method, target: Any, args: Array<Any?>) {
        if (!probeKeys.add(key)) return
        probes.add(Probe(key, m, target, args, fast = FAST.containsMatchIn(m.name.lowercase())))
    }

    private fun sendCapabilityChecks(now: Long, budget: Int): Int {
        var used = 0
        for (p in pending) {
            if (used >= budget) break
            if (p.done || p.sentAt > 0 || now < p.notBefore) continue
            p.sentAt = now
            used++
            val types = p.check.parameterTypes
            val args = Array<Any?>(types.size) { i ->
                callbackFor(types[i]) { results -> handler.post { onCapability(p, results) } }
            }
            try {
                p.check.invoke(p.target, *args)
            } catch (t: Throwable) {
                val cause = (t as? InvocationTargetException)?.targetException ?: t
                handler.post { onCapability(p, listOf(cause)) }
            }
        }
        // 응답이 오지 않는 확인 함수는 몇 번 더 물어보고, 그래도 안 되면 직접 조회로 넘어갑니다.
        for (p in pending) {
            if (!p.done && p.sentAt > 0 && now - p.sentAt > CAPABILITY_TIMEOUT_MS) {
                if (!scheduleCapabilityRetry(p, now)) {
                    p.done = true
                    addFallbackProbes(p.domain, p.getter, p.target)
                }
            }
        }
        pending.removeAll { it.done }
        return used
    }

    /** 차량 서비스 연결 직후에는 확인이 실패할 수 있어 3초, 6초, 9초 뒤에 다시 묻습니다. */
    private fun scheduleCapabilityRetry(p: Pending, now: Long): Boolean {
        if (p.retries >= MAX_CAPABILITY_RETRIES) return false
        p.retries++
        p.sentAt = 0
        p.notBefore = now + 3_000L * p.retries
        return true
    }

    private fun onCapability(p: Pending, results: List<Any?>) {
        if (p.done) return
        val baseKey = "${p.domain}.${p.getter.name}"
        val failure = results.firstOrNull { it is Throwable } as? Throwable
        if (failure != null) {
            if (scheduleCapabilityRetry(p, SystemClock.elapsedRealtime())) return
            p.done = true
            errors[baseKey] = "지원 여부 확인 실패: ${describe(failure)}"
            addFallbackProbes(p.domain, p.getter, p.target)
            return
        }
        p.done = true
        val config = results.filterIsInstance<CapabilityConfig>().firstOrNull()
        if (config != null && config.accessType == AccessType.NONE) {
            errors[baseKey] = "이 차량에서 지원하지 않음"
            unsupportedCount++
            return
        }
        if (p.areaIndex < 0) {
            val args = Array<Any?>(p.getter.parameterTypes.size) { CALLBACK }
            addProbe(baseKey, p.getter, p.target, args)
            return
        }
        // 지원하는 area 목록 = 확인 결과 Map의 키
        val areaType = p.getter.parameterTypes[p.areaIndex]
        val areas = results.filterIsInstance<Map<*, *>>()
            .flatMap { it.keys }
            .filter { it != null && areaType.isInstance(it) }
            .distinct()
            .sortedBy { areaRank(areaCodes(it)) }
            .take(MAX_AREAS)
        if (areas.isEmpty()) {
            addFallbackProbes(p.domain, p.getter, p.target)
            return
        }
        for (area in areas) {
            val args = Array<Any?>(p.getter.parameterTypes.size) { i -> if (i == p.areaIndex) area else CALLBACK }
            addProbe("$baseKey@${areaCodes(area).joinToString("+")}", p.getter, p.target, args)
        }
    }

    // ------------------------------------------------------------------ 조회 함수 판별

    private fun isReadable(domain: String, m: Method): Boolean {
        if (isStaticOrSynthetic(m)) return false
        val n = m.name
        val isGetter = n.startsWith("getCurrent") ||
            (n.startsWith("get") && !n.startsWith("getCapabilit")) ||
            n.startsWith("is")
        if (!isGetter) return false
        if (m.parameterTypes.none { isCallback(it) } && m.returnType == Void.TYPE) return false
        if ("$domain.$n" in BLOCKED) return false
        if (m.getAnnotation(Deprecated::class.java) != null) return false
        if (m.getAnnotation(java.lang.Deprecated::class.java) != null) return false
        return true
    }

    private fun isStaticOrSynthetic(m: Method): Boolean =
        Modifier.isStatic(m.modifiers) || m.isSynthetic || m.isBridge || m.name.contains('$')

    /** getCurrentWiperState, checkWiperStateCapabilities -> "Wiper" (공식 샘플과 같은 규칙) */
    private fun featureKey(name: String): String = name
        .removePrefix("check")
        .removePrefix("getCurrent")
        .removePrefix("get")
        .removePrefix("is")
        .removeSuffix("Capabilities")
        .removeSuffix("Capability")
        .removeSuffix("State")
        .ifBlank { name }

    /** 각 파라미터에 넣을 값을 정합니다. 콜백 자리는 표시만 해 두고 호출할 때 만듭니다. */
    private fun argumentCombos(m: Method): List<Pair<String, Array<Any?>>>? {
        val options = ArrayList<List<Pair<String, Any?>>>()
        for (type in m.parameterTypes) {
            when {
                isCallback(type) -> options.add(listOf("" to CALLBACK))
                else -> {
                    val areas = areaOptions(type)
                    when {
                        areas != null -> options.add(areas)
                        type.isEnum -> {
                            val constants = type.enumConstants
                                ?.take(MAX_ENUM_OPTIONS)
                                ?.map { (it as Enum<*>).name to (it as Any?) }
                                ?: return null
                            if (constants.isEmpty()) return null
                            options.add(constants)
                        }
                        else -> return null
                    }
                }
            }
        }
        var combos: List<Pair<String, List<Any?>>> = listOf("" to emptyList())
        for (option in options) {
            val next = ArrayList<Pair<String, List<Any?>>>()
            loop@ for ((suffix, args) in combos) {
                for ((label, value) in option) {
                    val newSuffix = if (label.isEmpty()) suffix else "$suffix@$label"
                    next.add(newSuffix to (args + value))
                    if (next.size >= MAX_COMBOS) break@loop
                }
            }
            combos = next
        }
        return combos.map { (suffix, args) -> suffix to args.toTypedArray() }
    }

    private fun areaOptions(type: Class<*>): List<Pair<String, Any?>>? = when (type) {
        DoorArea::class.java -> pickAreas(DoorValue.values().map { it.name to DoorArea(it) })
        MirrorArea::class.java -> pickAreas(MirrorValue.values().map { it.name to MirrorArea(it) })
        SeatArea::class.java -> pickAreas(SeatValue.values().map { it.name to SeatArea(it) })
        WheelArea::class.java -> pickAreas(WheelValue.values().map { it.name to WheelArea(it) })
        WindowArea::class.java -> pickAreas(WindowValue.values().map { it.name to WindowArea(it) })
        else -> null
    }

    private fun pickAreas(all: List<Pair<String, Any>>): List<Pair<String, Any?>> =
        all.sortedBy { (name, _) -> areaRank(listOf(name)) }
            .take(FALLBACK_AREAS)
            .map { it.first to (it.second as Any?) }

    private fun areaRank(codes: List<String>): Int =
        codes.minOfOrNull { code -> PREFERRED_AREAS.indexOf(code).let { if (it < 0) 99 else it } } ?: 99

    /** area 객체 -> ["ROW_1_LEFT"] 같은 이름 목록 */
    private fun areaCodes(area: Any?): List<String> {
        val raw: Any? = when (area) {
            is DoorArea -> area.values
            is MirrorArea -> area.values
            is SeatArea -> area.values
            is WheelArea -> area.values
            is WindowArea -> area.values
            else -> null
        }
        val names = when (raw) {
            is Iterable<*> -> raw.mapNotNull { (it as? Enum<*>)?.name }
            is Array<*> -> raw.mapNotNull { (it as? Enum<*>)?.name }
            is Enum<*> -> listOf(raw.name)
            else -> emptyList()
        }
        return names.ifEmpty { listOf(area?.toString()?.take(40) ?: "AREA") }
    }

    private fun isCallback(type: Class<*>): Boolean =
        type == Function0::class.java || type == Function1::class.java ||
            type == Function2::class.java || type == Function3::class.java

    private fun isArea(value: Any?): Boolean =
        value is DoorArea || value is MirrorArea || value is SeatArea ||
            value is WheelArea || value is WindowArea

    // ------------------------------------------------------------------ 주기적 조회

    private val pollTask: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                pollSome()
            } catch (t: Throwable) {
                errors["probe"] = describe(t)
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun pollSome() {
        val now = SystemClock.elapsedRealtime()
        var budget = CALLS_PER_TICK
        if (pending.isNotEmpty()) budget -= sendCapabilityChecks(now, CAPABILITY_PER_TICK)
        probeCount = probes.size + pending.size
        if (probes.isEmpty()) return
        // 방향지시등, 브레이크, 조향은 빨리 바뀌므로 자주 조회합니다.
        for (p in probes) {
            if (budget <= 0) break
            if (p.fast && isDue(p, now, FAST_INTERVAL_MS)) {
                call(p, now)
                budget--
            }
        }
        // 나머지 항목은 차례로 돌아가며 조회합니다.
        var scanned = 0
        while (budget > 0 && scanned < probes.size) {
            val p = probes[slowCursor % probes.size]
            slowCursor = (slowCursor + 1) % probes.size
            scanned++
            if (!p.fast && isDue(p, now, SLOW_INTERVAL_MS)) {
                call(p, now)
                budget--
            }
        }
    }

    private fun isDue(p: Probe, now: Long, interval: Long): Boolean {
        if (now < p.retryAt) return false
        if (p.inFlight) {
            if (now - p.lastCall < CALL_TIMEOUT_MS) return false
            p.inFlight = false
            registerFailure(p, "응답 없음", now)
            return false
        }
        return now - p.lastCall >= interval
    }

    private fun call(p: Probe, now: Long) {
        p.lastCall = now
        p.inFlight = true
        val token = ++p.callId
        val types = p.method.parameterTypes
        val args = Array(p.args.size) { i ->
            val a = p.args[i]
            if (a === CALLBACK) callbackFor(types[i]) { results -> onResult(p, token, results) } else a
        }
        try {
            val returned = p.method.invoke(p.target, *args)
            if (p.callbackCount == 0) onResult(p, token, listOf(returned))
        } catch (t: Throwable) {
            val cause = (t as? InvocationTargetException)?.targetException ?: t
            onResult(p, token, listOf(cause))
        }
    }

    private fun callbackFor(type: Class<*>, sink: (List<Any?>) -> Unit): Any = when (type) {
        Function0::class.java -> {
            val f: () -> Unit = { sink(emptyList()) }
            f
        }
        Function1::class.java -> {
            val f: (Any?) -> Unit = { a -> sink(listOf(a)) }
            f
        }
        Function2::class.java -> {
            val f: (Any?, Any?) -> Unit = { a, b -> sink(listOf(a, b)) }
            f
        }
        else -> {
            val f: (Any?, Any?, Any?) -> Unit = { a, b, c -> sink(listOf(a, b, c)) }
            f
        }
    }

    private fun onResult(p: Probe, token: Int, results: List<Any?>) {
        val failure = results.firstOrNull { it is Throwable } as? Throwable
        val text = if (failure == null) formatResults(results) else null
        handler.post {
            if (token != p.callId) return@post
            p.inFlight = false
            if (failure != null) {
                registerFailure(p, describe(failure), SystemClock.elapsedRealtime())
                return@post
            }
            if (text != null) {
                values[p.key] = text
                errors.remove(p.key)
                p.everSucceeded = true
                p.failures = 0
                p.retryAt = 0
            }
        }
    }

    /** 실패할수록 다시 묻는 간격을 늘립니다. 처음부터 계속 실패한 항목은 최대 10분 간격으로만 다시 묻습니다. */
    private fun registerFailure(p: Probe, message: String, now: Long) {
        p.failures++
        errors[p.key] = message
        if (p.failures >= 3) {
            values.remove(p.key) // 오래된 값으로 판단하지 않도록
            val step = p.failures - 3
            val base = if (p.everSucceeded) 10_000L else 30_000L
            p.retryAt = now + min(MAX_BACKOFF_MS, base shl min(step, 5))
        }
    }

    private fun formatResults(results: List<Any?>): String? {
        val parts = results.filter { it != null && it != Unit && !isArea(it) }.map { ValueFormat.format(it) }
        if (parts.isEmpty()) return null
        return parts.joinToString(" / ")
    }

    private fun describe(t: Throwable): String =
        "${t.javaClass.simpleName}: ${t.message ?: ""}".trim().take(140)

    companion object {
        private val CALLBACK = Any()

        // 공식 샘플 앱에서 외부 앱에 공개하지 않는 API
        private val BLOCKED = setOf(
            "CarInfo.getCapabilityInfo",
            "Safety.checkTelltalesCapabilities",
            "Safety.getTelltalesInfo",
            "Safety.registerTelltales",
            "Safety.unregisterTelltales",
            "Tire.checkTirePressureCapabilities",
            "Tire.getTirePressure",
            "Tire.registerTirePressure",
            "Tire.unregisterTirePressure"
        )

        private val FAST = Regex("angle|brake|pedal|signal|turn|indicator|blinker")
        private val PREFERRED_AREAS = listOf(
            "ROW_1_LEFT", "ROW_1_RIGHT", "ROW_2_LEFT", "ROW_2_RIGHT",
            "FRONT_LEFT", "FRONT_RIGHT", "REAR_LEFT", "REAR_RIGHT",
            "FRONT_WINDSHIELD", "REAR_WINDSHIELD", "HOOD", "REAR", "TRUNK", "LEFT", "RIGHT",
            "ROW_1", "ROW_2", "ROW_1_CENTER", "ROW_2_CENTER"
        )

        private const val TICK_MS = 100L
        private const val CALLS_PER_TICK = 8
        private const val CAPABILITY_PER_TICK = 6
        private const val FAST_INTERVAL_MS = 250L
        private const val SLOW_INTERVAL_MS = 2_000L
        private const val CALL_TIMEOUT_MS = 5_000L
        private const val CAPABILITY_TIMEOUT_MS = 5_000L
        private const val MAX_CAPABILITY_RETRIES = 3
        private const val MAX_BACKOFF_MS = 10L * 60 * 1000
        private const val MAX_AREAS = 8
        private const val FALLBACK_AREAS = 6
        private const val MAX_ENUM_OPTIONS = 4
        private const val MAX_COMBOS = 8
        private const val MAX_INIT_ATTEMPTS = 30
    }
}
