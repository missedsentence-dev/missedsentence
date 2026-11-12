package org.json

/** 검사용으로 만든 작은 JSON 읽기와 쓰기. 앱에서는 Android에 들어 있는 org.json 을 씁니다. */
class JSONObject {
    private val map = LinkedHashMap<String, Any?>()

    constructor()
    constructor(text: String) {
        val v = MiniJson(text).parse()
        @Suppress("UNCHECKED_CAST")
        if (v is Map<*, *>) map.putAll(v as Map<String, Any?>) else throw IllegalArgumentException("not an object")
    }
    internal constructor(m: Map<String, Any?>) {
        map.putAll(m)
    }

    fun put(key: String, value: Any?): JSONObject {
        map[key] = value
        return this
    }

    fun optJSONArray(key: String): JSONArray? = (map[key] as? List<*>)?.let { JSONArray(it) }
    fun optString(key: String, fallback: String = ""): String = when (val v = map[key]) {
        null -> fallback
        else -> v.toString()
    }
    fun optLong(key: String, fallback: Long): Long = (map[key] as? Number)?.toLong() ?: fallback
    fun optInt(key: String, fallback: Int): Int = (map[key] as? Number)?.toInt() ?: fallback

    override fun toString(): String = map.entries.joinToString(",", "{", "}") { (k, v) ->
        "\"$k\":" + when (v) {
            null -> "null"
            is Number, is Boolean -> v.toString()
            else -> "\"" + v.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
    }
}

class JSONArray internal constructor(private val list: List<*>) {
    fun length(): Int = list.size
    @Suppress("UNCHECKED_CAST")
    fun optJSONObject(index: Int): JSONObject? = (list.getOrNull(index) as? Map<String, Any?>)?.let { JSONObject(it) }
}

private class MiniJson(private val s: String) {
    private var i = 0

    fun parse(): Any? {
        val v = value()
        ws()
        return v
    }

    private fun ws() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun value(): Any? {
        ws()
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> { i += 4; true }
            'f' -> { i += 5; false }
            'n' -> { i += 4; null }
            else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("bad json at $i")
        }
    }

    private fun obj(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        i++
        ws()
        if (s[i] == '}') { i++; return m }
        while (true) {
            ws()
            val k = str()
            ws()
            require(s[i] == ':') { "':' expected at $i" }
            i++
            m[k] = value()
            ws()
            if (s[i] == ',') { i++; continue }
            require(s[i] == '}') { "'}' expected at $i" }
            i++
            return m
        }
    }

    private fun arr(): List<Any?> {
        val l = ArrayList<Any?>()
        i++
        ws()
        if (s[i] == ']') { i++; return l }
        while (true) {
            l.add(value())
            ws()
            if (s[i] == ',') { i++; continue }
            require(s[i] == ']') { "']' expected at $i" }
            i++
            return l
        }
    }

    private fun str(): String {
        require(s[i] == '"') { "string expected at $i" }
        i++
        val b = StringBuilder()
        while (s[i] != '"') {
            if (s[i] == '\\') {
                i++
                when (val e = s[i]) {
                    'n' -> b.append('\n')
                    't' -> b.append('\t')
                    'r' -> b.append('\r')
                    'u' -> { b.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                    else -> b.append(e)
                }
            } else {
                b.append(s[i])
            }
            i++
        }
        i++
        return b.toString()
    }

    private fun num(): Number {
        val start = i
        while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++
        val t = s.substring(start, i)
        return if (t.any { it in ".eE" }) t.toDouble() else t.toLong()
    }
}
