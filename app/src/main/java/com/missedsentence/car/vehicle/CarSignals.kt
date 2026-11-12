package com.missedsentence.car.vehicle

import android.content.Context
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Android Automotive 표준 Car API(android.car)로 속도, 기어, 조향각, 주행 규제 상태를 읽습니다.
 * Pleos SDK는 AAOS에 같은 기능이 있으면 AAOS API를 쓰도록 안내하므로 이 값들은 표준 API에서 가져옵니다.
 * android.car 라이브러리가 없는 환경에서도 앱이 죽지 않도록 리플렉션으로 연결합니다.
 *
 * 기어, 주차 브레이크, 야간 모드처럼 바뀔 때만 알려 주는 값은 변경 알림도 구독해 마지막 값을 씁니다.
 * 시험용으로 차량 값을 주입하면 알림으로만 전달되는 경우가 있기 때문입니다.
 */
class CarSignals(context: Context) {

    private val appContext = context.applicationContext
    private var car: Any? = null
    private var propertyManager: Any? = null
    private var uxManager: Any? = null
    private var packageManager: Any? = null
    private var getProperty: Method? = null
    private var callbackClass: Class<*>? = null
    private var callback: Any? = null
    private val ids = HashMap<String, Int>()
    private val unsupported = HashSet<String>()
    private val lastEvents = ConcurrentHashMap<Int, Any>()

    @Volatile var available: Boolean = false
        private set
    @Volatile var lastError: String? = null
        private set

    fun connect() {
        if (available) return
        try {
            val carClass = Class.forName("android.car.Car")
            val c = carClass.getMethod("createCar", Context::class.java).invoke(null, appContext)
                ?: throw IllegalStateException("Car.createCar returned null")
            val getManager = carClass.getMethod("getCarManager", String::class.java)
            val propertyService = carClass.getField("PROPERTY_SERVICE").get(null) as String
            val uxService = carClass.getField("CAR_UX_RESTRICTION_SERVICE").get(null) as String
            val pm = getManager.invoke(c, propertyService)
                ?: throw IllegalStateException("CarPropertyManager unavailable")
            car = c
            propertyManager = pm
            uxManager = runCatching { getManager.invoke(c, uxService) }.getOrNull()
            getProperty = pm.javaClass.getMethod(
                "getProperty",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            resolveIds()
            subscribeOnChange(pm)
            available = true
            lastError = null
        } catch (t: Throwable) {
            available = false
            lastError = "${t.javaClass.simpleName}: ${t.message ?: ""}".take(140)
        }
    }

    fun disconnect() {
        val pm = propertyManager
        val cb = callback
        val cbClass = callbackClass
        if (pm != null && cb != null && cbClass != null) {
            runCatching { pm.javaClass.getMethod("unregisterCallback", cbClass).invoke(pm, cb) }
        }
        runCatching { car?.javaClass?.getMethod("disconnect")?.invoke(car) }
        car = null
        propertyManager = null
        uxManager = null
        packageManager = null
        callback = null
        available = false
    }

    /** km/h. 후진 중 음수 값은 절댓값으로 바꿉니다. */
    fun speedKmh(): Float? = readFloat("PERF_VEHICLE_SPEED")?.let { abs(it) * 3.6f }

    /** "P", "R", "N", "D" 또는 null */
    fun gear(): String? {
        val raw = readInt("GEAR_SELECTION") ?: readInt("CURRENT_GEAR") ?: return null
        return when (raw) {
            GEAR_PARK -> "P"
            GEAR_REVERSE -> "R"
            GEAR_NEUTRAL -> "N"
            GEAR_DRIVE -> "D"
            0 -> null
            else -> if (raw >= 16) "D" else null // 수동 1단 이상은 주행으로 봅니다.
        }
    }

    fun steeringDeg(): Float? = readFloat("PERF_STEERING_ANGLE")

    fun outsideTempC(): Float? = readFloat("ENV_OUTSIDE_TEMPERATURE")

    fun parkingBrakeOn(): Boolean? = readBoolean("PARKING_BRAKE_ON")

    fun nightMode(): Boolean? = readBoolean("NIGHT_MODE")

    /** 주행 규제(운전자 주의 분산 최소화)가 걸려 있는지 */
    fun uxRestricted(): Boolean? {
        val manager = uxManager ?: return null
        return try {
            val restrictions = manager.javaClass.getMethod("getCurrentCarUxRestrictions").invoke(manager)
                ?: return null
            restrictions.javaClass.getMethod("isRequiresDistractionOptimization")
                .invoke(restrictions) as? Boolean
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 주행 규제 중에도 이 화면을 보여 줘도 된다고 차량이 인정했는지. 알 수 없으면 null.
     * 마켓을 거치지 않고 직접 설치한 앱은 선언을 해도 인정받지 못해 주행 중 화면이 가려집니다.
     */
    fun screenAllowedWhileDriving(packageName: String, className: String): Boolean? {
        val c = car ?: return null
        return try {
            val pm = packageManager ?: run {
                val carClass = Class.forName("android.car.Car")
                val service = carClass.getField("PACKAGE_SERVICE").get(null) as String
                carClass.getMethod("getCarManager", String::class.java).invoke(c, service)
                    .also { packageManager = it }
            } ?: return null
            pm.javaClass.getMethod("isActivityDistractionOptimized", String::class.java, String::class.java)
                .invoke(pm, packageName, className) as? Boolean
        } catch (t: Throwable) {
            null
        }
    }

    private fun resolveIds() {
        val idsClass = runCatching { Class.forName("android.car.VehiclePropertyIds") }.getOrNull()
        FALLBACK_IDS.forEach { (name, fallback) ->
            val id = runCatching { idsClass?.getField(name)?.getInt(null) }.getOrNull() ?: fallback
            ids[name] = id
        }
    }

    /** 바뀔 때만 알려 주는 값의 변경 알림을 구독합니다. 실패해도 직접 읽기로 동작합니다. */
    private fun subscribeOnChange(pm: Any) {
        try {
            val cbClass = Class.forName("android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback")
            val register = pm.javaClass.getMethod(
                "registerCallback",
                cbClass,
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType
            )
            val proxy = Proxy.newProxyInstance(cbClass.classLoader, arrayOf(cbClass)) { self, method, args ->
                when (method.name) {
                    "onChangeEvent" -> {
                        runCatching { remember(args?.getOrNull(0)) }
                        null
                    }
                    "equals" -> self === args?.getOrNull(0)
                    "hashCode" -> System.identityHashCode(self)
                    "toString" -> "MissedSentenceCarCallback"
                    else -> null
                }
            }
            callbackClass = cbClass
            callback = proxy
            for (name in ON_CHANGE) {
                val id = ids[name] ?: continue
                runCatching { register.invoke(pm, proxy, id, 0f) }
            }
        } catch (t: Throwable) {
            // 구독할 수 없는 환경이면 직접 읽기만 씁니다.
        }
    }

    private fun remember(propertyValue: Any?) {
        if (propertyValue == null) return
        val id = propertyValue.javaClass.getMethod("getPropertyId").invoke(propertyValue) as? Int ?: return
        val value = propertyValue.javaClass.getMethod("getValue").invoke(propertyValue) ?: return
        lastEvents[id] = value
    }

    private fun readValue(name: String): Any? {
        if (!available) return null
        val id = ids[name] ?: return null
        // 바뀔 때만 알려 주는 값은 마지막 알림 값을 먼저 씁니다.
        if (name in ON_CHANGE) lastEvents[id]?.let { return it }
        if (name in unsupported) return null
        val pm = propertyManager ?: return null
        return try {
            val propertyValue = getProperty?.invoke(pm, id, 0) ?: return null
            propertyValue.javaClass.getMethod("getValue").invoke(propertyValue)
        } catch (t: Throwable) {
            val cause = (t as? InvocationTargetException)?.targetException ?: t
            // 지원하지 않는 속성은 다시 묻지 않습니다. 권한 문제는 나중에 허용될 수 있어 다시 시도합니다.
            if (cause is IllegalArgumentException) unsupported.add(name)
            null
        }
    }

    private fun readFloat(name: String): Float? = (readValue(name) as? Number)?.toFloat()?.takeIf { it.isFinite() }

    private fun readInt(name: String): Int? = (readValue(name) as? Number)?.toInt()

    private fun readBoolean(name: String): Boolean? = readValue(name) as? Boolean

    companion object {
        private const val GEAR_NEUTRAL = 1
        private const val GEAR_REVERSE = 2
        private const val GEAR_PARK = 4
        private const val GEAR_DRIVE = 8

        private val ON_CHANGE = setOf("GEAR_SELECTION", "CURRENT_GEAR", "PARKING_BRAKE_ON", "NIGHT_MODE")

        // VehiclePropertyIds 값 (AOSP 표준)
        private val FALLBACK_IDS = mapOf(
            "PERF_VEHICLE_SPEED" to 0x11600207,
            "GEAR_SELECTION" to 0x11400400,
            "CURRENT_GEAR" to 0x11400401,
            "PARKING_BRAKE_ON" to 0x11200402,
            "NIGHT_MODE" to 0x11200407,
            "ENV_OUTSIDE_TEMPERATURE" to 0x11600703,
            "PERF_STEERING_ANGLE" to 0x11600209
        )
    }
}
