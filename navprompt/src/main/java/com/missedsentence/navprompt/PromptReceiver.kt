package com.missedsentence.navprompt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 놓친문장 화면을 띄워 둔 채로 안내음을 내고 싶을 때 씁니다.
 * adb shell am broadcast --include-stopped-packages -n com.missedsentence.navprompt/.PromptReceiver
 * 낮추기를 허락하지 않는 요청은 끝에 --ez exclusive true 를 붙입니다.
 */
class PromptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        val exclusive = intent?.getBooleanExtra("exclusive", false) == true
        Prompt.play(context, exclusive) { state ->
            Log.i("NavPrompt", state)
            if (state.startsWith("포커스를 돌려줬") || state.startsWith("오디오 포커스를 받지") || state.startsWith("안내음이 나오는")) {
                runCatching { pending.finish() }
            }
        }
    }
}
