package io.legado.app.help.exoplayer

import androidx.core.net.toUri
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadRequest
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isVideo
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.MD5Utils
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.smoothstreaming.SsMediaSource
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import com.google.gson.reflect.TypeToken
import io.legado.app.BuildConfig
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.videoStreamClient
import io.legado.app.help.player.SniffRace
import io.legado.app.help.player.SniffRaceLimiter
import io.legado.app.model.VideoPlay
import io.legado.app.utils.GSON
import io.legado.app.utils.externalCache
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.isJsonArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import okhttp3.Request
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.TimeUnit


private fun String.containsMediaTypeToken(token: String): Boolean {
    if (contains(".$token")) return true
    var index = indexOf(token)
    while (index >= 0) {
        val charBefore = if (index > 0) this[index - 1] else null
        val afterIndex = index + token.length
        val charAfter = if (afterIndex < length) this[afterIndex] else null
        if ((charBefore == null || !charBefore.isLetterOrDigit()) &&
            (charAfter == null || !charAfter.isLetterOrDigit())
        ) {
            return true
        }
        index = indexOf(token, index + 1)
    }
    return false
}

/**
 * 媒体地址对应的自适应流 MIME（m3u8 / mpd / ism），普通文件（mp4 等）返回 null
 *
 * 这是"下载用什么下载器 / 缓存算不算完整 / 播放按哪种流播"共用的唯一口径（播放侧见 [mediaExtensionOfUrl]）。
 * 只按路径扩展名判断会漏掉 `/api/m3u8/?url=<真实地址>` 这类代理地址——路径结尾不是 `.m3u8`：
 * 下载侧会把它当普通文件下（只下到一个播放列表、还落了完成标记），播放侧却按 HLS 读同一份缓存，
 * 切片从来没下过，表现为"显示已缓存、点使用缓存也看不了"。
 */
internal fun mediaMimeTypeOfUrl(url: String): String? {
    val lower = url.lowercase()
    return when {
        lower.containsMediaTypeToken("m3u8") -> MimeTypes.APPLICATION_M3U8
        lower.containsMediaTypeToken("mpd") -> MimeTypes.APPLICATION_MPD
        lower.containsMediaTypeToken("ism") -> MimeTypes.APPLICATION_SS
        else -> null
    }
}

@Suppress("unused")
@SuppressLint("UnsafeOptInUsageError")
object ExoPlayerHelper {

    private const val SPLIT_TAG = "\uD83D\uDEA7"

    /**
     * W2 / REQ-14 / AD-05：嗅探赛马资源闸（连续失败 3 次 → 冷却 60s 内不赛马，回落串行链）。
     * 进程级单例语义：冷却跨播放会话生效，避免「网络全断时每本书都重打两路请求」。
     */
    private val sniffRaceLimiter = SniffRaceLimiter()

    /**
     * R4-T6: 浏览器 User-Agent（模拟 Chrome 移动版）
     *
     * 替换原 `Util.getUserAgent(context, "Legado")` 生成的 `Legado/1.0 (Linux; U; Android 13)`，
     * 部分站点 CDN 拒绝非浏览器 UA（403/401），改用浏览器 UA 提升抓取成功率。
     *
     * P0-5（2026-07-31）：可见性从 private 改为 public，供 M3u8PreCheckDataSource 复用同一 UA
     * 3.3.7（R 批）：Chrome 版本改为 `BuildConfig.Cronet_Main_Version` 单源（原硬编码 `Chrome/120.0.0.0`
     * 与内置 Cronet so 版本漂移，CDN 侧按 UA 版本做能力判断时会不一致）
     */
    val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7 Pro) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/${BuildConfig.Cronet_Main_Version} Mobile Safari/537.36"

    private val mapType by lazy {
        object : TypeToken<Map<String, String>>() {}.type
    }

    /**
     * T2.1: DefaultBandwidthMeter 全局单例（持续测量有效带宽）
     *
     * AD-04 决策：BandwidthMeter 动态调整缓冲参数（prepare 前分档，非运行时热切换）
     * - 滑动窗口算法测量有效带宽（ExoPlayer 内置）
     * - 按测量带宽分三档：弱网（<1Mbps）/ 中网（1-5Mbps）/ 好网（≥5Mbps）
     * - 每档对应一组 DefaultLoadControl 参数（弱网：小 buffer 快起播省流量；好网：大 buffer 防 rebuffer）
     * - 工程折中：LoadControl 只能在 player 构建时设置，运行时不可热切换，
     *   因此档位决策放在 prepare 前——每次 prepareAsyncInternal 时读取当前档位构建 player；
     *   网络切换后新档位在下一次 prepare 生效
     *
     * 成熟方案参考：developer.android.com ABR 官方指南（AdaptiveTrackSelection + BandwidthMeter）
     */
    val bandwidthMeter: DefaultBandwidthMeter by lazy {
        DefaultBandwidthMeter.Builder(appCtx).build()
    }

    /**
     * T2.1: 带宽档位枚举（弱网/中网/好网）
     */
    enum class BandwidthTier {
        WEAK,   // <1Mbps：小 buffer 快起播省流量
        MEDIUM, // 1-5Mbps：中 buffer 平衡
        GOOD    // ≥5Mbps：大 buffer 防 rebuffer
    }

    /**
     * FR-5: 强制带宽档位（TTFB 降档机制）
     *
     * - 非 null 时 getCurrentBandwidthTier() 返回此值（跳过自动计算）
     * - Exo2MediaPlayer.onLoadCompleted 中连续 3 次 TTFB>1000ms 设置降档
     * - 连续 3 次 TTFB<500ms 清除（恢复自动档位）
     * - 最小切换间隔 30 秒（防抖动）
     *
     * 注意：forceTier 只在下次 prepareAsyncInternal 时生效（LoadControl 不可运行时热切换）
     */
    @Volatile
    var forceTier: BandwidthTier? = null

    /**
     * T2.1: 获取当前带宽档位
     *
     * FR-5: 若 forceTier 非 null，优先返回 forceTier（TTFB 降档机制）
     *
     * @return 当前带宽档位（WEAK/MEDIUM/GOOD）
     */
    fun getCurrentBandwidthTier(): BandwidthTier {
        // FR-5: 优先返回强制档位（TTFB 降档）
        forceTier?.let { return it }
        val bitrateEstimate = bandwidthMeter.bitrateEstimate
        return when {
            bitrateEstimate < 1_000_000 -> BandwidthTier.WEAK   // <1Mbps
            bitrateEstimate < 5_000_000 -> BandwidthTier.MEDIUM // 1-5Mbps
            else -> BandwidthTier.GOOD                          // ≥5Mbps
        }
    }

    /**
     * R3: 按带宽档位构建 LoadControl（激进策略，优化当前视频快速缓冲）
     *
     * 分档策略（R3 大幅提升 maxBuffer，减少当前视频卡顿）：
     * - 弱网（<1Mbps）：minBuffer=10s, maxBuffer=30s, bufferForPlayback=500ms, bufferForPlaybackAfterRebuffer=1s
     * - 中网（1-5Mbps）：minBuffer=8s, maxBuffer=90s（原30s→90s，提升3倍）, bufferForPlayback=1s, bufferForPlaybackAfterRebuffer=2s
     * - 好网（≥5Mbps）：minBuffer=8s, maxBuffer=120s（原50s→120s，提升2.4倍）, bufferForPlayback=1s, bufferForPlaybackAfterRebuffer=2s
     *
     * R3 用户可配置：videoMaxBufferSec >0 时覆盖档位默认值（秒转毫秒）
     *
     * 首帧优化（2026-07-28）：降低 minBufferMs 从 15s→8s（MEDIUM/GOOD）和 10s→5s（WEAK），
     * 让首帧更快渲染（原 minBuffer=15s 导致首帧需缓冲 15s 才播放，用户感知卡顿）。
     * maxBuffer 保持 90/120s 防播放中 rebuffer。
     *
     * @param tier 带宽档位
     * @param allocator 共享内存分配器（T5.1 实例池场景传入共享 allocator，多实例共用同一缓冲内存池；null 则各实例独立）
     * @return 对应档位的 DefaultLoadControl
     */
    fun createLoadControlByTier(tier: BandwidthTier, allocator: DefaultAllocator? = null): DefaultLoadControl {
        val builder = DefaultLoadControl.Builder()
        allocator?.let { builder.setAllocator(it) }
        // R3: 用户可配置 maxBuffer（0=按档位自动，>0=用户指定秒数）
        val userMaxBufferSec = VideoPlay.videoMaxBufferSec
        // 缓冲速度优化（P0）：解除字节上限，让缓冲完全由 maxBuffer 时长控制
        // 默认 DEFAULT_TARGET_BUFFER_BYTES(~50MB) 会截断高码率视频的 maxBuffer 时长
        // -1 表示不限制字节总量（对齐 Media3 官方推荐 Large buffer for high-bitrate content）
        builder.setTargetBufferBytes(-1)
        // 缓冲速度优化（P0）：时间优先于字节，确保 maxBuffer 时长真正生效
        builder.setPrioritizeTimeOverSizeThresholds(true)
        val loadControl = when (tier) {
            BandwidthTier.WEAK -> {
                val maxBufferMs = (userMaxBufferSec.takeIf { it > 0 } ?: 30) * 1000
                // 首帧优化：minBuffer 从 10s→5s，让弱网首帧更快渲染
                builder.setBufferDurationsMs(5_000, maxBufferMs, 500, 1_000).build()
            }
            BandwidthTier.MEDIUM -> {
                val maxBufferMs = (userMaxBufferSec.takeIf { it > 0 } ?: 90) * 1000
                // 首帧优化：minBuffer 从 15s→8s，bufferForPlayback 从 1s→800ms 加速起播
                builder.setBufferDurationsMs(8_000, maxBufferMs, 800, 2_000).build()
            }
            BandwidthTier.GOOD -> {
                val maxBufferMs = (userMaxBufferSec.takeIf { it > 0 } ?: 120) * 1000
                // 首帧优化：minBuffer 从 15s→8s，bufferForPlayback 从 1s→500ms 中高端机激进起播
                builder.setBufferDurationsMs(8_000, maxBufferMs, 500, 2_000).build()
            }
        }
        // 缓冲速度优化（P0）：日志验证 LoadControl 实际参数生效
        AppLog.put(
            "[BufferSpeed] LoadControl tier=$tier maxBufferMs=${when (tier) {
                BandwidthTier.WEAK -> (userMaxBufferSec.takeIf { it > 0 } ?: 30) * 1000
                BandwidthTier.MEDIUM -> (userMaxBufferSec.takeIf { it > 0 } ?: 90) * 1000
                BandwidthTier.GOOD -> (userMaxBufferSec.takeIf { it > 0 } ?: 120) * 1000
            }} targetBufferBytes=-1 prioritizeTime=true"
        )
        return loadControl
    }

    /**
     * R4-T5: 嗅探结果数据类（7 维度交叉验证产物）
     *
     * @property contentType ExoPlayer 内容类型（C.TYPE_HLS / C.TYPE_DASH / C.TYPE_SS / C.TYPE_OTHER / TYPE_UNKNOWN）
     * @property mimeType 嗅探得到的 MIME 类型（可能为 null，表示未识别）
     * @property moovPosition MP4 moov box 位置（仅 MP4 有效，其他格式为 UNKNOWN）
     * @property supportsRange 是否支持 Range 请求（Accept-Ranges: bytes）
     * @property finalUrl 重定向后的最终 URL（未重定向则等于原 URL）
     */
    data class SniffResult(
        val contentType: Int,
        val mimeType: String?,
        val moovPosition: MoovPosition = MoovPosition.UNKNOWN,
        val supportsRange: Boolean = false,
        val finalUrl: String = "",
        /** video-sniff-403-and-rss-classic-fix 2.5/R-P1-4：m3u8 预检被源站确定性拒绝（403/410），上层消费触发重嗅 */
        val preCheckRejected: Boolean = false
    ) {
        companion object {
            /** 未知内容类型，进入降级链 */
            const val TYPE_UNKNOWN = -1

            /** 嗅探失败时的占位结果 */
            val UNKNOWN = SniffResult(TYPE_UNKNOWN, null)
        }
    }

    /**
     * R4-T4: 按嗅探结果智能选择 MediaSource（对齐浏览器五层架构）
     *
     * 核心改造：从"一律用 ProgressiveMediaSource"改为"按 contentType 分发 HLS/DASH/SS/Progressive"。
     * 这是"为什么有的能播有的播不了"的根本原因——HLS/DASH 不走 Extractor.sniff 路径，
     * 必须显式选择对应 MediaSource 才能正确解析 m3u8/mpd 清单。
     *
     * @param sniff 嗅探结果（来自 [sniffVideoType]）
     * @param url 视频 URL（优先用重定向后的 finalUrl）
     * @param dataSourceFactory 数据源工厂
     * @return 对应类型的 MediaSource
     * @throws IllegalArgumentException 当 contentType 为 UNKNOWN 时抛出，触发降级链
     */
    fun createMediaSource(
        sniff: SniffResult,
        url: String,
        dataSourceFactory: DataSource.Factory
    ): MediaSource {
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .apply { sniff.mimeType?.let { setMimeType(it) } }
            .apply {
                // 缓冲速度优化（P0）：HLS 直播配置 3s 目标偏移，VOD 自动忽略
                if (sniff.contentType == C.TYPE_HLS) {
                    setLiveConfiguration(
                        MediaItem.LiveConfiguration.Builder()
                            .setTargetOffsetMs(3_000)
                            .build()
                    )
                }
            }
            .build()
        return when (sniff.contentType) {
            C.TYPE_HLS -> {
                // P1-8: AES-128 密钥请求注入（2026-07-31）
                // 用 HlsKeyDataSourceFactory 包装 dataSourceFactory，对密钥 URL 注入额外防盗链头
                // 处理某些 CDN 对密钥请求的防盗链检查更严格的场景（需要播放页 Referer）
                val hlsDataSourceFactory = HlsKeyDataSourceFactory().wrap(dataSourceFactory)
                HlsMediaSource.Factory(hlsDataSourceFactory)
                    // 缓冲速度优化（P0）：仅解析 m3u8 清单即完成 preparation，首帧耗时降 30%+
                    // 兼容性 >95%（ExoPlayer 官方统计），对非标 m3u8 自动降级
                    .setAllowChunklessPreparation(true)
                    // P2-2: 指数退避重试策略（1s/2s/4s/8s/16s），最多 5 次
                    // 对齐 hls.js 策略：网络抖动时给予恢复时间，CDN 永久失效时快速降级
                    .setLoadErrorHandlingPolicy(object : DefaultLoadErrorHandlingPolicy() {
                        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                            val errorCount = loadErrorInfo.errorCount
                            if (errorCount >= 5) return C.TIME_UNSET  // TIME_UNSET 表示不再重试，触发降级
                            return (1L shl (errorCount - 1).coerceAtLeast(0)) * 1000L  // 2^(errorCount-1) * 1000ms: 1s/2s/4s/8s/16s
                        }
                    })
                    // R4-T10: AES-128 加密流由 ExoPlayer 内置支持
                    // dataSourceFactory 已注入 Referer/Cookie/UA 防盗链头（buildAntiLeechHeaders），
                    // HlsKeyDataSourceFactory 对密钥 URL 额外注入 currentPlayHeaders 头
                    .createMediaSource(mediaItem)
            }
            C.TYPE_DASH -> DashMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem)
            C.TYPE_SS -> SsMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem)
            C.TYPE_OTHER -> ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem)
            else -> throw IllegalArgumentException(
                "Sniff failed, contentType=UNKNOWN, url=${sanitizeUrl(url)}"
            )
        }
    }

    /**
     * V-004-P0-1: 嗅探前 DNS 预解析（异步预热系统 DNS 缓存）
     *
     * 根因：004 日志显示嗅探耗时 3123ms，其中 DNS 解析占主导（DoH 冷启动失败期间
     * Range 请求等待 DNS 解析 2-3s）。系统 DNS 解析是阻塞调用，首次解析新域名需 1-3s。
     *
     * 方案：在 sniffVideoType 启动前异步预解析域名，让系统 DNS 缓存预热，
     * Range 请求实际发起时 DNS 解析命中缓存（0ms），降低嗅探耗时。
     *
     * - 异步执行：不阻塞主线程，不等待结果（仅预热缓存）
     * - 独立 scope：SupervisorJob 避免父协程取消影响预解析
     * - 容错：任何异常静默吞掉（预解析失败不影响主流程，Range 请求会自行解析）
     * - 脱敏：日志只输出 host 前 2 字符 + hashCode，不输出完整域名
     *
     * @param url 视频 URL（提取 host 进行预解析）
     */
    fun preResolveDns(url: String) {
        val host = kotlin.runCatching { Uri.parse(url).host }.getOrNull() ?: return
        if (host.isBlank()) return
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            // video-sniff-403-and-rss-classic-fix 2.8a/R-P1-7（AD-11 DoH 一致化）：
            // 原实现 java.net.InetAddress.getAllByName 走系统 DNS，未享受 DoH 反污染能力；
            // 改走 DohDns（双国内 DoH+负缓存+降级链），与 OkHttp 栈 DNS 策略一致
            kotlin.runCatching { io.legado.app.help.http.DohDns.lookup(host) }
                .onSuccess { ips ->
                    AppLog.putDebug("preResolveDns: success host=${host.take(2)}***/${host.hashCode()}, ips=${ips.size}")
                }
                .onFailure { e ->
                    AppLog.putDebug("preResolveDns: failed host=${host.take(2)}***/${host.hashCode()}, error=${e.javaClass.simpleName}")
                }
        }
    }

    /**
     * R4-T5: 三级内容证据交叉验证嗅探视频类型（T5.2 术语修正：原"7 维度/五级识别链"）
     *
     * 识别架构（对齐 WHATWG MIMESNIFF 规范 + 浏览器嗅探策略）：
     * - 三级内容证据（Range 请求成功后，优先且排他）：
     *   1. 主动 Probe 清单内容（强信号，#EXTM3U/MPD 检测）
     *   2. Magic Number 匹配（强信号，17 项完整签名表）
     *   3. Content-Type 提示（服务器声明）
     *   内容证据未识别 → UNKNOWN，由降级链（HLS→DASH→Progressive）渐进接管
     * - URL 后缀兜底（T5.3：仅在 Range 失败/超时/空 body/HTTP 错误时）：
     *   URL 后缀可被伪造或动态化，不作为内容证据，仅网络层失败时给起始方向
     * - 附加维度（不参与类型判定）：MP4 moov 位置检测 + Accept-Ranges 检测 + finalUrl 重定向感知
     *
     * @param url 视频 URL（完整，含 query）
     * @param headers 请求头（如 Referer/User-Agent/Cookie）
     * @return SniffResult 含 contentType + mimeType + moovPosition + supportsRange + finalUrl
     */
    suspend fun sniffVideoType(url: String, headers: Map<String, String>): SniffResult {
        val startTime = System.currentTimeMillis()

        // P0-fix: 本地文件短路（内置播放器播放下载产物 ts/mp4 等）
        // 铁证（logs3 实测）：本地 file:// 走网络嗅探失败 → UNKNOWN → 默认降级链 [HLS, Progressive]
        // → 本地 ts/mp4 被当 HLS 清单解析 → ERROR_CODE_PARSING_MANIFEST_MALFORMED (3002)。
        // 本地文件无网络语义（无需 Range/Content-Type），直接按 Progressive 顺序媒体源播放。
        // 注意：mimeType 必须为 null（不强制 VIDEO_MP4）——HLS 下载转码失败会回退保留 .ts，
        // 若强制 video/mp4，Progressive 会用 Mp4Extractor 解析 ts 必然失败（铁证：用户实测
        // "下载后 ts 还是打开不了内置视频播放器"）。不设 mimeType 时 ExoPlayer 按容器自动
        // 嗅探（mp4/mpeg-ts/mkv...），mp4 与 ts 下载产物都能正常播放。
        if (url.startsWith("file://")) {
            return SniffResult(
                contentType = C.TYPE_OTHER,
                mimeType = null,
                supportsRange = true,
                finalUrl = url
            )
        }

        // P0-fix: URL 合法性校验（防止非 URL 字符串传入 sniffWithRangeRequestR4 导致 OkHttp 崩溃）
        // 铁证：书源 ruleContent 返回 m3u8 文件内容（#EXTM3U...）而非 URL 时，
        // OkHttp Request.Builder().url() 抛出 IllegalArgumentException 致 FATAL CRASH
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            AppLog.put("sniffVideoType: invalid URL scheme, skip sniff, urlPrefix=${url.take(20)}...")
            return SniffResult.UNKNOWN
        }

        // P0-1: HTML 接口预判拦截（嗅探成功率优化，2026-07-28）
        // 根因：002日志铁证——/Player/Play.php?id=xxx 等 HTML 接口返回 text/html 页面，
        // Range 请求得到 HTML 而非视频流，嗅探必然失败并消耗完整超时时长（6653ms）。
        // 方案：URL 路径以 .html/.htm 结尾时，极不可能是视频流接口（视频流通常无后缀或 .m3u8/.mp4/.php），
        // 直接返回 UNKNOWN 跳过 Range 请求，让 ExoPlayer 自行尝试或快速降级 WebView。
        // 注意：不拦截 .php/.aspx 等动态后缀，因为这些接口可能返回 m3u8/mp4 视频流。
        if (isHtmlInterfaceUrl(url)) {
            AppLog.put(
                "sniffVideoType: skip HTML interface url, urlPath=${sanitizeUrl(url)}"
            )
            return SniffResult.UNKNOWN
        }

        // P0-1: m3u8 URL 短路检测（URL 以 .m3u8 结尾时跳过 Range 嗅探）
        // 根因：Range 嗅探对 .m3u8 URL 完全不必要（后缀已 100% 确定是 HLS），
        // 且可能消耗 CDN 一次性 token / 触发限流 / 增加 500ms-3s 延迟
        if (url.lowercase().substringBefore("?").substringBefore("#").endsWith(".m3u8")) {
            AppLog.put("sniffVideoType: short-circuit .m3u8 URL, skip Range sniff, urlPath=${sanitizeUrl(url)}")
            // P0-5: HEAD 预检获取重定向后的 finalUrl（避免 HlsMediaSource 创建后再次重定向）
            // video-sniff-403-and-rss-classic-fix 2.5/R-P1-4（AD-03）：auth-retry + Rejected 语义
            // 首次预检用当前 headers；被源站确定性拒绝（Rejected）时用合并嗅探上下文后的头重试一次
            // （Cookie/Referer 可能已由 buildPlayHeaders 过渡版合入 currentHeaders），重试独立 3s 预算（总 6s）
            var preCheckResult: M3u8PreCheckDataSource.PreCheckResult? =
                withTimeoutOrNull(3000L) {
                    M3u8PreCheckDataSource(headers).preCheck(url)
                }
            if (preCheckResult is M3u8PreCheckDataSource.PreCheckResult.Rejected) {
                AppLog.put("sniffVideoType: m3u8 preCheck rejected(403), auth-retry with refreshed cookie")
                // auth-retry：补齐 CookieManager 实时 Cookie（域内）；Referer/UA 沿用现有头（已含嗅探上下文时无需变更）
                val retryHeaders = headers.toMutableMap().apply {
                    val cookie = runCatching {
                        android.webkit.CookieManager.getInstance().getCookie(url)
                    }.getOrNull()
                    if (!cookie.isNullOrBlank()) {
                        put("Cookie", cookie)
                    }
                }
                preCheckResult = withTimeoutOrNull(3000L) {
                    M3u8PreCheckDataSource(retryHeaders).preCheck(url)
                }
            }
            val authRejected = preCheckResult is M3u8PreCheckDataSource.PreCheckResult.Rejected
            val finalUrl = when (preCheckResult) {
                is M3u8PreCheckDataSource.PreCheckResult.Success -> {
                    AppLog.putDebug("sniffVideoType: m3u8 preCheck success, using finalUrl")
                    preCheckResult.finalUrl
                }
                is M3u8PreCheckDataSource.PreCheckResult.Rejected -> {
                    // 仍被拒绝：finalUrl 保留原值，Rejected 标记向上传播（上层触发重嗅，不再静默直连）
                    AppLog.put("sniffVideoType: m3u8 preCheck still rejected after auth-retry, code=${preCheckResult.statusCode}")
                    url
                }
                is M3u8PreCheckDataSource.PreCheckResult.Fail -> {
                    AppLog.putDebug("sniffVideoType: m3u8 preCheck failed: ${preCheckResult.reason}, using original url")
                    url
                }
                null -> {
                    AppLog.putDebug("sniffVideoType: m3u8 preCheck timeout(3s), using original url")
                    url
                }
            }
            return SniffResult(
                contentType = C.TYPE_HLS,
                mimeType = MimeTypes.APPLICATION_M3U8,
                finalUrl = finalUrl,
                preCheckRejected = authRejected
            )
        }

        // W2 / REQ-14 / AD-05：嗅探赛马化（并发上限 2 / 先到结构化取消 / 连续失败 3 次冷却 60s）。
        // 四段确定性短路（本地文件 / 非法 scheme / HTML 接口 / .m3u8 后缀）**原地保留在赛马之前**——
        // 短路是零成本判断，赛马无收益且会引入噪音（详设 §3.3①）。
        // 开关关闭或处于冷却期 ⇒ 回落原串行链（零回归回滚点，行为与改造前逐行等价）。
        if (!AppConfig.sniffRaceEnabled || !sniffRaceLimiter.canRace()) {
            return sniffVideoTypeSerial(url, headers, startTime)
        }
        val raceResult = SniffRace.race(
            url = url,
            headers = headers,
            strategies = buildSniffStrategies(url),
            maxConcurrent = SniffRace.DEFAULT_MAX_CONCURRENT,
            totalTimeoutMs = SNIFF_TIMEOUT_MS
        )
        if (raceResult == null) {
            // 全灭且无兜底（原「Range 超时 + 后缀也 UNKNOWN」等价路径）
            sniffRaceLimiter.noteFailure()
            AppLog.put(
                "sniffVideoType: race all-miss (${System.currentTimeMillis() - startTime}ms), " +
                    "urlPath=${sanitizeUrl(url)}"
            )
            return SniffResult.UNKNOWN
        }
        if (raceResult.value.contentType == SniffResult.TYPE_UNKNOWN) {
            sniffRaceLimiter.noteFailure()
        } else {
            sniffRaceLimiter.noteSuccess()
        }
        // 单条归因日志（验收需要：胜出策略 / 置信度 / 耗时）
        AppLog.put(
            "sniffVideoType: race winner=${raceResult.strategyName}, " +
                "confidence=${raceResult.confidence}, elapsed=${raceResult.elapsedMs}ms, " +
                "contentType=${raceResult.value.contentType}, " +
                "urlPath=${sanitizeUrl(url)}"
        )
        return raceResult.value
    }

    /**
     * W2 / REQ-14：赛马策略集（列表序 = 兜底优先级序）。
     *
     * 1. `Extension`（**零请求**、置信度 0.4、**不抢占**）：URL 后缀兜底。瞬时返回，
     *    作为「Range 慢/失败」时的即刻兜底 —— 正是「省去空等」的收益来源；
     * 2. `Range`（网络、置信度 0.9、可抢占）：既有权威嗅探（7 维交叉验证），内部逻辑零改动；
     * 3. `M3u8PreCheck`（网络、置信度 0.85、可抢占）：**仅在后缀无法判定类型时**加入 ——
     *    针对「伪装成动态接口的 m3u8」（无后缀）提前命中，避免 Range 对 m3u8 清单做容器嗅探而失败；
     *    后缀已可判定（.mp4/.m3u8/...）时不发这一路额外请求（控请求面）。
     */
    private fun buildSniffStrategies(url: String): List<SniffRace.Strategy<SniffResult>> {
        val strategies = ArrayList<SniffRace.Strategy<SniffResult>>(3)
        strategies += SniffRace.strategy(
            name = "Extension",
            confidence = EXTENSION_STRATEGY_CONFIDENCE,
            timeoutMs = EXTENSION_STRATEGY_TIMEOUT_MS,
            canWin = false,
            networkBound = false
        ) { u, _ -> sniffByExtensionFallback(u, "race-extension") }
        strategies += SniffRace.strategy(
            name = "Range",
            confidence = RANGE_STRATEGY_CONFIDENCE,
            timeoutMs = SNIFF_TIMEOUT_MS
        ) { u, h -> sniffWithRangeRequestR4(u, h).takeIf { it.contentType != SniffResult.TYPE_UNKNOWN } }
        if (guessTypeByUrl(url) == null) {
            strategies += SniffRace.strategy(
                name = "M3u8PreCheck",
                confidence = M3U8_PRECHECK_STRATEGY_CONFIDENCE,
                timeoutMs = M3U8_PRECHECK_STRATEGY_TIMEOUT_MS
            ) { u, h -> m3u8PreCheckAsSniffResult(u, h) }
        }
        return strategies
    }

    /**
     * W2 / REQ-14：m3u8 预检结果 → 嗅探结果（预检 Success 即权威：已与 CDN 握手并验证 `#EXTM3U`/MIME）。
     * 其余（Fail / Rejected / 超时）返回 null ⇒ 不参与抢占，也不覆盖 Range 的结论。
     */
    private suspend fun m3u8PreCheckAsSniffResult(
        url: String,
        headers: Map<String, String>
    ): SniffResult? = when (val r = M3u8PreCheckDataSource(headers).preCheck(url)) {
        is M3u8PreCheckDataSource.PreCheckResult.Success -> SniffResult(
            contentType = C.TYPE_HLS,
            mimeType = MimeTypes.APPLICATION_M3U8,
            finalUrl = r.finalUrl
        )
        else -> null
    }

    /**
     * W2 / REQ-14：**原串行链**（改造前逐行语义），供「开关关闭 / 赛马冷却期」回落使用。
     *
     * T1.3: AtomicBoolean 防双回调竞态（withTimeoutOrNull 超时后 sniffWithRangeRequestR4 仍在执行）
     * 场景：超时返回 UNKNOWN 后，sniffWithRangeRequestR4 的 OkHttp execute() 完成，又返回一个结果
     * 修复：只有第一个结果被使用，后续结果被丢弃
     */
    private suspend fun sniffVideoTypeSerial(
        url: String,
        headers: Map<String, String>,
        startTime: Long
    ): SniffResult {
        val hasResult = AtomicBoolean(false)
        return withTimeoutOrNull(SNIFF_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                val result = sniffWithRangeRequestR4(url, headers)
                // T1.3: 只有第一个结果被使用（如果已超时，hasResult 已被设置为 true，此结果被丢弃）
                if (hasResult.compareAndSet(false, true)) {
                    result
                } else {
                    AppLog.putDebug("sniffVideoType: duplicate result discarded (timeout already returned), urlPath=${sanitizeUrl(url)}")
                    SniffResult.UNKNOWN
                }
            }
        } ?: run {
            val elapsed = System.currentTimeMillis() - startTime
            // T1.3: 超时后设置 hasResult 为 true，丢弃 sniffWithRangeRequestR4 后续可能返回的结果
            hasResult.set(true)
            // T1.4: 关键日志改为 AppLog.put（release 包可输出，ai_test 可分析）
            AppLog.put("sniffVideoType: timeout (${elapsed}ms), urlPath=${sanitizeUrl(url)}")
            // T5.3: 超时无内容证据 → URL 后缀兜底
            sniffByExtensionFallback(url, "timeout ${elapsed}ms")
        }
    }

    /**
     * R4-T5+T9: Range 请求 + 7 维度交叉验证 + 重定向感知
     *
     * 关键改造：
     * - Range 从 1KB 提升到 8KB（[MimeSniffer.SNIFF_LENGTH]）
     * - 跟随重定向，记录最终 URL（finalUrl）
     * - 7 维度交叉验证返回 SniffResult
     */
    private suspend fun sniffWithRangeRequestR4(url: String, headers: Map<String, String>): SniffResult {
        val startTime = System.currentTimeMillis()
        return try {
            val requestBuilder = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-${MimeSniffer.SNIFF_LENGTH - 1}")
                .header("Accept", "video/*, application/x-mpegURL, application/dash+xml, */*")
                .header("User-Agent", BROWSER_UA)
            headers.forEach { (key, value) ->
                requestBuilder.header(key, value)
            }
            val request = requestBuilder.build()

            // FR-3: Range 嗅探请求改用 videoStreamClient（强制 HTTP/1.1），规避 ERR_HTTP2_PROTOCOL_ERROR
            videoStreamClient.newCall(request).execute().use { response ->
                // T1.4: execute() 返回后检查 isActive，协程取消时立即返回 UNKNOWN（解决 Bug-3）
                // 注意：execute() 本身无法中断，isActive 检查只能在其返回后生效
                // use 块内 this 变为 Response，需用 coroutineContext.isActive 显式访问协程上下文
                if (!coroutineContext.isActive) {
                    // T1.4: 关键日志改为 AppLog.put（release 包可输出）
                    AppLog.put("sniffWithRangeRequestR4 cancelled after execute, urlPath=${sanitizeUrl(url)}")
                    return@use SniffResult.UNKNOWN
                }
                if (!response.isSuccessful && response.code != 206 && response.code != 200) {
                    // T1.4: 关键日志改为 AppLog.put（release 包可输出）
                    AppLog.put("sniffVideoType: non-200 response: code=${response.code}, urlPath=${sanitizeUrl(url)}")
                    // T5.3: HTTP 错误无内容证据 → URL 后缀兜底
                    return@use sniffByExtensionFallback(url, "http ${response.code}")
                }

                val finalUrl = response.request.url.toString()
                val contentType = response.header("Content-Type")
                val acceptRanges = response.header("Accept-Ranges")
                val supportsRange = acceptRanges?.equals("bytes", ignoreCase = true) == true

                // 维度1: Content-Type 提示（内容证据，WHATWG MIMESNIFF 权威信号之一）
                // T5.3: URL 后缀提示已从 Range 成功路径移除（原维度2/3）——
                // URL 后缀可被伪造（.php 返回 m3u8）或动态化（play.php?id=xxx 返回 mp4），
                // 仅在 Range 请求失败/超时时作最后兜底（见 sniffByExtensionFallback）
                val hintByCt = parseContentTypeToContentType(contentType)

                // 维度2: magic number 检测（强信号，读 body 前 8KB）
                val body = response.body ?: run {
                    // T1.4: 关键日志改为 AppLog.put（release 包可输出）
                    AppLog.put("sniffVideoType: empty body, urlPath=${sanitizeUrl(url)}")
                    // T5.3: 空 body 无内容证据 → URL 后缀兜底
                    return@use sniffByExtensionFallback(url, "empty body")
                }
                val bodyBytes = readLimitedBytes(body, MimeSniffer.SNIFF_LENGTH)

                // 维度4: Magic Number 匹配（强信号）
                val magicMime = MimeSniffer.sniff(bodyBytes)

                // 维度5: 主动 Probe 清单内容（强信号，HLS/DASH）
                val probedContentType = when {
                    MimeSniffer.isReallyM3u8(bodyBytes) -> C.TYPE_HLS
                    MimeSniffer.isReallyMpd(bodyBytes) -> C.TYPE_DASH
                    else -> C.TYPE_OTHER
                }

                // 维度6: MP4 moov 位置检测
                val moovPosition = if (magicMime == MimeTypes.VIDEO_MP4) {
                    MimeSniffer.detectMoovPosition(bodyBytes)
                } else {
                    MoovPosition.UNKNOWN
                }

                // 综合判定：内容证据优先且排他（WHATWG MIMESNIFF：probe > magic > Content-Type）
                // T5.3: Range 成功后不再使用 URL 后缀——内容证据未识别即返回 UNKNOWN，
                // 由 Exo2MediaPlayer 降级链（HLS→DASH→Progressive）渐进接管（对齐浏览器渐进增强），
                // 避免"200 但 body 是 HTML 错误页 + URL 含 .mp4"场景下后缀误判误导 MediaSource 选择
                val (finalContentType, finalMimeType) = when {
                    // 强信号1：主动 Probe 清单内容
                    probedContentType == C.TYPE_HLS -> C.TYPE_HLS to MimeTypes.APPLICATION_M3U8
                    probedContentType == C.TYPE_DASH -> C.TYPE_DASH to MimeTypes.APPLICATION_MPD
                    // 强信号2：Magic Number 匹配
                    magicMime != null -> inferContentTypeByMimeType(magicMime) to magicMime
                    // 内容证据3：Content-Type 提示
                    hintByCt != null -> hintByCt to contentType
                    // 全部未识别 → UNKNOWN（降级链接管）
                    else -> SniffResult.TYPE_UNKNOWN to null
                }

                val elapsed = System.currentTimeMillis() - startTime
                // T1.4: 关键日志改为 AppLog.put（release 包可输出，ai_test 可分析嗅探成功率）
                AppLog.put(
                    "sniffVideoType: success, contentType=$finalContentType, mimeType=$finalMimeType, " +
                        "moov=$moovPosition, range=$supportsRange, magic=$magicMime, " +
                        "ct=$contentType, elapsed=${elapsed}ms, urlPath=${sanitizeUrl(url)}"
                )

                SniffResult(
                    contentType = finalContentType,
                    mimeType = finalMimeType,
                    moovPosition = moovPosition,
                    supportsRange = supportsRange,
                    finalUrl = finalUrl
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // 重新抛出，保留协程取消语义（项目铁证）
        } catch (e: java.io.IOException) {
            AppLog.put("sniffVideoType: range request io failed: ${e.javaClass.simpleName}, urlPath=${sanitizeUrl(url)}")
            // T5.3: 网络层失败无内容证据 → URL 后缀兜底
            sniffByExtensionFallback(url, "io failed")
        }
    }

    /**
     * P0-1: HTML 接口预判（嗅探成功率优化，2026-07-28）
     *
     * 根因：002日志铁证——HTML 播放页接口（如 /play/xxx.html）返回 text/html 页面，
     * Range 请求得到 HTML 而非视频流，嗅探必然失败并消耗完整超时时长（6653ms）。
     *
     * 判定规则：URL 路径最后一段以 .html/.htm 结尾时，认定为 HTML 接口。
     * - 视频流接口通常无后缀（/play/stream?id=xxx）或视频后缀（.m3u8/.mp4/.flv）
     * - .html/.htm 后缀的 URL 极不可能直接返回视频流
     * - 动态后缀（.php/.aspx/.jsp）不拦截，因为这些接口可能返回 m3u8/mp4
     *
     * @param url 视频 URL（完整，含 query）
     * @return true 表示是 HTML 接口应跳过 Range 嗅探，false 表示正常嗅探
     */
    private fun isHtmlInterfaceUrl(url: String): Boolean {
        val path = url.lowercase().substringBefore("?").substringBefore("#")
        val lastSegment = path.substringAfterLast("/")
        return lastSegment.endsWith(".html") || lastSegment.endsWith(".htm")
    }

    /**
     * T5.3: URL 后缀兜底嗅探（仅在 Range 请求失败/超时/无内容证据时使用）
     *
     * WHATWG MIMESNIFF 规范精神：资源类型由 Content-Type + 内容 sniffing（magic number/probe）确定，
     * 不应依赖 URL 后缀。URL 后缀仅在无法获取任何内容证据（网络层失败/超时/空 body/HTTP 错误）时
     * 作为最后手段——此时给 ExoPlayer 一个明确的起始 MediaSource 方向，仍优于直接 UNKNOWN。
     *
     * @param url 视频 URL（原始 URL，未重定向）
     * @param reason 兜底触发原因（日志用）
     * @return 后缀命中的 SniffResult（含标准 mimeType），未命中返回 UNKNOWN
     */
    private fun sniffByExtensionFallback(url: String, reason: String): SniffResult {
        val byExt = guessTypeByUrl(url)
        if (byExt == null) {
            AppLog.put("sniffVideoType: extension fallback miss ($reason), urlPath=${sanitizeUrl(url)}")
            return SniffResult.UNKNOWN
        }
        val mime = when (byExt) {
            C.TYPE_HLS -> MimeTypes.APPLICATION_M3U8
            C.TYPE_DASH -> MimeTypes.APPLICATION_MPD
            else -> null
        }
        AppLog.put(
            "sniffVideoType: extension fallback hit ($reason), contentType=$byExt, " +
                "urlPath=${sanitizeUrl(url)}"
        )
        return SniffResult(contentType = byExt, mimeType = mime, finalUrl = url)
    }

    /**
     * R4-T5: 根据 MIME 类型推断 ExoPlayer contentType
     *
     * HLS: application/x-mpegURL → C.TYPE_HLS
     * DASH: application/dash+xml → C.TYPE_DASH
     * 其他视频/音频 → C.TYPE_OTHER（走 ProgressiveMediaSource）
     */
    private fun inferContentTypeByMimeType(mimeType: String?): Int {
        if (mimeType == null) return SniffResult.TYPE_UNKNOWN
        return when (mimeType) {
            MimeTypes.APPLICATION_M3U8 -> C.TYPE_HLS
            MimeTypes.APPLICATION_MPD -> C.TYPE_DASH
            else -> C.TYPE_OTHER
        }
    }

    /**
     * R4-T5 / V-P1-1: 根据 URL 后缀启发式推断 ExoPlayer contentType（公共入口）
     *
     * 清单类：
     * .m3u8 → C.TYPE_HLS
     * .mpd → C.TYPE_DASH
     * .ism/.ismv → C.TYPE_SS（Smooth Streaming）
     *
     * 直链类（V-P1-1 补齐，UNKNOWN 降级链首试 Progressive，避免 MANIFEST_MALFORMED 试错）：
     * 视频 .mp4/.mkv/.webm/.flv/.avi/.mov/.ts/.m2ts + 音频 .mp3/.m4a/.aac/.flac → C.TYPE_OTHER
     *
     * 其他 → null（未识别）
     *
     * 调用方：sniffByExtensionFallback（兜底识别）、buildFallbackTypes（UNKNOWN 降级链排序），
     * 单一逻辑源避免两处后缀表漂移。
     */
    fun guessTypeByUrl(url: String): Int? {
        val path = url.lowercase().substringBefore("?").substringBefore("#")
        return when {
            path.endsWith(".m3u8") -> C.TYPE_HLS
            path.endsWith(".mpd") -> C.TYPE_DASH
            path.endsWith(".ism") || path.endsWith(".ismv") -> C.TYPE_SS
            path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".webm")
                || path.endsWith(".flv") || path.endsWith(".avi") || path.endsWith(".mov")
                || path.endsWith(".ts") || path.endsWith(".m2ts")
                || path.endsWith(".mp3") || path.endsWith(".m4a") || path.endsWith(".aac")
                || path.endsWith(".flac") -> C.TYPE_OTHER
            else -> null
        }
    }

    /**
     * R4-T5: 解析 Content-Type 头为 ExoPlayer contentType
     *
     * 与 [parseContentType] 不同，本函数返回 ExoPlayer contentType（C.TYPE_HLS 等），
     * 而非 mimeType 字符串。用于 7 维度交叉验证的维度1。
     */
    private fun parseContentTypeToContentType(contentType: String?): Int? {
        if (contentType.isNullOrBlank()) return null
        val lower = contentType.lowercase().substringBefore(";").trim()
        return when {
            lower == "application/x-mpegurl" || lower == "application/vnd.apple.mpegurl" -> C.TYPE_HLS
            lower == "application/dash+xml" -> C.TYPE_DASH
            lower.startsWith("video/") || lower.startsWith("audio/") -> C.TYPE_OTHER
            else -> null
        }
    }

    fun createMediaItem(
        url: String,
        headers: Map<String, String>,
        sniffedMimeType: String? = null
    ): MediaItem {
        val mediaItemBuilder = MediaItem.Builder().setUri(url)
        // exoplayer-resilience Layer 1：优先级链 sniffedMimeType > getMimeType(url) > null
        // - sniffedMimeType 来自 sniffMimeType 预嗅探（Content-Type + magic number）
        // - getMimeType(url) 是 URL 后缀检测（含 format=m3u8 query 标识）
        // - null 让 ExoPlayer 内置 Extractor.sniff() 自动推断
        val suffixMime = getMimeType(url)
        val mimeType = sniffedMimeType ?: suffixMime
        if (mimeType != null) {
            mediaItemBuilder.setMimeType(mimeType)
        }
        // headers 通过 setDefaultHeaders 注入 okhttpDataFactory（等价原 SPLIT_TAG 路径的行为）
        if (headers.isNotEmpty()) {
            setDefaultHeaders(headers)
        }
        val source = when {
            sniffedMimeType != null -> "sniffed"
            suffixMime != null -> "suffix"
            else -> "auto"
        }
        AppLog.putDebug("createMediaItem: mimeType=$mimeType, source=$source, headerKeys=${headers.keys}, urlPath=${sanitizeUrl(url)}")
        return mediaItemBuilder.build()
    }

    /**
     * exoplayer-resilience Layer 1：预嗅探 mimeType
     *
     * 5 级识别优先级链（参考 Chromium 多级识别策略）：
     * - L1: 缓存命中（[MimeSnifferCache]）→ 直接返回（0 延迟）
     * - L2: 服务端 Content-Type 有效（video 子类型 或 application/x-mpegURL）→ 使用
     * - L3: magic number 检测（[MimeSniffer.sniff] 读 body 前 1KB）
     * - L4: URL 后缀检测 [getMimeType]（调用方做，本函数不返回 L4 结果）
     * - L5: 默认推断 → 返回 null 让 ExoPlayer 内置 Extractor.sniff() 兜底
     *
     * 超时控制：3 秒（withTimeoutOrNull），避免阻塞 UI
     * 协程调度：网络请求在 [Dispatchers.IO] 执行
     *
     * @param url 视频 URL（完整，含 query）
     * @param headers 请求头（如 Referer/User-Agent/Cookie，用于防盗链场景）
     * @return 嗅探得到的 mimeType（如 "application/x-mpegURL"），或 null 表示未识别
     */
    suspend fun sniffMimeType(url: String, headers: Map<String, String>): String? {
        val startTime = System.currentTimeMillis()
        // L1: 缓存命中检查
        MimeSnifferCache.get(url)?.let { cacheValue ->
            // 缓存命中（含"嗅探过但未识别"的 null 标记，避免重复嗅探失败）
            AppLog.putDebug("SniffingMime: cache hit, mimeType=${cacheValue.mimeType}, urlPath=${sanitizeUrl(url)}")
            return cacheValue.mimeType
        }

        // L2-L3: Range 请求 + Content-Type + magic number（前置帧分析，优先）
        // 设计理由（用户2026-07-26 11:11反馈强化嗅探能力）：
        // - URL 后缀可被伪造（如 .php 返回 m3u8 流）或动态 URL（play.php?id=xxx 返回 mp4）
        // - 前置帧分析（magic number）读取实际内容前 1KB，更可靠
        // - 嗅探准确性 > 性能（200-500ms 延迟可接受）
        val sniffedMime = withTimeoutOrNull(SNIFF_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                sniffWithRangeRequest(url, headers)
            }
        }  // 超时返回 null

        // 前置帧分析成功 → 缓存并返回
        if (sniffedMime != null) {
            MimeSnifferCache.put(url, sniffedMime)
            val elapsed = System.currentTimeMillis() - startTime
            // T1.4: 关键日志改为 AppLog.put（release 包可输出，ai_test 可分析嗅探成功率）
            AppLog.put("SniffingMime: sniffed mimeType=$sniffedMime, elapsed=${elapsed}ms, urlPath=${sanitizeUrl(url)}")
            return sniffedMime
        }

        // L4: URL 后缀检测（仅作为前置帧分析失败/超时时的兜底）
        // 设计理由：前置帧分析失败可能是网络问题或服务端不支持 Range，URL 后缀作为最后兜底
        val suffixMime = getMimeType(url)
        if (suffixMime != null) {
            // P0-2 修复（2026-07-26）：URL 后缀兜底结果不缓存
            // 原因：前置帧分析失败可能是临时网络问题，若缓存 URL 后缀结果，1小时内即使网络恢复也不会重新嗅探
            // 权衡：确实无法识别的视频每次都走 URL 后缀兜底，但避免临时失败后错误缓存导致无法播放
            // T1.4: 关键日志改为 AppLog.put（release 包可输出）
            AppLog.put("SniffingMime: suffix fallback (range failed/timeout), mimeType=$suffixMime, urlPath=${sanitizeUrl(url)}")
            return suffixMime
        }

        // L5: 默认推断 → null（让 ExoPlayer 内置 Extractor.sniff() 兜底）
        // P0-2 修复（2026-07-26）：不缓存 null，避免临时网络失败后1小时内无法重试嗅探
        // 用户核心诉求是"能播放"，宁可多嗅探一次也不要因缓存null导致无法播放
        val elapsed = System.currentTimeMillis() - startTime
        // T1.4: 关键日志改为 AppLog.put（release 包可输出，ai_test 可分析嗅探失败原因）
        AppLog.put("SniffingMime: sniff failed (null), elapsed=${elapsed}ms, urlPath=${sanitizeUrl(url)}")
        return null
    }

    /**
     * 发送 Range: bytes=0-1023 请求，按 L2→L3→L5 顺序识别 mimeType
     *
     * 安全性：用 [readLimitedBytes] 最多读取 1024 字节，避免服务端不支持 Range 时返回完整大文件导致 OOM
     *
     * P0-5 修复（2026-07-26）：原用 runCatching 会吞掉 CancellationException，破坏协程结构化取消
     * （项目铁证：runCatching会吞CancellationException导致协程取消误报，必须重新抛出）
     * 改用显式 try/catch：CancellationException 重新抛出，IOException 记录日志返回 null
     */
    private suspend fun sniffWithRangeRequest(url: String, headers: Map<String, String>): String? {
        return try {
            val requestBuilder = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-1023")
                .header("Accept", "video/*, application/x-mpegURL, */*")
            // 注入用户请求头（如 Referer/User-Agent/Cookie，用于防盗链场景）
            headers.forEach { (key, value) ->
                requestBuilder.header(key, value)
            }
            val request = requestBuilder.build()

            // FR-3: Range 嗅探请求改用 videoStreamClient（强制 HTTP/1.1），规避 ERR_HTTP2_PROTOCOL_ERROR
            videoStreamClient.newCall(request).execute().use { response ->
                // T1.4: execute() 返回后检查 isActive，协程取消时立即返回 null
                // use 块内 this 变为 Response，需用 coroutineContext.isActive 显式访问协程上下文
                if (!coroutineContext.isActive) {
                    AppLog.putDebug("sniffWithRangeRequest cancelled after execute, urlPath=${sanitizeUrl(url)}")
                    return@use null
                }
                if (!response.isSuccessful && response.code != 206 && response.code != 200) {
                    AppLog.putDebug("SniffingMime: non-200 response: code=${response.code}, urlPath=${sanitizeUrl(url)}")
                    return@use null
                }
                // L2: 服务端 Content-Type 检测
                val contentType = response.header("Content-Type")
                val mimeFromContentType = parseContentType(contentType)
                if (mimeFromContentType != null) {
                    AppLog.putDebug("SniffingMime: L2 content-type hit: mime=$mimeFromContentType, contentType=$contentType, urlPath=${sanitizeUrl(url)}")
                    return@use mimeFromContentType
                }
                // L3: magic number 检测（读 body 前 1KB，限制最多 1024 字节避免 OOM）
                val body = response.body ?: run {
                    AppLog.putDebug("SniffingMime: empty body, urlPath=${sanitizeUrl(url)}")
                    return@use null
                }
                val bodyBytes = readLimitedBytes(body, 1024)
                val magicMime = MimeSniffer.sniff(bodyBytes)
                if (magicMime == null) {
                    AppLog.putDebug("SniffingMime: L3 magic not matched: bytes=${bodyBytes.size}, contentType=$contentType, urlPath=${sanitizeUrl(url)}")
                } else {
                    AppLog.putDebug("SniffingMime: L3 magic hit: mime=$magicMime, bytes=${bodyBytes.size}, urlPath=${sanitizeUrl(url)}")
                }
                magicMime
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // 重新抛出，保留协程取消语义（项目铁证）
        } catch (e: java.io.IOException) {
            AppLog.put("SniffingMime: range request io failed: ${e.javaClass.simpleName}, urlPath=${sanitizeUrl(url)}")
            null
        }
    }

    /**
     * 从 ResponseBody 读取最多 [maxBytes] 字节，避免服务端不支持 Range 返回完整大文件导致 OOM
     *
     * T2.6: 改为 suspend 函数，循环内检查 [isActive]，协程取消时立即停止读取（解决 Bug-21）
     */
    private suspend fun readLimitedBytes(body: okhttp3.ResponseBody, maxBytes: Int): ByteArray {
        val stream = body.byteStream()
        val buffer = ByteArray(maxBytes)
        var totalRead = 0
        while (totalRead < maxBytes) {
            // T2.6: 循环内检查 isActive，协程取消时立即停止读取
            // 注：readLimitedBytes 是 suspend 函数，coroutineContext 可直接访问
            if (!coroutineContext.isActive) {
                AppLog.putDebug("readLimitedBytes cancelled in readLoop, totalRead=$totalRead")
                break
            }
            val read = stream.read(buffer, totalRead, maxBytes - totalRead)
            if (read <= 0) break
            totalRead += read
        }
        return if (totalRead == maxBytes) buffer else buffer.copyOf(totalRead)
    }

    /**
     * 解析 HTTP Content-Type 头，返回 ExoPlayer 兼容的 mimeType（仅识别视频流类型）
     *
     * - L2 命中条件：Content-Type 是 video 子类型 或 application/x-mpegURL 或 application/vnd.apple.mpegurl
     * - 忽略非视频 Content-Type（如 text/html 表示返回的是错误页面，不视为有效）
     */
    private fun parseContentType(contentType: String?): String? {
        if (contentType.isNullOrBlank()) return null
        val lower = contentType.lowercase().substringBefore(";").trim()
        return when {
            // 通用视频类型
            lower.startsWith("video/") -> {
                // 映射常见 video/* 到 ExoPlayer MimeTypes
                when (lower) {
                    "video/mp4" -> MimeTypes.VIDEO_MP4
                    "video/mp2t" -> MimeTypes.VIDEO_MP2T
                    "video/webm" -> MimeTypes.VIDEO_WEBM
                    "video/x-matroska" -> MimeTypes.VIDEO_MATROSKA
                    "video/x-flv" -> "video/x-flv"
                    else -> lower  // 其他 video/* 直接返回原值
                }
            }
            // HLS m3u8（多种变体）
            lower == "application/x-mpegurl" || lower == "application/vnd.apple.mpegurl" ->
                MimeTypes.APPLICATION_M3U8
            // DASH mpd
            lower == "application/dash+xml" -> MimeTypes.APPLICATION_MPD
            // 非视频类型（text/html、application/json 等）视为无效
            else -> null
        }
    }

    /**
     * 嗅探超时：5 秒（P1-3 嗅探能力恢复，2026-07-31）
     *
     * 历史：
     * - T1.3（2026-07-26）：从 3s 提升至 5s 解决弱网场景嗅探未完成被超时
     * - P0-2（2026-07-28）：从 5s 降回 3s（HTML 接口误入嗅探通道消耗完整 5s 超时）
     * - P1-3（2026-07-31）：从 3s 恢复到 5s
     *
     * P1-3 恢复根因（用户反馈"7月30日12点后嗅探能力明显减弱"）：
     *   3s 超时在弱网场景下 Range 请求未完成即被超时，导致嗅探成功率下降。
     *   P0-1 的 HTML 接口预判拦截（isHtmlInterfaceUrl + .html 后缀短路）已解决
     *   HTML 接口误入嗅探通道的问题，不再需要靠缩短超时来避免 HTML 嗅探消耗。
     *   m3u8 URL 短路检测（url.endsWith(".m3u8")）也减少了非必要嗅探。
     *   因此恢复到 5s 提升弱网场景嗅探成功率，HTML 误入问题由前置拦截解决。
     *
     * 超时后由 sniffByExtensionFallback（URL 后缀兜底）+ buildFallbackTypes（HLS 优先）接管。
     */
    private const val SNIFF_TIMEOUT_MS = 5000L

    // W2 / REQ-14：赛马策略参数（置信度仅用于归因与兜底排序；耗时预算各自独立）
    /** 后缀兜底策略：零请求、瞬时（80ms 仅作上限保护），不抢占。 */
    private const val EXTENSION_STRATEGY_TIMEOUT_MS = 80L
    private const val EXTENSION_STRATEGY_CONFIDENCE = 0.4f

    /** 权威 Range 嗅探：与既有串行链同预算、同逻辑。 */
    private const val RANGE_STRATEGY_CONFIDENCE = 0.9f

    /** m3u8 预检：与既有 `.m3u8` 短路分支的 preCheck 同预算（3s）。 */
    private const val M3U8_PRECHECK_STRATEGY_TIMEOUT_MS = 3000L
    private const val M3U8_PRECHECK_STRATEGY_CONFIDENCE = 0.85f

    /**
     * URL 后缀→MIME 类型映射
     * app-stability-round2 P1-3 修复：createMediaItem 不再拼接 SPLIT_TAG 到 URI，
     * 改用 setMimeType 显式声明类型，避免 DefaultMediaSourceFactory 的 URL 后缀检测被破坏
     */
    private fun getMimeType(url: String): String? {
        val lower = url.lowercase()
        val path = lower.substringBefore("?").substringBefore("#")
        return when {
            path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            path.endsWith(".mp4") -> MimeTypes.VIDEO_MP4
            path.endsWith(".mkv") -> MimeTypes.VIDEO_MATROSKA
            path.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
            path.endsWith(".flv") -> "video/x-flv"
            path.endsWith(".ts") -> MimeTypes.VIDEO_MP2T
            // HLS query 标识检测：仅匹配显式 query 参数 format=m3u8 / type=m3u8
            // 修复 R3 BUG：原 lower.contains("m3u8") 会匹配 URL 任意位置（含 query 参数中的 URL 值），
            //   误判场景：/Player/Play.php?uu=https%3A%2F%2Fplay2.xxx.top%2F...%2Findex.m3u8
            //   Play.php 是 PHP 代理页面，但 query 参数 uu 含 index.m3u8 字符串触发误判，
            //   ExoPlayer 用 HLS 解析器解析非 m3u8 内容抛 3002 ERROR_CODE_PARSING_MANIFEST_MALFORMED
            // 修复策略：
            //   1. 删除 lower.contains("m3u8") 和 lower.contains("index.m3u8") 过宽匹配
            //   2. 仅保留显式 format=m3u8 / type=m3u8 query 标识（站点B ruleContent 用）
            //   3. path 后缀场景已由 path.endsWith(".m3u8") 覆盖
            // 铁证：logcat L7164-L7218 SniffingMime cache hit mimeType=application/x-mpegURL
            //       + ParserException "Input does not start with the #EXTM3U header"
            lower.contains("format=m3u8") || lower.contains("type=m3u8") -> MimeTypes.APPLICATION_M3U8
            // 修复 3002 PARSING_CONTAINER_MALFORMED BUG：
            // 原代码 else 兜底强制返回 APPLICATION_M3U8，导致任何识别不出的 URL
            // （如 play.php?id=xxx 返回 mp4 流）被误判为 m3u8，
            // HlsMediaSource 期望 #EXTM3U 头但实际是 mp4 ftyp 二进制盒，抛 3002
            // 修复：返回 null 让 ExoPlayer 根据 URL 后缀 + HTTP Content-Type 自动推断
            // 安全性：L57 已有 if (mimeType != null) 判空，返回 null 不影响调用方
            else -> null
        }
    }

    /**
     * URL 脱敏：用于日志输出，只保留 path 前40字符（符合 P0 安全规范）
     *
     * P0-6 修复（2026-07-26）：从 private 改为 public，供 Exo2MediaPlayer 复用，
     * 统一URL脱敏入口，避免完整URL输出到AppLog触发违禁词审查中断
     */
    fun sanitizeUrl(url: String): String {
        return try {
            val u = java.net.URL(url)
            "path=${u.path?.take(40)}"
        } catch (e: Exception) {
            "raw=${url.take(30)}"
        }
    }

    fun createHttpExoPlayer(context: Context): ExoPlayer {
        return ExoPlayer.Builder(context).setLoadControl(
            DefaultLoadControl.Builder().setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS / 10,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS / 10
            ).build()
        ).setMediaSourceFactory(
            DefaultMediaSourceFactory(
                context,
                DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
            ).setDataSourceFactory(resolvingDataSource)
                .setLiveTargetOffsetMs(5000)
        ).build()
    }


    val resolvingDataSource: ResolvingDataSource.Factory by lazy {
        ResolvingDataSource.Factory(cacheDataSourceFactory) {
            var res = it

            if (it.uri.toString().contains(SPLIT_TAG)) {
                val urls = it.uri.toString().split(SPLIT_TAG)
                val url = urls[0]
                res = res.withUri(Uri.parse(url))
                try {
                    val headers: Map<String, String> = GSON.fromJson(urls[1], mapType)
                    okhttpDataFactory.setDefaultRequestProperties(headers)
                    // P0 日志规范：Header 注入成功确认（Tag=ExoHeader）
                    AppLog.putDebug("ExoHeader: Headers injected via SPLIT_TAG, keys=${headers.keys}, urlLen=${url.length}")
                } catch (e: Exception) {
                    // P0 日志规范：错误处理路径必须有日志
                    AppLog.putError("ExoHeader: Failed to parse headers from SPLIT_TAG", e)
                }
            }

            res

        }
    }


    /**
     * 支持缓存的DataSource.Factory
     */
    val cacheDataSourceFactory: DataSource.Factory by lazy {
        // P0: 优先使用 Cronet 数据源（TLS 指纹与 Chrome 一致，解决 CDN TLS 指纹检测）
        // 铁证：站点A m3u8，OkHttp TLS 被 CDN 重置（SSLHandshakeException: Connection reset by peer），
        // 但 Cronet（BoringSSL）能成功握手。项目 HTTP 请求层已用 Cronet 成功获取详情页。
        // 回退：Cronet 不可用时用 OkHttp（保持兼容性）
        // video-sniff-403-and-rss-classic-fix 4.8c（Z7，design §3.7）澄清：
        // cronet 用户开关（AppConfig.isCronet，PreferKey.cronet 默认 false）仅控制 OkHttp builder
        // 是否装配 Cronet interceptor（爬取链路），与视频链路此处 cronetDataFactory 无条件装配
        // （cronetEngine 非空即优先 Cronet、失败回退 OkHttp）是两条独立逻辑，互不联动。
        // video-proxy-m3u8-403 AD-04（2026-09-12）：Cronet TLS 握手被部分 CDN 拒绝（ERR_SSL_VERSION_OR_CIPHER_MISMATCH，
        // 实测：壳域名可过、站点B 密钥域名被拒），与既有铁证（部分 CDN 拒 OkHttp 仅 Cronet 能过）方向相反——
        // 两类 CDN 各拒一种 TLS 栈。Cronet 优先 + TLS 失败自动回退 OkHttp，单请求粒度、无状态
        val upstreamFactory: DataSource.Factory = cronetDataFactory?.let { cronet ->
            TlsFallbackDataSourceFactory(cronet, okhttpDataFactory)
        } ?: okhttpDataFactory
        // P0-3-cache-play 接线：视频缓存总开关（VideoPlay.videoCache，默认开启）
        // 开启：走 CacheDataSource + SimpleCache 边下边缓存（回看重播零流量、可秒拖缓存区间）
        // 关闭：仅直连播放，不写磁盘缓存（省存储）
        // 注意：cacheDataSourceFactory 为 lazy 单例，修改开关需重启 App 生效
        if (!VideoPlay.videoCache) {
            // P2 修复：用 DefaultDataSource 包装，支持 file:// 等本地协议
            DefaultDataSource.Factory(appCtx, upstreamFactory)
        } else {
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(DefaultDataSource.Factory(appCtx, upstreamFactory))
                .setCacheReadDataSourceFactory(FileDataSource.Factory())
                .setCacheWriteDataSinkFactory(
                    CacheDataSink.Factory()
                        .setCache(cache)
                        .setFragmentSize(CacheDataSink.DEFAULT_FRAGMENT_SIZE)
                )
        }
    }

    /**
     * P0: Cronet DataSource.Factory（TLS 指纹与 Chrome 一致，解决 CDN TLS 指纹检测）
     *
     * 根因：部分视频 CDN 使用 TLS 指纹检测（JA3），OkHttp 的 conscrypt TLS ClientHello
     * 被识别为非浏览器客户端，连接被重置（SSLHandshakeException: Connection reset by peer）。
     * Cronet 使用 BoringSSL（与 Chrome 浏览器相同的 TLS 栈），TLS 指纹与 Chrome 一致，
     * CDN 不会拒绝。
     *
     * 回退：cronetEngine 为 null 时（Cronet 初始化失败），返回 null，cacheDataSourceFactory 回退到 OkHttp
     */
    private val cronetDataFactory: androidx.media3.datasource.cronet.CronetDataSource.Factory? by lazy {
        val engine = io.legado.app.lib.cronet.cronetEngine
        if (engine == null) {
            AppLog.put("ExoPlayerHelper: cronetEngine is null, fallback to OkHttp")
            null
        } else {
            // P0-4: 注入防盗链默认请求头（UA + Referer 兜底）
            // 说明：setDefaultHeaders 被调用时会覆盖此默认值；此默认值仅用于未被 setDefaultHeaders 显式覆盖的请求
            androidx.media3.datasource.cronet.CronetDataSource.Factory(engine, java.util.concurrent.Executors.newSingleThreadExecutor())
                .setDefaultRequestProperties(buildAntiLeechHeaders())
        }
    }

    /**
     * Okhttp DataSource.Factory
     *
     * P2-C 修复：强制 HTTP/1.1，规避 HTTP/2 PROTOCOL_ERROR
     * 根因：部分视频 CDN 的 HTTP/2 实现对 mp4 流式响应处理有 bug，
     *      主动发送 RST_STREAM(PROTOCOL_ERROR)，导致 OkHttp 抛 StreamResetException，
     *      视频播放失败（ERROR_CODE_IO_NETWORK_CONNECTION_FAILED 2001）。
     * 证据：appLog-26-07-12 + logcat 中 22 条 StreamResetException
     * 方案：ExoPlayer 客户端限制 protocols=[HTTP_1_1]，绕开 HTTP/2 协商。
     * 影响范围：仅视频播放，不影响书源/订阅源请求（仍用默认 OkHttp + Cronet）。
     * 已知上限：HTTP/1.1 无多路复用，但视频流是单长连接，性能影响可忽略。
     */
    private val okhttpDataFactory by lazy {
        val client = okHttpClient.newBuilder()
            .callTimeout(0, TimeUnit.SECONDS)
            // 缓冲速度优化（P0）：超时配置统一 10s/15s/15s，容忍慢速 CDN
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))  // P2-C: 强制 HTTP/1.1
            .followRedirects(true)  // R4-T6: 跟随重定向，感知最终 URL
            .build()
        OkHttpDataSource.Factory(client)
            .setUserAgent(BROWSER_UA)  // R4-T6: 浏览器 UA，提升 CDN 抓取成功率
            // P0-4: 注入防盗链默认请求头（Referer + UA 兜底，解决 CDN 防盗链 403）
            // 说明：setDefaultHeaders 被调用时会覆盖此默认值；此默认值仅用于未被显式覆盖的请求
            .setDefaultRequestProperties(buildAntiLeechHeaders())
            // P1: 移除 setCacheControl —— 视频缓存由 SimpleCache 层处理，
            // Cache-Control: max-age=86400 对密钥请求和 TS 分片请求无价值，
            // 且可能干扰 CDN 行为（部分 CDN 不期望客户端发送 Cache-Control 请求头）
            // 注：OkHttpDataSource.Factory 无 setAllowCrossProtocolRedirects 方法，
            // 跨协议重定向由 OkHttp.followSslRedirects(true) 控制（已在 okHttpClient 中配置）
    }

    /**
     * 按请求头构建 OkHttp DataSource.Factory（播放/缓存链路的上游工厂）。
     * 与 [okhttpDataFactory] 共用同一份客户端配置（HTTP/1.1 + 浏览器 UA + 防盗链默认头），
     * 再把调用方传入的 headers 覆盖上去（Referer/Cookie 等防盗链请求头优先）。
     */
    private fun okhttpDataFactory(headers: Map<String, String>): OkHttpDataSource.Factory {
        val client = okHttpClient.newBuilder()
            .callTimeout(0, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .followRedirects(true)
            .build()
        return OkHttpDataSource.Factory(client)
            .setUserAgent(BROWSER_UA)
            .setDefaultRequestProperties(buildAntiLeechHeaders() + headers)
    }

    /**
     * P0-4: 构建防盗链默认请求头（User-Agent + Referer 兜底）
     *
     * 用于 cronetDataFactory / okhttpDataFactory lazy 初始化时的默认请求头，确保即使
     * setDefaultHeaders 未被调用（如嗅探阶段、直链播放场景），请求也带浏览器 UA +
     * 当前播放页 Referer，突破 CDN 防盗链（403/404）。
     *
     * 优先级：
     * 1. VideoPlay.currentPlayHeaders 中的 Referer（订阅源规则配置，最准确）
     * 2. 无 Referer（仅注入 UA，避免错误 Referer 干扰）
     *
     * 说明：
     * - User-Agent 始终注入 BROWSER_UA（模拟 Chrome 120 移动版）
     * - setDefaultHeaders 被调用时会覆盖此默认值（覆盖式更新，非追加）
     * - 每次 lazy 初始化时读取一次 VideoPlay.currentPlayHeaders（后续 setDefaultHeaders 刷新）
     *
     * 成熟方案参考：ExoPlayer 官方文档 "Playing media with anti-leech headers" +
     *   Chromium MediaDataSource 默认 UA 策略
     */
    private fun buildAntiLeechHeaders(): Map<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to BROWSER_UA
        )
        // 优先从 currentPlayHeaders 提取 Referer（订阅源规则配置）
        VideoPlay.currentPlayHeaders?.let { playHeaders ->
            playHeaders.entries.firstOrNull {
                it.key.equals("Referer", ignoreCase = true)
            }?.let { (key, value) ->
                if (value.isNotBlank()) {
                    headers["Referer"] = value
                }
            }
        }
        // P0 日志规范：只记录是否注入，不记录 Referer 完整值（防止泄漏）
        AppLog.putDebug("buildAntiLeechHeaders: hasReferer=${headers.containsKey("Referer")}, headerKeys=${headers.keys}")
        return headers
    }

    /**
     * R5 Header 修复：设置 okhttpDataFactory 的默认请求头
     *
     * Exo2MediaPlayer.prepareAsyncInternal 使用 no-op resolver（不处理 Header），
     * 但其 MediaSource 使用 cacheDataSourceFactory（upstream 是 okhttpDataFactory）。
     * 在 ExoPlayerManager.initVideoPlayer 中 setDataSource 前调用此方法，
     * 可确保 Header 到达 HTTP 请求（解决 CDN 防盗链 404 问题）。
     *
     * 简化说明：单播放器场景，Header 覆盖不影响 | 已知上限：多播放器并发会互相覆盖 | 升级路径：改用 per-request Header 注入
     */
    fun setDefaultHeaders(headers: Map<String, String>) {
        okhttpDataFactory.setDefaultRequestProperties(headers)
        // P0: 同时注入 Cronet 数据源（解决 CDN TLS 指纹检测）
        cronetDataFactory?.setDefaultRequestProperties(headers)
    }

    /**
     * P1-C 修复：清除视频缓存（HTTP 416 错误时调用）
     *
     * 根因：ExoPlayer 的 Range 请求与服务端缓存状态不匹配，服务端返回 416 Range Not Satisfiable
     * 方案：清除缓存分片后重试，避免 Range 请求冲突
     * 影响范围：清除所有视频缓存（416 不常见，清除全部可接受）
     */
    fun clearCache() {
        try {
            val keys = cache.keys.toList()
            keys.forEach { cache.removeResource(it) }
            AppLog.put("清除视频缓存: count=${keys.size}")
        } catch (e: Exception) {
            AppLog.put("清除视频缓存失败", e)
        }
    }

    /**
     * Exoplayer 内置的缓存
     * P0-3：缓存容量从 VideoPlay.videoCacheSize 读取（首次 lazy 初始化时生效，修改后需重启 App）
     * R3：容量范围保护从 50-500MB 调整为 50-2048MB（支持 HIGH 档位 1GB 缓存）
     */
    internal val cache: Cache by lazy {
        val databaseProvider = StandaloneDatabaseProvider(appCtx)
        val cacheSizeMb = VideoPlay.videoCacheSize.coerceIn(50, 2048)  // R3: 容量范围保护 50-2048MB
        return@lazy SimpleCache(
            //Exoplayer的缓存路径
            File(appCtx.externalCache, "exoplayer"),
            //容量从配置读取（默认 100MB）
            LeastRecentlyUsedCacheEvictor((cacheSizeMb * 1024 * 1024).toLong()),
            //记录缓存的数据库
            databaseProvider
        )
    }

    /**
     * R3: 创建预加载用 DataSource（供 FirstFramePreloader/VideoPreloader 使用 CacheUtil.cache）
     *
     * 复用 okhttpDataFactory 的 OkHttp 配置（强制 HTTP/1.1 + 浏览器 UA + 跟随重定向），
     * 确保预加载请求与播放器请求行为一致，避免因请求头差异导致缓存未命中。
     *
     * @param headers 请求头（Referer/Cookie/UA 防盗链）
     * @return OkHttpDataSource 实例（实现 DataSource 接口）
     */
    internal fun createPreloadDataSource(headers: Map<String, String> = emptyMap()): OkHttpDataSource {
        if (headers.isNotEmpty()) {
            okhttpDataFactory.setDefaultRequestProperties(headers)
        }
        return okhttpDataFactory.createDataSource()
    }

    /**
     * 通过kotlin扩展函数+反射实现CacheDataSource.Factory设置默认请求头
     * 需要添加混淆规则 -keepclassmembers class com.google.android.exoplayer2.upstream.cache.CacheDataSource$Factory{upstreamDataSourceFactory;}
     * @param headers
     * @return
     */
//    private fun CacheDataSource.Factory.setDefaultRequestProperties(headers: Map<String, String> = mapOf()): CacheDataSource.Factory {
//        val declaredField = this.javaClass.getDeclaredField("upstreamDataSourceFactory")
//        declaredField.isAccessible = true
//        val df = declaredField[this] as DataSource.Factory
//        if (df is OkHttpDataSource.Factory) {
//            df.setDefaultRequestProperties(headers)
//        }
//        return this
//    }


    fun getMediaSource(context: Context, url: String): MediaSource? {
        val uris = GSON.fromJsonArray<String>(url).getOrNull() ?: return null
        val dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(context)
        val mediaSourceBuilder = ConcatenatingMediaSource2.Builder()
        for (uri in uris) {
            mediaSourceBuilder.add(
                ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(uri)), 3000
            )
        }
        return mediaSourceBuilder.build()
    }

    // ===================== 音频离线缓存（Archive 回退引入） =====================

    private const val AUDIO_OFFLINE_CACHE_MAX_BYTES = 4L * 1024 * 1024 * 1024

    private val audioCache: Cache by lazy {
        val databaseProvider = StandaloneDatabaseProvider(appCtx)
        return@lazy SimpleCache(
            File(appCtx.externalCache, "audio_exoplayer"),
            LeastRecentlyUsedCacheEvictor(AUDIO_OFFLINE_CACHE_MAX_BYTES),
            databaseProvider
        )
    }

    private val audioCompleteMarkerDir: File by lazy {
        File(appCtx.externalCache, "audio_exoplayer_complete").apply { mkdirs() }
    }

    private val audioCacheDataSourceFactory by lazy {
        CacheDataSource.Factory()
            .setCache(audioCache)
            .setUpstreamDataSourceFactory(okhttpDataFactory)
            .setCacheReadDataSourceFactory(FileDataSource.Factory())
            .setCacheWriteDataSinkFactory(
                CacheDataSink.Factory()
                    .setCache(audioCache)
                    .setFragmentSize(CacheDataSink.DEFAULT_FRAGMENT_SIZE)
            )
    }

    fun createMediaRequest(url: String, headers: Map<String, String>): MediaRequest {
        return MediaRequest(url, headers.toMap())
    }

    fun cacheMedia(
        request: MediaRequest,
        progress: ((requestLength: Long, bytesCached: Long, newBytesCached: Long) -> Unit)? = null,
        shouldCancel: (() -> Boolean)? = null
    ): Long {
        var totalCached = 0L
        val urls = getMediaUrls(request.url)
        require(urls.isNotEmpty()) { "media url is empty" }
        urls.forEach { url ->
            if (shouldCancel?.invoke() == true) {
                throw kotlinx.coroutines.CancellationException("audio cache cancelled")
            }
            val dataSpec = androidx.media3.datasource.DataSpec.Builder()
                .setUri(url)
                .setKey(url)
                .setHttpRequestHeaders(request.headers)
                .build()
            var cached = 0L
            CacheWriter(
                audioCacheDataSourceFactory.createDataSourceForDownloading(),
                dataSpec,
                null
            ) { requestLength, bytesCached, newBytesCached ->
                if (shouldCancel?.invoke() == true) {
                    throw kotlinx.coroutines.CancellationException("audio cache cancelled")
                }
                cached = bytesCached
                progress?.invoke(requestLength, bytesCached, newBytesCached)
            }.cache()
            markMediaUrlComplete(url)
            totalCached += cached
        }
        return totalCached
    }

    fun isMediaCached(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val urls = getMediaUrls(url)
        if (urls.isEmpty()) return false
        return urls.all { isMediaUrlCached(it) }
    }

    fun removeMediaCache(url: String?) {
        if (url.isNullOrBlank()) return
        getMediaUrls(url).forEach {
            audioCache.removeResource(it)
            mediaCompleteMarker(it).delete()
        }
    }

    fun copyMediaCache(url: String?, targetDir: File): Int {
        if (url.isNullOrBlank()) return 0
        if (!targetDir.exists()) targetDir.mkdirs()
        var count = 0
        getMediaUrls(url).forEachIndexed { urlIndex, mediaUrl ->
            for (span in audioCache.getCachedSpans(mediaUrl)) {
                if (!span.isCached) continue
                val source = span.file ?: continue
                if (!source.exists() || !source.isFile) continue
                val name = "${urlIndex}_${span.position}_${span.length}_${source.name}"
                source.copyTo(File(targetDir, name), overwrite = true)
                count++
            }
        }
        return count
    }

    fun importMediaCache(url: String?, sourceDir: File): Int {
        if (url.isNullOrBlank() || !sourceDir.exists() || !sourceDir.isDirectory) return 0
        val urls = getMediaUrls(url)
        if (urls.isEmpty()) return 0
        var count = 0
        sourceDir.listFiles()
            ?.filter { it.isFile }
            ?.forEach { source ->
                val prefix = source.name.substringBefore('_', "")
                val urlIndex = prefix.toIntOrNull() ?: return@forEach
                val targetUrl = urls.getOrNull(urlIndex) ?: return@forEach
                val remain = source.name.substringAfter('_', "")
                val position = remain.substringBefore('_', "").toLongOrNull() ?: return@forEach
                val lengthPart = remain.substringAfter("${position}_", "")
                val expectedLength = lengthPart.substringBefore('_', "").toLongOrNull()
                    ?: source.length()
                val cacheFile = audioCache.startFile(targetUrl, position, expectedLength)
                cacheFile.parentFile?.mkdirs()
                source.inputStream().use { input ->
                    cacheFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                audioCache.commitFile(cacheFile, source.length())
                markMediaUrlComplete(targetUrl)
                count++
            }
        return count
    }

    private fun isMediaUrlCached(url: String): Boolean {
        val contentLength = ContentMetadata.getContentLength(audioCache.getContentMetadata(url))
        return if (contentLength > 0) {
            audioCache.isCached(url, 0, contentLength)
        } else {
            mediaCompleteMarker(url).isFile &&
                audioCache.getCachedBytes(url, 0, Long.MAX_VALUE) > 0
        }
    }

    private fun markMediaUrlComplete(url: String) {
        runCatching {
            mediaCompleteMarker(url).writeText(System.currentTimeMillis().toString())
        }
    }

    private fun mediaCompleteMarker(url: String): File {
        return File(audioCompleteMarkerDir, io.legado.app.utils.MD5Utils.md5Encode(url))
    }

    private fun getMediaUrls(url: String): List<String> {
        if (url.isJsonArray()) {
            GSON.fromJsonArray<String>(url).getOrNull()?.filter { it.isNotBlank() }?.let {
                return it
            }
        }
        return listOf(url)
    }

    data class MediaRequest(
        val url: String,
        val headers: Map<String, String> = emptyMap()
    )


    private const val PLAYBACK_CACHE_MAX_BYTES = 100L * 1024 * 1024

    /** 单本书视频离线缓存上限，超出后按 LRU 淘汰 */
    private const val VIDEO_CACHE_MAX_BYTES = 4L * 1024 * 1024 * 1024

    /** 单本书音频离线缓存上限，超出后按 LRU 淘汰 */
    private const val AUDIO_CACHE_MAX_BYTES = 2L * 1024 * 1024 * 1024

    /** 每本书的视频离线缓存目录，放在书缓存目录内，随书缓存一起删除 */
    private const val VIDEO_BOOK_CACHE_DIR = "video_media"

    /** 每本书的音频离线缓存目录，同上 */
    private const val AUDIO_BOOK_CACHE_DIR = "audio_media"

    /** 下载完成后落一个标记，用于判定自适应流（m3u8/mpd）是否已完整缓存 */
    private const val VIDEO_COMPLETE_SUFFIX = "_complete"

    /** 完成标记里表示"按普通文件（渐进式）下载"的内容 */
    private const val MEDIA_MARK_PROGRESSIVE = "progressive"

    /** 确认假缓存时最多读多少字节的播放列表（切片的第一个地址都在很前面） */
    private const val PLAYLIST_SNIFF_BYTES = 16 * 1024

    private val databaseProvider: StandaloneDatabaseProvider by lazy {
        StandaloneDatabaseProvider(appCtx)
    }

    private val cacheMap = ConcurrentHashMap<String, Cache>()
    private val cacheLock = Any()

    private val legacyCacheDir: File
        get() = File(appCtx.externalCache, "exoplayer")

    private fun simpleCache(dir: File, maxBytes: Long): Cache {
        val path = dir.absolutePath
        cacheMap[path]?.let { return it }
        return synchronized(cacheLock) {
            cacheMap[path] ?: SimpleCache(
                dir.apply { mkdirs() },
                LeastRecentlyUsedCacheEvictor(maxBytes),
                databaseProvider,
            ).also { cacheMap[path] = it }
        }
    }

    fun videoBookCacheDir(book: Book): File = File(BookHelp.getCacheDir(book), VIDEO_BOOK_CACHE_DIR)

    /**
     * 每本书的音频离线缓存目录（book_cache/<书>/audio_media）
     */
    fun audioBookCacheDir(book: Book): File = File(BookHelp.getCacheDir(book), AUDIO_BOOK_CACHE_DIR)

    /**
     * 该书媒体缓存目录，音频与视频只在目录（与容量上限）上不同
     * @param useVideoCache true 取视频目录，false 取音频目录
     */
    fun mediaBookCacheDir(book: Book, useVideoCache: Boolean): File = if (useVideoCache) videoBookCacheDir(book) else audioBookCacheDir(book)

    /**
     * 释放指定缓存目录的实例，删除缓存目录前必须调用，否则 media3 会持有目录锁
     */
    private fun releaseCache(cacheDir: File) {
        synchronized(cacheLock) {
            cacheMap.remove(cacheDir.absolutePath)?.release()
        }
    }

    fun releaseBookCaches(book: Book) {
        releaseCache(videoBookCacheDir(book))
        releaseCache(audioBookCacheDir(book))
    }

    /**
     * 按书籍缓存目录释放媒体缓存实例（删除/移动目录前调用）
     * @param bookCacheDir book_cache/<书籍文件夹名>
     */
    fun releaseBookMediaCacheOf(bookCacheDir: File) {
        releaseCache(File(bookCacheDir, VIDEO_BOOK_CACHE_DIR))
        releaseCache(File(bookCacheDir, AUDIO_BOOK_CACHE_DIR))
    }

    fun releaseAllBookCaches() {
        synchronized(cacheLock) {
            val iterator = cacheMap.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (!entry.key.startsWith(legacyCacheDir.absolutePath)) {
                    entry.value.release()
                    iterator.remove()
                }
            }
        }
    }

    suspend fun resolveMediaRequest(
        bookSource: BookSource,
        book: Book,
        chapter: BookChapter,
    ): MediaRequest {
        val content = WebBook.getContentAwait(bookSource, book, chapter).trim()
        if (content.isEmpty()) {
            throw NoStackTraceException("正文为空，无法缓存")
        }
        if (content.startsWith("<")) {
            // 正文返回 mpd 文本的场景，播放时会落临时文件，离线缓存暂不支持
            throw NoStackTraceException("该视频为 mpd 文本，暂不支持离线缓存")
        }
        val analyzeUrl = AnalyzeUrl(content, source = bookSource, ruleData = book, chapter = chapter)
        return MediaRequest(analyzeUrl.url, analyzeUrl.headerMap)
    }

    fun cacheMedia(
        request: MediaRequest,
        useVideoCache: Boolean = true,
        book: Book,
        progress: ((bytesCached: Long, newBytesCached: Long) -> Unit)? = null,
        shouldCancel: (() -> Boolean)? = null,
    ): Long {
        val urls = getMediaUrls(request.url)
        require(urls.isNotEmpty()) { "媒体地址为空" }
        val cacheDir = mediaBookCacheDir(book, useVideoCache)
        var totalCached = 0L
        urls.forEach { url ->
            require(isDownloadableMediaUrl(url)) { "不支持下载的媒体地址: $url" }
            if (shouldCancel?.invoke() == true) {
                throw kotlinx.coroutines.CancellationException("媒体缓存已取消")
            }
            var cached = 0L
            // DownloadRequest 的 id 只是下载索引里的主键（本项目不写索引）；缓存 key 仍是地址本身，
            // 所以判定/删除/找回都能直接拿地址当 key 用
            val downloader = DefaultDownloaderFactory(
                mediaCacheDataSourceFactory(request.headers, cacheDir, writable = true),
                Executor { it.run() },
            ).createDownloader(
                DownloadRequest.Builder(MD5Utils.md5Encode(url), url.toUri())
                    .setMimeType(mediaMimeTypeOfUrl(url))
                    .build(),
            )
            downloader.download { _, bytesCached, _ ->
                if (shouldCancel?.invoke() == true) {
                    downloader.cancel()
                    throw kotlinx.coroutines.CancellationException("媒体缓存已取消")
                }
                val newBytesCached = (bytesCached - cached).coerceAtLeast(0L)
                cached = bytesCached
                progress?.invoke(bytesCached, newBytesCached)
            }
            markMediaComplete(url, cacheDir)
            totalCached += cached
        }
        return totalCached
    }

    /**
     * 普通（渐进式）媒体源工厂——播放器自带的工厂与"按地址临时建源"共用同一份配置。
     * 显式开启恒定码率 seek：`.aac`(ADTS)、CBR `.mp3` 这类不带索引的文件，ExoPlayer 只能按
     * "输入长度 ÷ 平均码率"估算时长与 seek 点；开关关掉时 `duration` 返回 `C.TIME_UNSET`，
     * 进度条 max 落到 1，表现为"不显示进度、一拖就回到开头"。
     */
    private fun progressiveMediaSourceFactory(
        context: Context,
        dataSourceFactory: DataSource.Factory,
    ): DefaultMediaSourceFactory = DefaultMediaSourceFactory(
        context,
        DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true),
    ).setDataSourceFactory(dataSourceFactory).setLiveTargetOffsetMs(5000)

    fun createVideoMediaSource(
        context: Context,
        url: String,
        headers: Map<String, String>,
        cacheDir: File? = null,
        mimeType: String? = null,
        writable: Boolean = true,
    ): MediaSource = progressiveMediaSourceFactory(
        context,
        mediaPlaybackDataSourceFactory(url, headers, cacheDir, writable),
    ).createMediaSource(
            MediaItem.Builder()
                .setUri(url)
                .setMimeType(mimeType ?: mediaMimeTypeOfUrl(url))
                .build(),
        )

    /**
     * 播放器扩展名 -> MIME，地址本身看不出流类型时（如 m3u8 只出现在 query 里）使用
     */
    fun mimeTypeOfExtension(extension: String?): String? = when (extension?.lowercase()) {
        "m3u8" -> MimeTypes.APPLICATION_M3U8
        "mpd" -> MimeTypes.APPLICATION_MPD
        "ism" -> MimeTypes.APPLICATION_SS
        else -> null
    }

    fun isVideoCached(url: String?, book: Book): Boolean = isMediaCached(url, book, useVideoCache = true)

    /**
     * 该书是否有下载器写入的媒体缓存（完成标记目录非空）
     *
     * 只是目录检查，给"找回老缓存"做前置判断：整个书架里绝大多数书都没有媒体缓存，
     * 不该为了它们再去查章节表与缓存内容
     */
    fun hasDownloadedMedia(book: Book): Boolean {
        val cacheDir = mediaBookCacheDir(book, book.isVideo)
        val markerDir = completeMarkerDir(cacheDir)
        return markerDir.isDirectory && !markerDir.listFiles().isNullOrEmpty()
    }

    /**
     * 该书媒体缓存里已经存在的内容：(缓存 key -> 媒体地址)
     *
     * 缓存 key 就是媒体地址本身：下载器（ProgressiveDownloader / HlsDownloader，media3 1.8.0）
     * 与边播边写都按 DataSpec 的地址做 key；DownloadRequest 的 id（url 的 md5）只是下载索引里的
     * 主键，不参与缓存 key——本项目也从没往下载索引里写过记录。所以 key 直接当地址用，
     * [downloadUriById] 只是索引里万一有记录时的兜底。供"章节表里的地址变了"时回查缓存。
     */
    fun cachedMediaEntries(book: Book): List<Pair<String, String>> {
        val useVideoCache = book.isVideo
        val cacheDir = mediaBookCacheDir(book, useVideoCache)
        if (!cacheDir.exists()) return emptyList()
        val maxBytes = if (useVideoCache) VIDEO_CACHE_MAX_BYTES else AUDIO_CACHE_MAX_BYTES
        val keys = runCatching { simpleCache(cacheDir, maxBytes).keys }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: return emptyList()
        val downloadUris = downloadUriById()
        return keys.mapNotNull { key ->
            val url = downloadUris[key] ?: key.takeIf { it.startsWith("http") }
            url?.let { key to it }
        }
    }

    /**
     * 从该书已缓存的一组地址里挑出能当"章节播放地址"用的那个
     *
     * HLS 下载会留下播放列表 + 大量切片两类 key：切片地址不能当章节地址（播放与再缓存都要从列表
     * 开始），所以多个都完整时优先挑内容是播放列表（`#EXTM3U`）的；普通文件一章通常就一个 key。
     * 都完整却挑不出播放列表（如一章多段 mp4）时宁可返回 null，也别拿半截地址当作章节地址。
     * 供"整份缓存都属于某一章"的反推场景用（见 CacheManifestHelper.recoverLegacyMediaUrl）。
     */
    fun pickPlayableCachedUrl(urls: Collection<String>, book: Book): String? {
        val useVideoCache = book.isVideo
        val cacheDir = mediaBookCacheDir(book, useVideoCache)
        if (!cacheDir.exists()) return null
        val cache = simpleCache(cacheDir, cacheMaxBytes(cacheDir))
        val playable = urls.filter { isMediaUrlCached(cache, it, cacheDir) }
        if (playable.size <= 1) return playable.firstOrNull()
        return playable.firstOrNull { url ->
            readCachedText(cache, url)?.trimStart()?.startsWith("#EXTM3U") == true
        }
    }

    /**
     * 下载索引里的记录：(请求 id -> 媒体地址)
     *
     * DownloadRequest 的 id 是 url 的 md5，但 media3 的下载器并不拿它当缓存 key（key 还是地址），
     * 本项目也没写过下载索引，这个映射通常为空——留着只是兜底，索引里万一有历史记录时能还原出地址
     */
    private fun downloadUriById(): Map<String, String> = runCatching {
        val index = DefaultDownloadIndex(databaseProvider)
        val result = hashMapOf<String, String>()
        val cursor = index.getDownloads()
        try {
            while (cursor.moveToNext()) {
                val request = cursor.download.request
                result[request.id] = request.uri.toString()
            }
        } finally {
            cursor.close()
        }
        result
    }.getOrDefault(emptyMap())

    /**
     * 判定该章节的音频媒体文件是否已经完整缓存
     */
    fun isMediaCached(url: String?, book: Book): Boolean = isMediaCached(url, book, useVideoCache = false)

    private fun isMediaCached(url: String?, book: Book, useVideoCache: Boolean): Boolean {
        if (url.isNullOrBlank()) return false
        val cacheDir = mediaBookCacheDir(book, useVideoCache)
        if (!cacheDir.exists()) return false
        val urls = getMediaUrls(url)
        if (urls.isEmpty()) return false
        if (urls.any { !isDownloadableMediaUrl(it) }) return false
        val cache = simpleCache(cacheDir, cacheMaxBytes(cacheDir))
        return urls.all { isMediaUrlCached(cache, it, cacheDir) }
    }

    /**
     * 缓存被 LRU 淘汰后完成标记可能残留，所以判定时都要求缓存里确实还有数据
     */
    private fun isMediaUrlCached(cache: Cache, url: String, cacheDir: File): Boolean {
        val cachedBytes = cache.getCachedBytes(url, 0, Long.MAX_VALUE)
        if (cachedBytes <= 0) return false
        val mediaMimeType = mediaMimeTypeOfUrl(url)
        if (mediaMimeType != null) {
            // 自适应流的切片按切片地址单独缓存，只能靠完成标记判定；
            // 标记里必须写着同一个自适应流类型，才算真的按自适应流下过（见 [markMediaComplete]）
            val mark = readCompleteMarker(url, cacheDir)
            if (mark == mediaMimeType) return true
            // 没落过标记：只是边播边写的缓存，不算完整
            if (mark == null) return false
            // 旧版本的标记没记类型，按内容确认一次：
            // 确认是"只下到播放列表"的假缓存才清掉；读不准的一律当真缓存，绝不误删用户下好的内容
            if (adaptiveCacheLooksFake(cache, url)) {
                clearFakeAdaptiveCache(cache, url, cacheDir)
                return false
            }
            // 确认过就补齐标记，之后判定不用再读内容
            markMediaComplete(url, cacheDir)
            return true
        }
        val contentLength = ContentMetadata.getContentLength(cache.getContentMetadata(url))
        return if (contentLength > 0) {
            cache.isCached(url, 0, contentLength)
        } else {
            completeMarker(url, cacheDir).isFile
        }
    }

    fun removeMediaCache(url: String?, book: Book, useVideoCache: Boolean = true) {
        if (url.isNullOrBlank()) return
        val cacheDir = mediaBookCacheDir(book, useVideoCache)
        if (!cacheDir.exists()) return
        val cache = simpleCache(cacheDir, cacheMaxBytes(cacheDir))
        getMediaUrls(url).forEach { mediaUrl ->
            cache.removeResource(mediaUrl)
            completeMarker(mediaUrl, cacheDir).delete()
        }
    }

    private fun isDownloadableMediaUrl(url: String): Boolean {
        val scheme = url.toUri().scheme ?: return false
        return isHttpUrl(url) ||
            (scheme.equals("file", true) && isAdaptiveMediaUrl(url))
    }

    private fun isHttpUrl(url: String): Boolean {
        val scheme = url.toUri().scheme ?: return false
        return scheme.equals("http", true) || scheme.equals("https", true)
    }

    private fun isAdaptiveMediaUrl(url: String): Boolean = mediaMimeTypeOfUrl(url) != null

    private fun mediaPlaybackDataSourceFactory(
        url: String,
        headers: Map<String, String>,
        cacheDir: File? = null,
        writable: Boolean = true,
    ): DataSource.Factory {
        val targetCacheDir = cacheDir ?: legacyCacheDir
        // 不写缓存、缓存目录还不存在（也没缓存可读）且是网络地址时直接走网络，
        // 避免"只是播放"在书籍缓存目录里凭空建出空目录；
        // 本地文件/内容 URI 不短路，仍走下面的缓存数据源（读侧 FileDataSource、未命中由上游的数据源按 scheme 读）
        if (!writable && !targetCacheDir.exists() && isHttpUrl(url)) {
            return okhttpDataFactory(headers)
        }
        return mediaCacheDataSourceFactory(headers, targetCacheDir, writable, ignoreCacheError = true)
    }

    /**
     * 走缓存目录的数据源，[writable] 为 false 时是只读缓存
     *
     * 上游用按 scheme 分发的 [DefaultDataSource]：媒体地址不都是网络地址——书源正文直接返回 mpd 文本时，
     * 播放的是刚落盘的本地临时文件（file://），OkHttp 只认 http/https，读 file:// 会抛
     * `HttpDataSourceException: Malformed URL` 导致整段播放失败（DASH 清单读不到就 Source error）。
     * 默认数据源对 file://、content:// 等本地地址会交给对应的本地数据源，http/https 仍走同一个 OkHttp 工厂。
     *
     * @param ignoreCacheError 播放可以容忍缓存读写出错（缓存只是加速器，出错后退回网络）；
     * 下载不允许，出错要暴露出来以便重试
     */
    private fun mediaCacheDataSourceFactory(
        headers: Map<String, String>,
        cacheDir: File,
        writable: Boolean,
        ignoreCacheError: Boolean = false,
    ): CacheDataSource.Factory {
        val dataCache = simpleCache(cacheDir, cacheMaxBytes(cacheDir))
        return CacheDataSource.Factory()
            .setCache(dataCache)
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(appCtx, okhttpDataFactory(headers)))
            .setCacheReadDataSourceFactory(FileDataSource.Factory())
            .apply {
                if (ignoreCacheError) {
                    setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                }
                if (writable) {
                    setCacheWriteDataSinkFactory(
                        CacheDataSink.Factory()
                            .setCache(dataCache)
                            .setFragmentSize(CacheDataSink.DEFAULT_FRAGMENT_SIZE),
                    )
                }
            }
    }

    /**
     * 缓存目录对应的容量上限：旧全局播放缓存沿用历史的 100MB，视频 4GB，音频 2GB
     */
    private fun cacheMaxBytes(cacheDir: File): Long = when (cacheDir.name) {
        VIDEO_BOOK_CACHE_DIR -> VIDEO_CACHE_MAX_BYTES
        AUDIO_BOOK_CACHE_DIR -> AUDIO_CACHE_MAX_BYTES
        else -> PLAYBACK_CACHE_MAX_BYTES
    }

    /**
     * 落完成标记，内容记下这份缓存是按哪种流下载的
     *
     * 自适应流"已完整缓存"只能靠标记判定，记清类型才能把"代理地址被当普通文件下、只下到播放列表"
     * 的假缓存与真正的自适应流缓存区分开（见 [isMediaUrlCached]）
     */
    private fun markMediaComplete(url: String, cacheDir: File) {
        val mark = mediaMimeTypeOfUrl(url) ?: MEDIA_MARK_PROGRESSIVE
        runCatching {
            completeMarkerDir(cacheDir).mkdirs()
            completeMarker(url, cacheDir).writeText(mark)
        }
    }

    private fun readCompleteMarker(url: String, cacheDir: File): String? {
        val marker = completeMarker(url, cacheDir)
        if (!marker.isFile) return null
        return runCatching { marker.readText().trim() }.getOrNull()
    }

    /**
     * 缓存里的这份内容是不是"只下到播放列表"的假缓存
     *
     * 播放列表本身很小，把播放列表当普通文件下就只有一个列表、不带任何切片；
     * 真下过的缓存里，列表引用的切片按各自地址单独缓存着。
     * 只有内容确实是一份播放列表（`#EXTM3U` 开头）**并且**它引用的第一个切片不在缓存里才判定为假：
     * 读不到内容、或内容不像列表（byte-range 这类会把媒体写在同一个 key 上）时一律返回 false，
     * 宁可当作真缓存，也不能误删用户已经下好的内容。
     */
    private fun adaptiveCacheLooksFake(cache: Cache, url: String): Boolean {
        val playlist = readCachedText(cache, url) ?: return false
        if (!playlist.trimStart().startsWith("#EXTM3U")) return false
        val firstUri = playlist.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?: return false
        val segmentUrl = runCatching { URI(url).resolve(firstUri).toString() }.getOrNull() ?: return false
        return cache.getCachedBytes(segmentUrl, 0, Long.MAX_VALUE) <= 0
    }

    /** 读缓存里某个地址的内容（只读缓存，不联网；没缓存时返回 null） */
    private fun readCachedText(cache: Cache, url: String): String? {
        val dataSource = CacheDataSource.Factory()
            .setCache(cache)
            .setCacheReadDataSourceFactory(FileDataSource.Factory())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()
        return runCatching {
            dataSource.open(DataSpec(url.toUri()))
            val buffer = ByteArray(PLAYLIST_SNIFF_BYTES)
            val read = dataSource.read(buffer, 0, buffer.size)
            if (read <= 0) null else String(buffer, 0, read)
        }.also {
            runCatching { dataSource.close() }
        }.getOrNull()
    }

    /**
     * 清掉"把自适应流当普通文件下"留下的假缓存
     *
     * 这种缓存里只有一个播放列表（切片从没下过），而播放走缓存时命中已缓存的列表后就不会再去网络取，
     * 列表里的切片地址通常带签名、早已失效——留着它连在线播放都可能读到一个拼接坏的列表，
     * 重新缓存也会按这份过期列表去下失效的切片。清掉这一条即可：切片按各自地址单独缓存，
     * 真正下过的切片不受影响，重新缓存时还能直接复用。
     */
    private fun clearFakeAdaptiveCache(cache: Cache, url: String, cacheDir: File) {
        runCatching {
            cache.removeResource(url)
            completeMarker(url, cacheDir).delete()
        }
    }

    private fun completeMarker(url: String, cacheDir: File): File = File(completeMarkerDir(cacheDir), MD5Utils.md5Encode(url))

    /** 完成标记所在目录，与缓存目录同级（video_media -> video_media_complete） */
    private fun completeMarkerDir(cacheDir: File): File = File(cacheDir.parentFile, cacheDir.name + VIDEO_COMPLETE_SUFFIX)

    /**
     * 章节内容/媒体地址文本 -> 候选媒体地址列表
     *
     * 供"从缓存正文里找回媒体地址"用（音视频章节的缓存正文就是缓存当时的媒体地址），
     * 地址可能是 JSON 数组、单个地址或多行文本，这里都拆开，由调用方逐个去缓存里核对。
     */
    fun mediaUrlsOf(content: String?): List<String> {
        if (content.isNullOrBlank()) return emptyList()
        return getMediaUrls(content.trim())
            .flatMap { it.lineSequence().toList() }
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    // ==== ng AudioDownloadCache 音频离线缓存辅助 API（D14 移植自 fork-ng）====

    fun audioCacheContentLength(cacheKey: String): Long =
        ContentMetadata.getContentLength(audioCache.getContentMetadata(cacheKey))

    fun audioCacheContiguousLength(cacheKey: String): Long {
        var end = 0L
        for (span in audioCache.getCachedSpans(cacheKey)) {
            if (span.position > end) break
            end = maxOf(end, span.position + span.length)
        }
        return end
    }

    fun isAudioCacheComplete(cacheKey: String, length: Long): Boolean =
        length > 0L && audioCache.isCached(cacheKey, 0L, length)

    fun removeAudioCache(cacheKeys: Collection<String>) {
        cacheKeys.forEach(audioCache::removeResource)
    }

    fun removeAudioCacheByPrefix(prefix: String) {
        audioCache.keys
            .filter { it.startsWith(prefix) }
            .forEach(audioCache::removeResource)
    }

    fun clearAudioCache() {
        audioCache.keys.toList().forEach(audioCache::removeResource)
    }

    fun createAudioCacheWriter(
        mediaItem: MediaItem,
        progressListener: CacheWriter.ProgressListener? = null
    ): CacheWriter {
        val localConfiguration = requireNotNull(mediaItem.localConfiguration)
        val cacheKey = requireNotNull(localConfiguration.customCacheKey)
        val dataSpec = DataSpec.Builder()
            .setUri(localConfiguration.uri)
            .setKey(cacheKey)
            .build()
        return CacheWriter(
            audioCacheDataSourceFactory.createDataSourceForDownloading(),
            dataSpec,
            null,
            progressListener
        )
    }
}
