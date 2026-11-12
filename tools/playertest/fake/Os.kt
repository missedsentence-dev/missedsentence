package android.os

/** 검사용 가짜 시계와 할 일 줄. 시간을 손으로 흘려보냅니다. */
object FakeClock {
    class Task(val at: Long, val seq: Long, val r: Runnable)

    var now = 0L
    private var seq = 0L
    private val queue = ArrayList<Task>()

    fun reset() {
        now = 0L
        queue.clear()
    }

    fun post(r: Runnable, delay: Long) {
        queue.add(Task(now + delay, seq++, r))
    }

    fun remove(r: Runnable) {
        queue.removeAll { it.r === r }
    }

    fun clear() = queue.clear()

    /** [ms] 만큼 시간을 흘려보내며 그 사이에 예약된 일을 차례로 합니다. */
    fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val next = queue.filter { it.at <= end }.minWithOrNull(compareBy<Task>({ it.at }, { it.seq })) ?: break
            queue.remove(next)
            if (next.at > now) now = next.at
            next.r.run()
        }
        now = end
    }
}

class Looper private constructor() {
    companion object {
        private val main = Looper()
        @JvmStatic fun getMainLooper(): Looper = main
        @JvmStatic fun myLooper(): Looper = main
    }
}

open class Handler(val looper: Looper) {
    fun post(r: Runnable): Boolean {
        FakeClock.post(r, 0L)
        return true
    }

    fun postDelayed(r: Runnable, delayMillis: Long): Boolean {
        FakeClock.post(r, delayMillis)
        return true
    }

    fun removeCallbacks(r: Runnable) = FakeClock.remove(r)

    fun removeCallbacksAndMessages(token: Any?) = FakeClock.clear()
}

object SystemClock {
    @JvmStatic fun elapsedRealtime(): Long = FakeClock.now
}

class Bundle

class HandlerThread(name: String) {
    val looper: Looper get() = Looper.getMainLooper()
    fun start() {}
}
