package com.missedsentence.car.service

import android.content.Context

object PlaybackService {
    var starts = 0
    fun start(context: Context) {
        starts++
    }
}
