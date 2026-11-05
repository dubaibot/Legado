package com.lanyue.reader.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 4.11 媒体下载退后台续传（foregroundServiceType=dataSync）。
 *
 * 约束（必须遵守，否则被系统清理或审核拒绝）：
 * - 必须是 dataSync 类型；开始后 10 秒内必须真正开始传输
 * - 用 Range 续传，落盘先写 .part 再原子重命名
 * - 用户暂停/取消时立即 stopForeground，不虚占前台
 * - 单任务串行，不做多任务并发（前台服务不能做后台批量抓取）
 *
 * 明确不做：不下载受版权保护的付费内容；仅对用户已获得的直链做离线缓存。
 */
class MediaDownloadService : LifecycleService() {

    companion object {
        private const val CHANNEL_ID = "lanyue_media_download"
        private const val NOTIFICATION_ID = 1002
        private const val BUFFER = 64 * 1024

        const val ACTION_START = "com.lanyue.reader.action.DOWNLOAD_START"
        const val ACTION_CANCEL = "com.lanyue.reader.action.DOWNLOAD_CANCEL"

        fun start(context: Context, url: String, targetPath: String) {
            val intent = Intent(context, MediaDownloadService::class.java).apply {
                action = ACTION_START
                putExtra("url", url)
                putExtra("target", targetPath)
            }
            context.startForegroundService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val downloaded = AtomicLong(0)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_CANCEL -> {
                job?.cancel()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val url = intent.getStringExtra("url")
                val target = intent.getStringExtra("target")
                if (url.isNullOrBlank() || target.isNullOrBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                // 先起前台通知，再开始传输（满足 10 秒内开始传输的判定）
                startForeground(NOTIFICATION_ID, buildNotification(0, 0))
                job?.cancel()
                job = scope.launch { download(url, File(target)) }
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun download(url: String, target: File) {
        val part = File(target.absolutePath + ".part")
        target.parentFile?.mkdirs()

        val resumeFrom = if (part.exists()) part.length() else 0L
        downloaded.set(resumeFrom)

        runCatching {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (resumeFrom > 0) conn.setRequestProperty("Range", "bytes=$resumeFrom-")
            conn.connect()

            val total = conn.contentLengthLong + resumeFrom
            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, true).use { out ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        if (!isActive()) return@runCatching
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        val done = downloaded.addAndGet(n.toLong())
                        notifyProgress(done, total)
                    }
                    out.flush()
                }
            }
            // 原子落盘
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
        }.onFailure {
            // 失败保留 .part，下次续传
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun isActive(): Boolean = job?.isActive ?: false

    private fun notifyProgress(done: Long, total: Long) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(done, total))
    }

    private fun buildNotification(done: Long, total: Long): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "媒体下载", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "离线缓存进度" }
            )
        }
        val pct = if (total > 0) ((done * 100) / total).toInt() else 0
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("蓝阅 · 离线缓存")
            .setContentText(if (total > 0) "$pct%" else "准备中")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, pct, total <= 0)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
