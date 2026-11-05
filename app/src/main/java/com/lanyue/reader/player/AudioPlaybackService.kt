package com.lanyue.reader.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.lifecycle.LifecycleService
import androidx.media3.session.MediaSession

/**
 * 4.9 音频书源后台播放（foregroundServiceType=mediaPlayback）。
 *
 * 约束：
 * - 音频书源（bookSourceType=1）正文结果是直链，不解析 HTML，直接注入 ExoPlayer
 * - 通知栏提供 播放/暂停 + 上一章/下一章，锁屏同样可见
 * - 无播放任务时立即 stopSelf，不常驻
 *
 * 注意：本项目不在后台做网络抓取，正文 URL 由前台 UI 解析后传入。
 */
class AudioPlaybackService : LifecycleService() {

    companion object {
        private const val CHANNEL_ID = "lanyue_audio_playback"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY = "com.lanyue.reader.action.AUDIO_PLAY"
        const val ACTION_PAUSE = "com.lanyue.reader.action.AUDIO_PAUSE"
        const val ACTION_STOP = "com.lanyue.reader.action.AUDIO_STOP"

        /** 4.9：外部只能通过 startForegroundService 启动，且必须带 url。 */
        fun start(context: Context, url: String, headers: Map<String, String> = emptyMap()) {
            val intent = Intent(context, AudioPlaybackService::class.java).apply {
                action = ACTION_PLAY
                putExtra("url", url)
                headers.forEach { (k, v) -> putExtra("h_$k", v) }
            }
            context.startForegroundService(intent)
        }
    }

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_PAUSE -> {
                startForeground(NOTIFICATION_ID, buildNotification(paused = true))
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PLAY -> {
                val url = intent.getStringExtra("url")
                if (url.isNullOrBlank()) {
                    // 无有效任务：不常驻，避免被判为滥用前台服务
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, buildNotification(paused = false))
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "音频播放",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "音频书源后台播放控制"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(paused: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, Class.forName("com.lanyue.reader.ui.MainActivity")),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("蓝阅 · 音频播放")
            .setContentText(if (paused) "已暂停" else "正在播放")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (paused) "播放" else "暂停",
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, AudioPlaybackService::class.java).apply {
                        action = if (paused) ACTION_PLAY else ACTION_PAUSE
                    },
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}
