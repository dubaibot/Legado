package com.lanyue.reader.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.ServiceCompat
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.gson.Gson
import com.lanyue.reader.ui.RuntimeGraph

/**
 * 4.9 音频书源后台播放（foregroundServiceType=mediaPlayback）。
 *
 * 约束（§4.9 spec）：
 * - 音频书源（bookSourceType=1）正文结果是直链，不解析 HTML，直接注入 ExoPlayer
 * - 通知栏提供 播放/暂停 + 上一章/下一章，锁屏同样可见
 * - Chapter 队列由前台 UI 解析后传入；服务态不做爬源
 * - 无播放任务时立即 stopSelf，不常驻
 *
 * 媒体按钮（§4.9 mediaPlayback）：MediaSession 接 ExoPlayer，
 * Play/Pause/Stop 由系统自动接管；翻章走通知 prev/next action。
 */
class AudioPlaybackService : MediaSessionService() {

    companion object {
        private const val CHANNEL_ID = "lanyue_audio_playback"
        private const val NOTIFICATION_ID = 1001
        private const val SESSION_ID = "lanyue-audio"

        const val ACTION_PLAY = "com.lanyue.reader.action.AUDIO_PLAY"
        const val ACTION_PAUSE = "com.lanyue.reader.action.AUDIO_PAUSE"
        const val ACTION_STOP = "com.lanyue.reader.action.AUDIO_STOP"
        const val ACTION_NEXT = "com.lanyue.reader.action.AUDIO_NEXT"
        const val ACTION_PREV = "com.lanyue.reader.action.AUDIO_PREV"

        const val EXTRA_CHAPTERS = "com.lanyue.reader.extra.CHAPTERS"
        const val EXTRA_CURRENT_INDEX = "com.lanyue.reader.extra.CURRENT_INDEX"

        /** §4.9 章节条目：UI 解析音频书源正文 Audio 后打包传入。 */
        data class AudioChapter(
            val title: String,
            val url: String,
            val headers: Map<String, String> = emptyMap()
        )

        /** §4.9 起播：UI 解析完音频章节列表后调用本方法。 */
        fun start(context: Context, chapters: List<AudioChapter>, startIndex: Int = 0) {
            val intent = Intent(context, AudioPlaybackService::class.java).apply {
                action = ACTION_PLAY
                putExtra(EXTRA_CHAPTERS, Gson().toJson(chapters))
                putExtra(EXTRA_CURRENT_INDEX, startIndex)
            }
            context.startForegroundService(intent)
        }

        /** 控制操作（暂停/播放/上一章/下一章/停止）走普通 startService。 */
        fun control(context: Context, action: String) {
            Intent(context, AudioPlaybackService::class.java).apply {
                this.action = action
            }.also { context.startService(it) }
        }
    }

    private val playerManager by lazy {
        PlayerManager(applicationContext, RuntimeGraph.get(applicationContext).metrics)
    }

    private var chapters: List<AudioChapter> = emptyList()
    private var currentIndex = 0
    private var chapterTitle = ""

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    /** §4.9 media3 Session：锁屏控件接管 play/pause/stop，handler 由 MediaSessionService 托管。 */
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession {
        val openIntent = Intent(this, Class.forName("com.lanyue.reader.ui.MainActivity"))
        val open = PendingIntent.getActivity(
            this,
            100,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        return session ?: MediaSession.Builder(this, playerManager.ensurePlayer())
            .setId(SESSION_ID)
            .setSessionActivity(open)
            .build()
            .also { session = it }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_PLAY -> {
                val raw = intent.getStringExtra(EXTRA_CHAPTERS)
                chapters = raw
                    ?.let { Gson().fromJson(it, Array<AudioChapter>::class.java)?.toList() }
                    ?: emptyList()
                currentIndex = intent.getIntExtra(EXTRA_CURRENT_INDEX, 0)
                    .coerceIn(0, chapters.lastIndex.coerceAtLeast(0))
                if (chapters.isEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                loadChapter(chapters[currentIndex])
                startForegroundCompat(paused = false)
                return START_STICKY
            }
            ACTION_PAUSE -> {
                playerManager.pause()
                startForegroundCompat(paused = true)
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                playerManager.release()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_NEXT -> {
                if (currentIndex + 1 < chapters.size) {
                    currentIndex++
                    loadChapter(chapters[currentIndex])
                }
                return START_NOT_STICKY
            }
            ACTION_PREV -> {
                if (currentIndex > 0) {
                    currentIndex--
                    loadChapter(chapters[currentIndex])
                }
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun loadChapter(ch: AudioChapter) {
        playerManager.ensurePlayer()
        playerManager.loadAudio(ch.url, ch.headers)
        chapterTitle = ch.title
        playerManager.player?.let { session?.setPlayer(it) }
        startForegroundCompat(paused = false)
    }

    /** Android 14+ 要求 startForeground 带 serviceType；ServiceCompat 抹平版本差异。 */
    private fun startForegroundCompat(paused: Boolean) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(paused = paused),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
    }

    /** section 媒体按钮布局：prev | play/pause | next —— media3 自动提取进行时状态。 */
    override fun onUpdateNotification(mediaSession: MediaSession, updated: Boolean) {
        // 通知被 MediaSessionService 默认填充 media control；本方法留空以保持默认行为。
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
            100,
            Intent(this, Class.forName("com.lanyue.reader.ui.MainActivity")),
            PendingIntent.FLAG_IMMUTABLE
        )

        val prevIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, AudioPlaybackService::class.java).apply {
                action = ACTION_PREV
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val playIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, AudioPlaybackService::class.java).apply {
                action = if (paused) ACTION_PLAY else ACTION_PAUSE
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val nextIntent = PendingIntent.getService(
            this,
            4,
            Intent(this, AudioPlaybackService::class.java).apply {
                action = ACTION_NEXT
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(chapterTitle.ifBlank { "蓝阅 · 音频播放" })
            .setContentText(
                if (paused)
                    "已暂停（第 ${currentIndex + 1}/${chapters.size} 章）"
                else
                    "正在播放（第 ${currentIndex + 1}/${chapters.size} 章）"
            )
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(true)
            .setStyle(Notification.MediaStyle()
                .setShowActionsInCompactView(
                    android.R.drawable.ic_media_previous,
                    if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                    android.R.drawable.ic_media_next
                ))
            .build()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        playerManager.release()
        super.onDestroy()
    }
}
