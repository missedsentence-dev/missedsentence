package com.missedsentence.navprompt

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** 버튼 하나짜리 화면. 누르면 내비 안내 음성처럼 포커스를 요청하고 안내음을 냅니다. */
class PromptActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d + 0.5f).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(40), dp(40), dp(40), dp(40))
            setBackgroundColor(0xFF0E1116.toInt())
        }
        root.addView(TextView(this).apply {
            text = "시연용 안내 음성"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFF2F4F7.toInt())
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "내비 안내 음성처럼 오디오 포커스를 4초 동안 요청해요. 놓친문장이 멈췄다가 그 문장 처음부터 다시 나오는지 볼 때 써요."
            textSize = 19f
            setTextColor(0xFFA3ADBB.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(28))
        })
        root.addView(TextView(this).apply {
            text = "안내음 내기"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minHeight = dp(96)
            minWidth = dp(320)
            background = GradientDrawable().apply {
                setColor(0xFF3D7BFF.toInt())
                cornerRadius = dp(20).toFloat()
            }
            isClickable = true
            setOnClickListener { Prompt.play(this@PromptActivity) { status.text = it } }
        })
        root.addView(TextView(this).apply {
            text = "낮추기를 허락하지 않는 요청으로 내기"
            textSize = 19f
            setTextColor(0xFFF2F4F7.toInt())
            gravity = Gravity.CENTER
            minHeight = dp(72)
            minWidth = dp(320)
            background = GradientDrawable().apply {
                setColor(0xFF222935.toInt())
                cornerRadius = dp(20).toFloat()
            }
            isClickable = true
            setOnClickListener { Prompt.play(this@PromptActivity, exclusive = true) { status.text = it } }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        status = TextView(this).apply {
            text = ""
            textSize = 19f
            setTextColor(0xFFA3ADBB.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        }
        root.addView(status)
        setContentView(root)
    }
}
