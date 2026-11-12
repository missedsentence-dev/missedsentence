package com.missedsentence.car.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.missedsentence.car.Controller.HoldIcon
import kotlin.math.min

/** 주행 화면의 큰 재생 버튼. 글자 없이 모양만으로 상태를 알립니다. */
class PlayButtonView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    /** 운전자가 재생을 원하고 있는지. true면 멈춤 모양을 보여 줍니다. */
    var playing = false
        set(value) {
            if (field != value) {
                field = value
                contentDescription = if (value) "멈춤" else "재생"
                invalidate()
            }
        }

    /** 엔진이 붙잡고 있어 조용한 상태. 버튼 색을 가라앉힙니다. */
    var held = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "재생"
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val r = size / 2f - size * 0.02f

        paint.style = Paint.Style.FILL
        paint.color = when {
            isPressed -> Palette.ACCENT_PRESSED
            held -> Palette.SURFACE_2
            else -> Palette.ACCENT
        }
        canvas.drawCircle(cx, cy, r, paint)
        if (held) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = size * 0.03f
            paint.color = Palette.ACCENT
            canvas.drawCircle(cx, cy, r - paint.strokeWidth / 2f, paint)
            paint.style = Paint.Style.FILL
        }

        paint.color = 0xFFFFFFFF.toInt()
        if (playing) {
            val w = size * 0.11f
            val h = size * 0.36f
            val gap = size * 0.07f
            val round = w * 0.3f
            rect.set(cx - gap - w, cy - h / 2f, cx - gap, cy + h / 2f)
            canvas.drawRoundRect(rect, round, round, paint)
            rect.set(cx + gap, cy - h / 2f, cx + gap + w, cy + h / 2f)
            canvas.drawRoundRect(rect, round, round, paint)
        } else {
            val h = size * 0.38f
            val left = cx - size * 0.11f
            path.reset()
            path.moveTo(left, cy - h / 2f)
            path.lineTo(left + h * 0.87f, cy)
            path.lineTo(left, cy + h / 2f)
            path.close()
            canvas.drawPath(path, paint)
        }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }
}

/** 진행 막대 */
class ProgressBarView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    var fraction = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (v != field) {
                field = v
                invalidate()
            }
        }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val r = h / 2f
        paint.color = Palette.SURFACE_2
        rect.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(rect, r, r, paint)
        if (fraction > 0f) {
            paint.color = Palette.ACCENT
            rect.set(0f, 0f, maxOf(h, width * fraction), h)
            canvas.drawRoundRect(rect, r, r, paint)
        }
    }
}

/**
 * 멈춘 이유를 알리는 아이콘 하나. 깜빡이지 않고, 글자를 쓰지 않습니다.
 * 100x100 눈금 위에 선으로 그립니다.
 */
class ReasonIconView(context: Context) : View(context) {

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val rect = RectF()

    var icon: HoldIcon? = null
        set(value) {
            if (field != value) {
                field = value
                contentDescription = when (value) {
                    HoldIcon.SCHOOL -> "어린이보호구역이라 잠시 쉬는 중"
                    HoldIcon.RIGHT -> "우회전이라 잠시 쉬는 중"
                    HoldIcon.LEFT -> "좌회전이라 잠시 쉬는 중"
                    HoldIcon.UTURN -> "유턴이라 잠시 쉬는 중"
                    HoldIcon.MERGE -> "합류라 잠시 쉬는 중"
                    HoldIcon.LANE -> "차로 변경이라 잠시 쉬는 중"
                    HoldIcon.BRAKE -> "급제동이라 잠시 쉬는 중"
                    HoldIcon.VOICE -> "안내 음성이 나오는 중"
                    null -> null
                }
                invalidate()
            }
        }

    override fun onDraw(canvas: Canvas) {
        val kind = icon ?: return
        val size = min(width, height).toFloat()
        val u = size / 100f
        canvas.save()
        canvas.translate((width - size) / 2f, (height - size) / 2f)
        canvas.scale(u, u)

        // 바탕
        fill.color = Palette.SURFACE_2
        rect.set(0f, 0f, 100f, 100f)
        canvas.drawRoundRect(rect, 22f, 22f, fill)

        line.color = Palette.TEXT
        line.strokeWidth = 8f
        fill.color = Palette.TEXT
        path.reset()
        when (kind) {
            HoldIcon.LEFT -> {
                path.moveTo(64f, 84f)
                path.lineTo(64f, 52f)
                path.quadTo(64f, 38f, 50f, 38f)
                path.lineTo(26f, 38f)
                path.moveTo(39f, 24f)
                path.lineTo(25f, 38f)
                path.lineTo(39f, 52f)
                canvas.drawPath(path, line)
            }
            HoldIcon.RIGHT -> {
                path.moveTo(30f, 84f)
                path.lineTo(30f, 52f)
                path.quadTo(30f, 38f, 44f, 38f)
                path.lineTo(62f, 38f)
                path.moveTo(50f, 24f)
                path.lineTo(64f, 38f)
                path.lineTo(50f, 52f)
                canvas.drawPath(path, line)
                // 길을 건너는 사람
                fill.color = Palette.WARN
                canvas.drawCircle(80f, 56f, 6f, fill)
                line.color = Palette.WARN
                line.strokeWidth = 7f
                canvas.drawLine(80f, 66f, 80f, 86f, line)
            }
            HoldIcon.UTURN -> {
                path.moveTo(68f, 84f)
                path.lineTo(68f, 46f)
                rect.set(32f, 28f, 68f, 64f)
                path.arcTo(rect, 0f, -180f)
                path.lineTo(32f, 70f)
                path.moveTo(18f, 58f)
                path.lineTo(32f, 72f)
                path.lineTo(46f, 58f)
                canvas.drawPath(path, line)
            }
            HoldIcon.MERGE -> {
                path.moveTo(30f, 86f)
                path.lineTo(30f, 68f)
                path.quadTo(30f, 52f, 50f, 46f)
                path.moveTo(70f, 86f)
                path.lineTo(70f, 68f)
                path.quadTo(70f, 52f, 50f, 46f)
                path.lineTo(50f, 18f)
                path.moveTo(37f, 31f)
                path.lineTo(50f, 17f)
                path.lineTo(63f, 31f)
                canvas.drawPath(path, line)
            }
            HoldIcon.LANE -> {
                // 차선
                line.color = Palette.FAINT
                line.strokeWidth = 6f
                canvas.drawLine(50f, 14f, 50f, 26f, line)
                canvas.drawLine(50f, 74f, 50f, 86f, line)
                line.color = Palette.TEXT
                line.strokeWidth = 8f
                path.moveTo(30f, 84f)
                path.lineTo(30f, 66f)
                path.lineTo(70f, 40f)
                path.lineTo(70f, 20f)
                path.moveTo(57f, 32f)
                path.lineTo(70f, 18f)
                path.lineTo(83f, 32f)
                canvas.drawPath(path, line)
            }
            HoldIcon.BRAKE -> {
                line.color = Palette.DANGER
                canvas.drawCircle(50f, 50f, 24f, line)
                rect.set(12f, 12f, 88f, 88f)
                canvas.drawArc(rect, 140f, 80f, false, line)
                canvas.drawArc(rect, -40f, 80f, false, line)
                canvas.drawLine(50f, 38f, 50f, 53f, line)
                fill.color = Palette.DANGER
                canvas.drawCircle(50f, 63f, 4.5f, fill)
            }
            HoldIcon.SCHOOL -> {
                fill.color = Palette.WARN
                path.moveTo(50f, 10f)
                path.lineTo(88f, 40f)
                path.lineTo(88f, 88f)
                path.lineTo(12f, 88f)
                path.lineTo(12f, 40f)
                path.close()
                canvas.drawPath(path, fill)
                // 어른과 아이
                fill.color = Palette.BG
                canvas.drawCircle(40f, 42f, 7f, fill)
                rect.set(32f, 51f, 48f, 80f)
                canvas.drawRoundRect(rect, 6f, 6f, fill)
                canvas.drawCircle(63f, 52f, 5.5f, fill)
                rect.set(57f, 59f, 69f, 80f)
                canvas.drawRoundRect(rect, 5f, 5f, fill)
            }
            HoldIcon.VOICE -> {
                path.moveTo(20f, 42f)
                path.lineTo(34f, 42f)
                path.lineTo(52f, 26f)
                path.lineTo(52f, 74f)
                path.lineTo(34f, 58f)
                path.lineTo(20f, 58f)
                path.close()
                canvas.drawPath(path, fill)
                line.strokeWidth = 7f
                rect.set(42f, 34f, 74f, 66f)
                canvas.drawArc(rect, -45f, 90f, false, line)
                rect.set(30f, 22f, 86f, 78f)
                canvas.drawArc(rect, -45f, 90f, false, line)
            }
        }
        canvas.restore()
    }
}
