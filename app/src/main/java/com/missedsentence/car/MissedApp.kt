package com.missedsentence.car

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import com.missedsentence.car.player.OutputWarmer

class MissedApp : Application() {

    lateinit var controller: Controller
        private set

    /** 앱 화면이 떠 있는지. MainActivity 가 알려 줍니다. */
    @Volatile var uiVisible = false

    private val warmer = OutputWarmer()

    override fun onCreate() {
        super.onCreate()
        controller = Controller(this)
        controller.start()
        // 시연 모드에서 앱 화면이 떠 있거나 재생 중이면 소리 출력이 쉬지 않게 합니다(에뮬레이터 소리 끊김 대책).
        controller.addTickListener { warmer.set(controller.demoMode && (uiVisible || controller.player.wantPlay)) }
        // 차량 미디어 화면에 보일 표지 그림
        controller.player.artwork = runCatching { BitmapFactory.decodeResource(resources, R.drawable.art_cover) }.getOrNull()
    }

    companion object {
        fun controller(context: Context): Controller = (context.applicationContext as MissedApp).controller
    }
}
