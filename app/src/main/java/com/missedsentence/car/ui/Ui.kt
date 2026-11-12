package com.missedsentence.car.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max

/** 차량 화면용 색. 어두운 바탕에 대비가 큰 글자와 초록색 재생 버튼 */
object Palette {
    val BG = 0xFF0E1116.toInt()
    val SURFACE = 0xFF171C24.toInt()
    val SURFACE_2 = 0xFF222935.toInt()
    val LINE = 0xFF2E3746.toInt()
    val TEXT = 0xFFF2F4F7.toInt()
    val SUB = 0xFFA3ADBB.toInt()
    val FAINT = 0xFF6B7585.toInt()
    val ACCENT = 0xFF2FB98A.toInt()
    val ACCENT_PRESSED = 0xFF259A72.toInt()
    val ACCENT_SOFT = 0x332FB98A
    val OK = 0xFF34C38F.toInt()
    val OK_SOFT = 0x3334C38F
    val WARN = 0xFFF5B841.toInt()
    val WARN_SOFT = 0x33F5B841
    val DANGER = 0xFFFF6B6B.toInt()
    val DANGER_SOFT = 0x33FF6B6B
    val INFO = 0xFF5B9BFF.toInt()
    val BASE_BAR = 0xFF475163.toInt()
}

/**
 * 코드로 화면을 만드는 작은 도우미.
 * Pleos Connect 화면 가이드에 맞춰 터치 영역은 크게(최소 64dp), 글자는 크게 씁니다.
 */
class Ui(val ctx: Context) {

    private val density = ctx.resources.displayMetrics.density

    fun dp(v: Int): Int = (v * density + 0.5f).toInt()
    fun dpf(v: Float): Float = v * density

    enum class Kind { PRIMARY, SECONDARY, GHOST, DANGER }

    fun text(
        s: CharSequence,
        sp: Float = 20f,
        color: Int = Palette.TEXT,
        bold: Boolean = false,
        maxLines: Int = 0
    ): TextView = TextView(ctx).apply {
        text = s
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        if (maxLines > 0) {
            this.maxLines = maxLines
            ellipsize = TextUtils.TruncateAt.END
        }
        setLineSpacing(0f, 1.15f)
    }

    fun round(color: Int, radiusDp: Int, strokeColor: Int? = null, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dpf(radiusDp.toFloat())
            if (strokeColor != null) setStroke(dp(strokeDp), strokeColor)
        }

    fun oval(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    /** 누르면 물결이 퍼지는 배경 */
    fun pressable(base: Drawable, rippleColor: Int = 0x40FFFFFF): Drawable =
        RippleDrawable(ColorStateList.valueOf(rippleColor), base, null)

    fun button(label: String, kind: Kind = Kind.SECONDARY, sp: Float = 20f, onClick: () -> Unit): TextView {
        val textColor = when (kind) {
            Kind.PRIMARY -> Color.WHITE
            Kind.DANGER -> Palette.DANGER
            else -> Palette.TEXT
        }
        val bg = when (kind) {
            Kind.PRIMARY -> round(Palette.ACCENT, 16)
            Kind.SECONDARY -> round(Palette.SURFACE_2, 16, Palette.LINE)
            Kind.GHOST -> round(Color.TRANSPARENT, 16, Palette.LINE)
            Kind.DANGER -> round(Palette.DANGER_SOFT, 16)
        }
        return text(label, sp, textColor, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(64)
            minWidth = dp(120)
            setPadding(dp(28), dp(12), dp(28), dp(12))
            background = pressable(bg)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    fun chip(label: String, selected: Boolean, onClick: (() -> Unit)? = null): TextView =
        text(label, 20f, if (selected) Color.WHITE else Palette.TEXT, bold = selected).apply {
            gravity = Gravity.CENTER
            minHeight = dp(60)
            minWidth = dp(96)
            setPadding(dp(24), dp(10), dp(24), dp(10))
            val bg = if (selected) round(Palette.ACCENT, 30) else round(Palette.SURFACE_2, 30, Palette.LINE)
            background = if (onClick != null) pressable(bg) else bg
            if (onClick != null) {
                isClickable = true
                setOnClickListener { onClick() }
            }
        }

    /** 누를 수 없는 작은 표시 */
    fun tag(label: String, color: Int = Palette.SUB, bg: Int = Palette.SURFACE_2, sp: Float = 16f): TextView =
        text(label, sp, color, bold = false).apply {
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = round(bg, 14)
        }

    private fun gap(sizeDp: Int): Drawable = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        setSize(dp(sizeDp), dp(sizeDp))
    }

    fun vbox(gapDp: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        if (gapDp > 0) {
            dividerDrawable = gap(gapDp)
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        }
    }

    fun hbox(gapDp: Int = 0, centerVertical: Boolean = true): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        if (centerVertical) gravity = Gravity.CENTER_VERTICAL
        if (gapDp > 0) {
            dividerDrawable = gap(gapDp)
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        }
    }

    fun card(padDp: Int = 28, gapDp: Int = 14, color: Int = Palette.SURFACE): LinearLayout = vbox(gapDp).apply {
        setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
        background = round(color, 24)
    }

    fun flow(hGapDp: Int = 12, vGapDp: Int = 12): FlowLayout = FlowLayout(ctx, dp(hGapDp), dp(vGapDp))

    fun scroll(child: View, padDp: Int = 0): ScrollView = ScrollView(ctx).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = true
        setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
        clipToPadding = false
        addView(child, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun spacer(): View = View(ctx)

    fun divider(): View = View(ctx).apply {
        setBackgroundColor(Palette.LINE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    fun section(title: String, sub: String? = null): LinearLayout = vbox(4).apply {
        addView(text(title, 22f, Palette.TEXT, bold = true))
        if (sub != null) addView(text(sub, 17f, Palette.SUB))
    }

    fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    fun wrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    fun weight(w: Float, vertical: Boolean = false): LinearLayout.LayoutParams =
        if (vertical) {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, w)
        } else {
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w)
        }

    fun fixed(widthDp: Int, heightDp: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(dp(widthDp), dp(heightDp))
}

/** 칩을 가로로 늘어놓다가 자리가 모자라면 다음 줄로 넘기는 레이아웃 */
class FlowLayout(context: Context, private val hGap: Int, private val vGap: Int) : ViewGroup(context) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val specWidth = MeasureSpec.getSize(widthMeasureSpec)
        val unbounded = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
        val maxWidth = if (unbounded) Int.MAX_VALUE else specWidth - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowHeight = 0
        var usedWidth = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            measureChild(
                child,
                MeasureSpec.makeMeasureSpec(if (unbounded) 0 else maxWidth, if (unbounded) MeasureSpec.UNSPECIFIED else MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            if (x > 0 && x + child.measuredWidth > maxWidth) {
                x = 0
                y += rowHeight + vGap
                rowHeight = 0
            }
            x += child.measuredWidth + hGap
            rowHeight = max(rowHeight, child.measuredHeight)
            usedWidth = max(usedWidth, x - hGap)
        }
        val height = y + rowHeight + paddingTop + paddingBottom
        val width = if (unbounded) usedWidth + paddingLeft + paddingRight else specWidth
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxWidth = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            if (x > 0 && x + child.measuredWidth > maxWidth) {
                x = 0
                y += rowHeight + vGap
                rowHeight = 0
            }
            child.layout(
                paddingLeft + x,
                paddingTop + y,
                paddingLeft + x + child.measuredWidth,
                paddingTop + y + child.measuredHeight
            )
            x += child.measuredWidth + hGap
            rowHeight = max(rowHeight, child.measuredHeight)
        }
    }
}

/** 글자가 달라졌을 때만 바꿉니다. 같은 글자를 다시 넣으면 화면을 쓸데없이 다시 그리게 됩니다. */
fun TextView.setTextIfChanged(value: CharSequence) {
    if (text?.toString() != value.toString()) text = value
}

/** 진행 막대를 2% 단위로만 움직여 다시 그리는 횟수를 줄입니다. */
fun stepFraction(value: Float): Float = Math.round(value * 50f) / 50f
