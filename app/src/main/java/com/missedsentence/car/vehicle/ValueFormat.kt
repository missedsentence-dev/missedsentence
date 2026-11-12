package com.missedsentence.car.vehicle

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** SDK가 돌려준 값을 비교와 저장이 쉬운 짧은 문자열로 바꿉니다. */
internal object ValueFormat {

    private const val MAX_LEN = 160
    private val JVM_IDENTITY = Regex("""([A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*)@[0-9a-fA-F]+""")

    fun format(value: Any?): String = when (value) {
        null -> "null"
        is Float -> number(value.toDouble())
        is Double -> number(value)
        is Number -> value.toString()
        is Boolean -> value.toString()
        is Enum<*> -> value.name
        is CharSequence -> value.toString()
        is IntArray -> value.joinToString(",", "[", "]")
        is LongArray -> value.joinToString(",", "[", "]")
        is FloatArray -> value.joinToString(",", "[", "]") { number(it.toDouble()) }
        is DoubleArray -> value.joinToString(",", "[", "]") { number(it) }
        is BooleanArray -> value.joinToString(",", "[", "]")
        is Array<*> -> value.joinToString(",", "[", "]") { format(it) }
        is Collection<*> -> value.joinToString(",", "[", "]") { format(it) }
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { "${format(it.key)}=${format(it.value)}" }
        else -> sanitize(value.toString())
    }.let { if (it.length > MAX_LEN) it.substring(0, MAX_LEN) else it }

    fun number(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "null"
        val rounded = (v * 10.0).roundToLong() / 10.0
        return if (abs(rounded - rounded.roundToLong()) < 1e-9) {
            rounded.roundToLong().toString()
        } else {
            String.format(Locale.US, "%.1f", rounded)
        }
    }

    fun sanitize(text: String): String = JVM_IDENTITY.replace(text) {
        it.value.substringBefore('@').substringAfterLast('.')
    }
}
