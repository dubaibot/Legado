import com.lanyue.reader.data.db.entity.BookSourceEntity
import com.lanyue.reader.data.db.entity.DanmakuSourceEntity
import com.lanyue.reader.data.db.entity.RssSourceEntity
import com.lanyue.reader.data.db.entity.VideoSiteEntity
import com.lanyue.reader.source.SourceRegistry
import com.lanyue.reader.source.model.IBookSource
import com.lanyue.reader.source.model.IDanmakuSource
import com.lanyue.reader.source.model.ISource
import com.lanyue.reader.source.model.ISubscriptionSource
import com.lanyue.reader.source.model.IVideoSource

private var pass = 0
private var fail = 0
private val failures = ArrayList<String>()

private fun eq(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) pass++ else {
        fail++
        failures.add("$name -> actual=$actual expected=$expected")
    }
}

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) pass++ else {
        fail++
        failures.add("$name -> $detail")
    }
}

// ---------- 最小实现 ----------

private class DummyBook : IBookSource {
    override val sourceId: String = "dummy-book"
    override val sourceType: com.lanyue.reader.source.model.SourceType =
        com.lanyue.reader.source.model.SourceType.BOOK
    override val capabilityFlags: Set<com.lanyue.reader.source.model.SourceCapability> = emptySet()
    override val concurrentRate: com.lanyue.reader.core.engine.RateLimit? = null
    override suspend fun search(key: String, page: Int) = emptyList<com.lanyue.reader.source.model.SearchBookItem>()
    override suspend fun explore(sort: com.lanyue.reader.source.model.SortRule, page: Int) = emptyList<com.lanyue.reader.source.model.SearchBookItem>()
    override suspend fun getDetail(url: String) =
        com.lanyue.reader.source.model.BookDetail(url, bookUrl = url)
    override suspend fun getToc(url: String) = emptyList<com.lanyue.reader.source.model.TocItem>()
    override suspend fun getContent(url: String) =
        com.lanyue.reader.source.model.ContentResult.Html("<p>ok</p>")
}

private class DummyRss : ISubscriptionSource {
    override val sourceId: String = "dummy-rss"
    override val sourceType: com.lanyue.reader.source.model.SourceType =
        com.lanyue.reader.source.model.SourceType.SUBSCRIPTION
    override val capabilityFlags: Set<com.lanyue.reader.source.model.SourceCapability> = emptySet()
    override val concurrentRate: com.lanyue.reader.core.engine.RateLimit? = null
    override suspend fun getSorts() = emptyList<com.lanyue.reader.source.model.SortRule>()
    override suspend fun getArticles(sort: com.lanyue.reader.source.model.SortRule?, page: Int) = emptyList<com.lanyue.reader.source.model.SubscriptionItem>()
    override suspend fun search(key: String, page: Int) = emptyList<com.lanyue.reader.source.model.SubscriptionItem>()
    override suspend fun getContent(url: String) =
        com.lanyue.reader.source.model.ContentResult.Html("<p>ok</p>")
}

private class DummyVideo : IVideoSource {
    override val sourceId: String = "dummy-video"
    override val sourceType: com.lanyue.reader.source.model.SourceType =
        com.lanyue.reader.source.model.SourceType.VIDEO
    override val capabilityFlags: Set<com.lanyue.reader.source.model.SourceCapability> = emptySet()
    override val concurrentRate: com.lanyue.reader.core.engine.RateLimit? = null
    override suspend fun getCategories() = emptyList<com.lanyue.reader.source.model.Category>()
    override suspend fun explore(category: com.lanyue.reader.source.model.Category, filter: com.lanyue.reader.source.model.Filter?, page: Int) = emptyList<com.lanyue.reader.source.model.VideoItem>()
    override suspend fun search(key: String, page: Int) = emptyList<com.lanyue.reader.source.model.VideoItem>()
    override suspend fun getDetail(url: String) = com.lanyue.reader.source.model.VideoDetail(url)
    override suspend fun getPlay(episode: com.lanyue.reader.source.model.VideoEpisode) =
        com.lanyue.reader.source.model.PlayResult(
            com.lanyue.reader.source.model.PlayKind.DIRECT,
            episode.url
        )
}

private class DummyDanmaku : IDanmakuSource {
    override val sourceId: String = "dummy-danmaku"
    override val sourceType: com.lanyue.reader.source.model.SourceType =
        com.lanyue.reader.source.model.SourceType.DANMAKU
    override val capabilityFlags: Set<com.lanyue.reader.source.model.SourceCapability> = emptySet()
    override val concurrentRate: com.lanyue.reader.core.engine.RateLimit? = null
    override suspend fun match(req: com.lanyue.reader.source.model.DanmakuMatchRequest) = null
    override suspend fun search(key: String) = emptyList<com.lanyue.reader.source.model.DanmakuMatch>()
    override suspend fun getComments(match: com.lanyue.reader.source.model.DanmakuMatch, fromMs: Long, durationMs: Long) = emptyList<com.lanyue.reader.source.model.DanmakuEvent>()
}

fun main() {
    // ================= 装配过滤 =================
    val calls = ArrayList<String>()

    val registry = SourceRegistry(
        bookFactory = { e -> calls.add("book:${e.bookSourceUrl}"); DummyBook() },
        rssFactory = { e -> calls.add("rss:${e.sourceUrl}"); DummyRss() },
        videoFactory = { e -> calls.add("video:${e.siteKey}"); DummyVideo() },
        danmakuFactory = { e -> calls.add("danmaku:${e.apiBase}"); DummyDanmaku() }
    )

    // ---- book：enabled 过滤 ----
    val bookEnabled = BookSourceEntity(bookSourceUrl = "b1", enabled = true)
    val bookDisabled = BookSourceEntity(bookSourceUrl = "b2", enabled = false)

    check("book enabled=true 返回实例", registry.book(bookEnabled) is IBookSource)
    eq("book enabled=true 调用工厂", calls.filter { it == "book:b1" }, listOf("book:b1"))

    check("book enabled=false 返回 null", registry.book(bookDisabled) == null)
    eq("book enabled=false 不调用工厂", calls.filter { it == "book:b2" }, emptyList<String>())

    // ---- rss：enabled 过滤 ----
    val rssEnabled = RssSourceEntity(sourceUrl = "r1", enabled = true)
    val rssDisabled = RssSourceEntity(sourceUrl = "r2", enabled = false)

    check("rss enabled=true 返回实例", registry.rss(rssEnabled) is ISubscriptionSource)
    eq("rss enabled=true 调用工厂", calls.filter { it == "rss:r1" }, listOf("rss:r1"))

    check("rss enabled=false 返回 null", registry.rss(rssDisabled) == null)
    eq("rss enabled=false 不调用工厂", calls.filter { it == "rss:r2" }, emptyList<String>())

    // ---- video：enabled + unsupported 双重过滤 ----
    val videoEnabled = VideoSiteEntity(configUrl = "c1", siteKey = "v1", enabled = true, unsupported = false)
    val videoDisabled = VideoSiteEntity(configUrl = "c1", siteKey = "v2", enabled = false)
    val videoUnsupported = VideoSiteEntity(configUrl = "c1", siteKey = "v3", enabled = true, unsupported = true)

    check("video unsupported=true 返回 null", registry.video(videoUnsupported) == null)
    eq("video unsupported 不调用工厂", calls.filter { it == "video:v3" }, emptyList<String>())

    check("video enabled=false 返回 null", registry.video(videoDisabled) == null)
    eq("video enabled=false 不调用工厂", calls.filter { it == "video:v2" }, emptyList<String>())

    check("video enabled=true 返回实例", registry.video(videoEnabled) is IVideoSource)
    eq("video enabled=true 调用工厂", calls.filter { it == "video:v1" }, listOf("video:v1"))

    // ---- danmaku：enabled 过滤 ----
    val dmEnabled = DanmakuSourceEntity(id = "d1", name = "dm", apiBase = "http://dm-on", enabled = true)
    val dmDisabled = DanmakuSourceEntity(id = "d2", name = "dm", apiBase = "http://dm-off", enabled = false)

    check("danmaku enabled=false 返回 null", registry.danmaku(dmDisabled) == null)
    eq("danmaku enabled=false 不调用工厂", calls.filter { it == "danmaku:http://dm-off" }, emptyList<String>())

    check("danmaku enabled=true 返回实例", registry.danmaku(dmEnabled) is IDanmakuSource)
    eq("danmaku enabled=true 调用工厂", calls.filter { it == "danmaku:http://dm-on" }, listOf("danmaku:http://dm-on"))

    // ================= 工厂返回 null 与正常转发 =================
    val nullRegistry = SourceRegistry(
        bookFactory = { null },
        rssFactory = { null },
        videoFactory = { e -> DummyVideo() },
        danmakuFactory = { null }
    )
    check("工厂返回 null 时透传 null", nullRegistry.book(BookSourceEntity("b9", enabled = true)) == null)
    check("工厂返回 null（订阅）透传", nullRegistry.rss(RssSourceEntity("r9", enabled = true)) == null)
    check("工厂返回 null（弹幕）透传", nullRegistry.danmaku(dmEnabled) == null)
    check("工厂返回实体正常装配", nullRegistry.video(videoEnabled) is IVideoSource)

    // ================= 工厂异常传播（当前行为：不捕获） ============
    val silentRegistry = SourceRegistry(
        bookFactory = { throw IllegalStateException("boom") },
        rssFactory = { null },
        videoFactory = { null },
        danmakuFactory = { null }
    )
    val threw = try {
        silentRegistry.book(bookEnabled)
        false
    } catch (e: IllegalStateException) {
        true
    }
    check("工厂抛 IllegalStateException 向上传播", threw)

    // ================= 汇总 =================
    println("SourceRegistry 配配测试: $pass 通过, $fail 失败")
    failures.forEach { println("[FAIL] $it") }
    if (fail > 0) kotlin.system.exitProcess(1)
}
