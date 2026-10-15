package io.legado.app.help.source

import io.legado.app.help.http.warmUpConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * 统一预连接组件（多源合并 D6，对齐 m 源 SourcePreconnectHelper）
 *
 * 机制层互补：让 BookSource（BookChapterList）与 RssSource 列表加载完成后，
 * 对前 N 个 URL 发起 HEAD 预连接，点击时减少 300-1000ms 连接建立时间。
 *
 * 数据流：
 *   1. 调用方在列表/目录加载完成后，传入 URL 列表和预连接数量 N
 *   2. 本组件并行对前 N 个 URL 发起 HEAD 预连接（warmUpConnection）
 *   3. kotlin.runCatching 包裹，失败不影响主流程
 *
 * 行为对齐 m 源 Rss.kt 原内联实现（coroutineScope + async + awaitAll + runCatching）。
 */
object SourcePreconnectHelper {

    /**
     * 预连接前 N 个 URL
     *
     * @param urls URL 列表（取前 N 个非空 URL）
     * @param n 预连接数量（默认 3）
     */
    suspend fun preconnectTopN(urls: List<String>, n: Int = 3) {
        kotlin.runCatching {
            coroutineScope {
                urls.take(n).mapIndexed { _, url ->
                    async(Dispatchers.IO) {
                        if (url.isNotBlank()) {
                            warmUpConnection(url)
                        }
                    }
                }.awaitAll()
            }
        }
    }
}
