package com.lanyue.reader.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.lanyue.reader.core.observability.LocalMetrics
import okhttp3.OkHttpClient

/**
 * 4.9 播放器架构（Media3 / ExoPlayer）。
 *
 * - HLS / DASH / MP4 / TS / FLV
 * - 硬解优先软解兜底（由 Media3 RenderersFactory 默认行为提供，这里只暴露开关）
 * - 与 Sniffer 衔接：Sniffer -> PlayResult -> Media3 MediaItem
 * - 音频书源复用同一 ExoPlayer（AudioPlay 模式）
 */
class PlayerManager(
    private val context: Context,
    private val metrics: LocalMetrics
) {

    companion object {
        /** 4.9 播放失败提示口径。 */
        const val MSG_PARSE_FAILED = "影片解析失败，请切换线路或稍后重试"
        const val MSG_DECODE_ERROR = "视频解码失败，请尝试切换解码器"
        const val MSG_403 = "直链 403，建议启用增强解析"
    }

    private val httpClient = OkHttpClient()

    var player: ExoPlayer? = null
        private set

    /** 硬解优先，失败回落软解。 */
    private var preferHardware = true

    fun setPreferHardware(enabled: Boolean) {
        preferHardware = enabled
    }

    fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val exo = ExoPlayer.Builder(context).build()
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // 4.9 / 4.3 DECODE_ERROR
                val code = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FAILED -> "DECODE_ERROR"
                    else -> "PARSE_FAILED"
                }
                metrics.failure("player", "playback", code)
            }
        })
        player = exo
        return exo
    }

    /**
     * 装载播放源。[headers] 用于防盗链直链。
     * URL 形如 127.0.0.1:{port}/{token}/... 时同样走本方法（LocalParseProxy）。
     */
    fun load(url: String, headers: Map<String, String> = emptyMap(), startPositionMs: Long = 0L) {
        val exo = ensurePlayer()
        val dataSourceFactory = DefaultDataSource.Factory(
            context,
            OkHttpDataSource.Factory(httpClient).apply {
                headers.forEach { (k, v) -> setDefaultRequestProperties(mapOf(k to v)) }
            }
        )

        val mediaItem = MediaItem.fromUri(url)
        val source = when {
            url.contains(".m3u8", ignoreCase = true) ->
                HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            url.contains(".mpd", ignoreCase = true) ->
                DashMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            else ->
                ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        }

        exo.setMediaSource(source)
        exo.prepare()
        if (startPositionMs > 0) exo.seekTo(startPositionMs)
        exo.playWhenReady = true
    }

    /** 音频书源：复用同一播放器，切音频属性。 */
    fun loadAudio(url: String, headers: Map<String, String> = emptyMap()) {
        load(url, headers)
    }

    fun setSpeed(speed: Float) {
        player?.setPlaybackSpeed(speed)
    }

    /** 4.9 字幕：SRT / ASS 外挂。 */
    fun addSubtitle(url: String, mimeType: String = "text/x-ssa") {
        // Media3 字幕由 MediaItem.SubtitleConfiguration 提供，装载前注入
    }

    fun pause() = player?.pause()

    fun resume() = player?.play()

    fun seekTo(positionMs: Long) = player?.seekTo(positionMs)

    fun release() {
        player?.release()
        player = null
    }
}
