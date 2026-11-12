package android.util

object Log {
    val errors = ArrayList<String>()
    val infos = ArrayList<String>()
    @JvmStatic fun i(tag: String, msg: String): Int {
        if (infos.size < 2000) infos.add(msg)
        return 0
    }
    @JvmStatic fun w(tag: String, msg: String, t: Throwable? = null): Int {
        errors.add("W $msg ${t ?: ""}")
        return 0
    }
    @JvmStatic fun e(tag: String, msg: String, t: Throwable? = null): Int {
        errors.add("E $msg ${t ?: ""}")
        t?.printStackTrace()
        return 0
    }
}
