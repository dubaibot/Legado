package com.lanyue.reader.ui

import android.content.Context
import com.lanyue.reader.core.engine.DomParser
import com.lanyue.reader.core.engine.InMemoryStickyStore
import com.lanyue.reader.core.engine.NetworkDispatcher
import com.lanyue.reader.core.engine.Sniffer
import com.lanyue.reader.core.engine.SourceRateLimiter
import com.lanyue.reader.core.engine.SystemCookieBridge
import com.lanyue.reader.core.engine.WebViewJsRuntime
import com.lanyue.reader.bookshelf.ShelfOps
import com.lanyue.reader.core.engine.ChannelStickyStore
import com.lanyue.reader.core.net.OkHttpChannel
import com.lanyue.reader.core.observability.LocalMetrics
import com.lanyue.reader.core.engine.RssFallbackParser
import com.lanyue.reader.data.db.DanmakuDao
import com.lanyue.reader.data.db.LanyueDatabase
import com.lanyue.reader.data.db.ProgressDao
import com.lanyue.reader.danmaku.DanmakuMatchEngine
import com.lanyue.reader.source.danmaku.DandanplaySource
import com.lanyue.reader.source.model.IDanmakuSource
import com.lanyue.reader.source.rule.RuleEngine
import com.lanyue.reader.source.rule.SortUrlParser
import com.lanyue.reader.source.video.LocalParseProxy
import com.lanyue.reader.source.video.TvBoxParser
import com.lanyue.reader.source.video.WebViewSnifferPool
import java.io.File

/**
 * 运行时装配：内核组件图的单例持有者。
 *
 * 源适配器（LegadoBookSource / LegadoRssSource）全部依赖这张图；
 * 池化与 Cronet/WebView 通道按 4.4 是后续轮次，本图先以
 * OkHttp + jsoup 降级 + 单 WebView JS 组成最小可用运行时。
 */
object RuntimeGraph {

    class Graph(
        val net: NetworkDispatcher,
        val rules: RuleEngine,
        val sortParser: SortUrlParser,
        val rssFallback: RssFallbackParser,
        val metrics: LocalMetrics,
        /** 影视 parses type 2 本地聚合代理（§7.3 LocalParseProxy）。 */
        val localParseProxy: LocalParseProxy,
        /** 视频配置的 parses 列表，导入影视配置后由 updateVideoParses 刷新。 */
        var videoParses: List<TvBoxParser.ParseRule>,
        /** 弹幕 DAO（§4.10 持久化匹配记忆 + 源列表）。 */
        val danmakuDao: DanmakuDao,
        /** 弹幕自动匹配引擎（§4.10.3 零操作链路）。 */
        val danmakuEngine: DanmakuMatchEngine,
        /** 4.8 嗅探渲染池 + Snipper 门面：SNIFF 类型解析执行器。 */
        val sniffer: Sniffer,
        /** 播放/阅读进度 DAO（§4.12 play_records；影视断点续播走这里）。 */
        val progressDao: ProgressDao,
        /** 全库 DAO 门面（§3.10 源管理中心为第二批 UI 消费方）。 */
        val db: LanyueDatabase,
        /** §3.1 书架操作语义（§3.1 书架页 UI 的进程内演示态数据源）。 */
        val shelfOps: ShelfOps
    )

    @Volatile
    private var graph: Graph? = null

    fun get(context: Context): Graph {
        graph?.let { return it }
        synchronized(this) {
            graph?.let { return it }
            val app = context.applicationContext
            val metrics = LocalMetrics(File(app.filesDir, "metrics"))
            val net = NetworkDispatcher(
                okHttp = OkHttpChannel(),
                cronet = null,
                webView = null,
                sticky = ChannelStickyStore(InMemoryStickyStore()),
                rateLimiter = SourceRateLimiter(),
                metrics = metrics,
                cookieBridge = SystemCookieBridge
            )
            val dom = DomParser(realDom = null, metrics = metrics)
            val js = WebViewJsRuntime(app)
            val localParseProxy = LocalParseProxy(
                net = net,
                parses = emptyList()
            )
            val db = LanyueDatabase.build(app)
            val danmakuFactory: (com.lanyue.reader.data.db.entity.DanmakuSourceEntity) -> IDanmakuSource? =
                { entity -> DandanplaySource(entity, net, metrics) { null } }
            val danmakuEngine = DanmakuMatchEngine(db.danmakuDao(), danmakuFactory, metrics)
            val sniffer = Sniffer(WebViewSnifferPool(app), SystemCookieBridge)
            val g = Graph(
                net = net,
                rules = RuleEngine(js = js, dom = dom, metrics = metrics),
                sortParser = SortUrlParser(js = js, metrics = metrics),
                rssFallback = RssFallbackParser(),
                metrics = metrics,
                localParseProxy = localParseProxy,
                videoParses = emptyList(),
                danmakuDao = db.danmakuDao(),
                danmakuEngine = danmakuEngine,
                sniffer = sniffer,
                progressDao = db.progressDao(),
                db = db,
                shelfOps = ShelfOps()
            )
            graph = g
            return g
        }
    }

    /**
     * 影视配置导入后刷新 parses 链：
     * - localParseProxy.parses 立即就位，后续 PARSE 类型播放立即走本地代理解析
     * - videoParses 反映最新配置，供诊断/回放追溯
     * - 顺带启动本地代理，避免首个 PARSE 请求落空
     */
    fun updateVideoParses(
        context: Context,
        parses: List<TvBoxParser.ParseRule>
    ) {
        val g = get(context)
        g.localParseProxy.parses = parses
        g.videoParses = parses
        g.localParseProxy.start()
    }
}
