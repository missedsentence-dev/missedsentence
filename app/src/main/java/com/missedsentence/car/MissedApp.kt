package com.missedsentence.car

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory

class MissedApp : Application() {

    lateinit var controller: Controller
        private set

    override fun onCreate() {
        super.onCreate()
        controller = Controller(this)
        controller.start()
        // 차량 미디어 화면에 보일 표지 그림
        controller.player.artwork = runCatching { BitmapFactory.decodeResource(resources, R.drawable.art_cover) }.getOrNull()
    }

    companion object {
        fun controller(context: Context): Controller = (context.applicationContext as MissedApp).controller
    }
}
