package com.missedsentence.car.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.missedsentence.car.Controller
import com.missedsentence.car.MissedApp
import com.missedsentence.car.R
import com.missedsentence.car.ui.MainActivity

/**
 * 내비 화면을 보거나 주행 규제로 앱 화면이 가려진 동안에도 재생과 판단이 이어지도록 붙잡아 두는 미디어 재생 서비스.
 * 재생을 멈추고 1분이 지나면 스스로 끝납니다.
 */
class PlaybackService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var controller: Controller
    private var shownPlaying: Boolean? = null
    private var shownTitle: String? = null
    private var idleSince = 0L

    private val watch: () -> Unit = { onTick() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        controller = MissedApp.controller(this)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "재생", NotificationManager.IMPORTANCE_LOW)
        )
        startInForeground()
        controller.addTickListener(watch)
    }

    override fun onDestroy() {
        controller.removeTickListener(watch)
        super.onDestroy()
    }

    /** 운전자가 앱을 최근 앱 목록에서 지우면 재생을 멈추고 서비스도 끝냅니다. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        controller.player.pause()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> controller.player.toggle()
            ACTION_PREVIOUS -> controller.player.previous()
            else -> startInForeground()
        }
        return START_NOT_STICKY
    }

    private fun onTick() {
        val playing = controller.player.wantPlay
        val title = controller.pack?.title
        if (playing != shownPlaying || title != shownTitle) {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, build())
        }
        val now = System.currentTimeMillis()
        if (playing) {
            idleSince = 0L
        } else {
            if (idleSince == 0L) idleSince = now
            if (now - idleSince > IDLE_STOP_MS) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun build(): Notification {
        val playing = controller.player.wantPlay
        val title = controller.pack?.title ?: "놓친문장"
        shownPlaying = playing
        shownTitle = title
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), flags)
        val toggle = PendingIntent.getService(
            this, 2, Intent(this, PlaybackService::class.java).setAction(ACTION_TOGGLE), flags
        )
        val previous = PendingIntent.getService(
            this, 3, Intent(this, PlaybackService::class.java).setAction(ACTION_PREVIOUS), flags
        )
        val icon = Icon.createWithResource(this, R.drawable.ic_notify)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(if (playing) "복잡한 순간에는 문장 사이에서 쉬었다가 이어 들려줘요" else "멈춤")
            .setContentIntent(open)
            .setOngoing(playing)
            .addAction(Notification.Action.Builder(icon, "한 문장 다시", previous).build())
            .addAction(Notification.Action.Builder(icon, if (playing) "멈춤" else "재생", toggle).build())
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(controller.player.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .build()
    }

    private fun startInForeground() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, build())
            }
        } catch (t: Throwable) {
            // 포그라운드 시작이 막힌 환경에서는 앱이 화면에 있는 동안만 재생합니다.
            stopSelf()
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.missedsentence.car.action.TOGGLE"
        const val ACTION_PREVIOUS = "com.missedsentence.car.action.PREVIOUS"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 11
        private const val IDLE_STOP_MS = 60_000L

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, PlaybackService::class.java))
            } catch (t: Throwable) {
                // 무시: 앱이 화면에 있는 동안에는 재생이 이어집니다.
            }
        }
    }
}
