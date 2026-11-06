import com.lanyue.reader.source.model.SourceHealth
import com.lanyue.reader.source.model.SourceType
import com.lanyue.reader.source.model.SourceValidator
import com.lanyue.reader.source.model.SourceValidator.Level

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

private fun throws(name: String, block: () -> Unit) {
    try {
        block(); fail++; failures.add("$name -> 期望抛异常但未抛出")
    } catch (e: IllegalArgumentException) {
        pass++
    } catch (e: Throwable) {
        fail++; failures.add("$name -> 异常类型不对: $e")
    }
}

fun main() {
    // ================= SourceHealth =================
    val h = SourceHealth(3)

    eq("初始未隔离", h.isIsolated(SourceType.BOOK, "s1"), false)
    eq("未知源不视为隔离", h.isIsolated(SourceType.VIDEO, "never-seen"), false)

    eq("第1次失败计数", h.markFail(SourceType.BOOK, "s1"), 1)
    eq("第2次失败计数", h.markFail(SourceType.BOOK, "s1"), 2)
    eq("未达阈值不隔离", h.isIsolated(SourceType.BOOK, "s1"), false)
    eq("第3次失败计数", h.markFail(SourceType.BOOK, "s1"), 3)
    eq("达阈值即隔离", h.isIsolated(SourceType.BOOK, "s1"), true)

    // 关键回归：书源失败不得连带隔离同 id 的影视源（本轮修复的缺陷）
    eq("同 id 影视源不受书源隔离影响", h.isIsolated(SourceType.VIDEO, "s1"), false)

    h.markSuccess(SourceType.BOOK, "s1")
    eq("成功后解除隔离", h.isIsolated(SourceType.BOOK, "s1"), false)
    eq("成功后计数清零", h.markFail(SourceType.BOOK, "s1"), 1)

    // 影视源独立累计
    h.markFail(SourceType.VIDEO, "s1")
    h.markFail(SourceType.VIDEO, "s1")
    eq("影视源未达阈值", h.isIsolated(SourceType.VIDEO, "s1"), false)
    h.markFail(SourceType.VIDEO, "s1")
    eq("影视源达阈值被隔离", h.isIsolated(SourceType.VIDEO, "s1"), true)
    eq("影视隔离不波及书源", h.isIsolated(SourceType.BOOK, "s1"), false)

    // reset 跨类型清理
    h.reset("s1")
    eq("reset 清影视隔离", h.isIsolated(SourceType.VIDEO, "s1"), false)
    eq("reset 后计数归零", h.markFail(SourceType.BOOK, "s1"), 1)

    // 隔离计数
    val h2 = SourceHealth(2)
    h2.markFail(SourceType.BOOK, "a"); h2.markFail(SourceType.BOOK, "a")
    h2.markFail(SourceType.BOOK, "b")
    eq("隔离源数量", h2.isolatedCount(), 1)
    eq("跟踪源数量含未达阈值", h2.trackedCount(), 2)
    h2.clear()
    eq("clear 后无跟踪", h2.trackedCount(), 0)
    eq("clear 后无隔离", h2.isolatedCount(), 0)

    // 阈值 1 立刻隔离
    val h3 = SourceHealth(1)
    h3.markFail(SourceType.BOOK, "x")
    eq("阈值1立即隔离", h3.isIsolated(SourceType.BOOK, "x"), true)

    // 非法阈值
    throws("阈值 0 应拒绝") { SourceHealth(0) }
    throws("阈值负数应拒绝") { SourceHealth(-1) }

    // ================= SourceValidator: 书源 =================
    val okBook = SourceValidator.validateBook(loginUrl = null, 
        bookSourceUrl = "https://a.com",
        bookSourceName = "测试源",
        searchUrl = "/search?q={{key}}",
        ruleSearch = "class.book@text",
        exploreUrl = "/list",
        enabledExplore = true,
        ruleToc = "class.toc@text",
        ruleContent = "id.content@html"
    )
    eq("完整书源无错误", okBook.hasError, false)
    eq("完整书源摘要", okBook.summary(), "配置有效")

    val noUrl = SourceValidator.validateBook(loginUrl = null, 
        bookSourceUrl = "", bookSourceName = "x", searchUrl = null, ruleSearch = null,
        exploreUrl = null, enabledExplore = false, ruleToc = "a@text", ruleContent = "b"
    )
    eq("缺书源地址报错", noUrl.hasError, true)
    eq("错误字段是 bookSourceUrl", noUrl.errors().first().field, "bookSourceUrl")

    val badUrl = SourceValidator.validateBook(loginUrl = null, 
        bookSourceUrl = "ftp://a.com", bookSourceName = "x", searchUrl = null, ruleSearch = null,
        exploreUrl = null, enabledExplore = false, ruleToc = "a", ruleContent = "b"
    )
    eq("非 http 地址报错", badUrl.hasError, true)

    val halfSearch = SourceValidator.validateBook(loginUrl = null, 
        bookSourceUrl = "https://a.com", bookSourceName = "x", searchUrl = "/s",
        ruleSearch = null, exploreUrl = null, enabledExplore = false,
        ruleToc = "a", ruleContent = "b"
    )
    eq("只配搜索地址不报错但有告警", halfSearch.hasError, false)
    check("搜索告警存在", halfSearch.warnings().any { it.field == "searchUrl" })

    val noToc = SourceValidator.validateBook(loginUrl = null, 
        bookSourceUrl = "https://a.com", bookSourceName = "x", searchUrl = null, ruleSearch = null,
        exploreUrl = null, enabledExplore = false, ruleToc = null, ruleContent = "b"
    )
    eq("缺目录规则报错", noToc.hasError, true)

    val noContent = SourceValidator.validateBook(loginUrl = null, 
        bookSourceUrl = "https://a.com", bookSourceName = "x", searchUrl = null, ruleSearch = null,
        exploreUrl = null, enabledExplore = false, ruleToc = "a", ruleContent = null
    )
    eq("缺正文规则只是告警", noContent.hasError, false)
    check("正文告警存在", noContent.warnings().any { it.field == "ruleContent" })

    val audioNoContent = SourceValidator.validateBook(loginUrl = null, 
        bookSourceUrl = "https://a.com", bookSourceName = "x", searchUrl = null, ruleSearch = null,
        exploreUrl = null, enabledExplore = false, ruleToc = "a", ruleContent = null,
        bookSourceType = 1
    )
    eq("音频源缺正文不告警", audioNoContent.warnings().none { it.field == "ruleContent" }, true)

    val exploreWarn = SourceValidator.validateBook(loginUrl = null, 
        bookSourceUrl = "https://a.com", bookSourceName = "x", searchUrl = null, ruleSearch = null,
        exploreUrl = null, enabledExplore = true, ruleToc = "a", ruleContent = "b"
    )
    check("开发现却无地址告警", exploreWarn.warnings().any { it.field == "exploreUrl" })

    val badLogin = SourceValidator.validateBook(
        bookSourceUrl = "https://a.com", bookSourceName = "x", searchUrl = null, ruleSearch = null,
        exploreUrl = null, enabledExplore = false, ruleToc = "a", ruleContent = "b",
        loginUrl = "javascript:void(0)"
    )
    eq("非法登录地址报错", badLogin.hasError, true)

    val badJs = SourceValidator.validateBook(loginUrl = null, 
        bookSourceUrl = "https://a.com", bookSourceName = "x", searchUrl = null, ruleSearch = null,
        exploreUrl = null, enabledExplore = false,
        ruleToc = "@js:result = 'oops", ruleContent = "b"
    )
    eq("JS 引号未闭合报错", badJs.hasError, true)
    check("错误提示提到 JS", badJs.errors().first().message.contains("JS"))

    val goodJs = SourceValidator.validateBook(loginUrl = null, 
        bookSourceUrl = "https://a.com", bookSourceName = "x", searchUrl = null, ruleSearch = null,
        exploreUrl = null, enabledExplore = false,
        ruleToc = "@js:result = JSON.parse(html).data", ruleContent = "b"
    )
    eq("JS 配平不报错", goodJs.hasError, false)

    // ================= isHttpUrl =================
    eq("https 合法", SourceValidator.isHttpUrl("https://a.com"), true)
    eq("http 带路径合法", SourceValidator.isHttpUrl("http://a.com/x?y=1"), true)
    eq("localhost 合法", SourceValidator.isHttpUrl("http://localhost:8080"), true)
    eq("缺协议非法", SourceValidator.isHttpUrl("a.com"), false)
    eq("空值非法", SourceValidator.isHttpUrl(""), false)
    eq("null 非法", SourceValidator.isHttpUrl(null), false)
    eq("ftp 非法", SourceValidator.isHttpUrl("ftp://a.com"), false)
    eq("无点主机非法", SourceValidator.isHttpUrl("http://abc"), false)
    eq("协议相对非法", SourceValidator.isHttpUrl("//a.com"), false)

    // ================= looksLikeJson =================
    eq("空对象合法", SourceValidator.looksLikeJson("{}"), true)
    eq("空数组合法", SourceValidator.looksLikeJson("[]"), true)
    eq("嵌套合法", SourceValidator.looksLikeJson("{\"a\":[1,{\"b\":2}]}"), true)
    eq("缺右括号非法", SourceValidator.looksLikeJson("{"), false)
    eq("括号不配平非法", SourceValidator.looksLikeJson("{\"a\":1"), false)
    eq("多余右括号非法", SourceValidator.looksLikeJson("{\"a\":1}}"), false)
    eq("纯文本非法", SourceValidator.looksLikeJson("abc"), false)
    eq("字符串内括号不算", SourceValidator.looksLikeJson("{\"a\":\"}\"}"), true)
    eq("null 非法", SourceValidator.looksLikeJson(null), false)

    // ================= balanced =================
    eq("配平括号", SourceValidator.balanced("(a[b]{c})"), true)
    eq("未闭合圆括号", SourceValidator.balanced("(a"), false)
    eq("多余右括号", SourceValidator.balanced("a)"), false)
    eq("未闭合双引号", SourceValidator.balanced("\"abc"), false)
    eq("未闭合单引号", SourceValidator.balanced("'abc"), false)
    eq("字符串内括号不参与", SourceValidator.balanced("\"(((\""), true)
    eq("转义引号", SourceValidator.balanced("\"a\\\"b\""), true)

    // ================= 订阅源 =================
    val okRss = SourceValidator.validateRss(
        sourceUrl = "https://a.com/rss", sourceName = "订阅", sortUrl = "推荐::/rec",
        ruleArticles = "class.item", ruleTitle = "tag.a@text", ruleLink = "tag.a@href",
        searchUrl = null
    )
    eq("完整订阅源无错误", okRss.hasError, false)

    val rssNoUrl = SourceValidator.validateRss(
        sourceUrl = null, sourceName = "x", sortUrl = null, ruleArticles = null,
        ruleTitle = null, ruleLink = null, searchUrl = null
    )
    eq("订阅源缺地址报错", rssNoUrl.hasError, true)

    val rssNoTitle = SourceValidator.validateRss(
        sourceUrl = "https://a.com", sourceName = "x", sortUrl = null,
        ruleArticles = "class.item", ruleTitle = null, ruleLink = "a@href", searchUrl = null
    )
    eq("有列表无标题报错", rssNoTitle.hasError, true)

    val rssNoLink = SourceValidator.validateRss(
        sourceUrl = "https://a.com", sourceName = "x", sortUrl = null,
        ruleArticles = "class.item", ruleTitle = "a@text", ruleLink = null, searchUrl = null
    )
    eq("有列表无链接报错", rssNoLink.hasError, true)

    val rssBadType = SourceValidator.validateRss(
        sourceUrl = "https://a.com", sourceName = "x", sortUrl = null,
        ruleArticles = null, ruleTitle = null, ruleLink = null, searchUrl = null, type = 9
    )
    eq("非法内容类型报错", rssBadType.hasError, true)

    val rssBadSort = SourceValidator.validateRss(
        sourceUrl = "https://a.com", sourceName = "x", sortUrl = "只有名字没有地址",
        ruleArticles = null, ruleTitle = null, ruleLink = null, searchUrl = null
    )
    eq("分类行缺分隔只是告警", rssBadSort.hasError, false)
    check("分类分隔告警存在", rssBadSort.warnings().any { it.field == "sortUrl" })

    // 无规则的纯 RSS 兜底：合法
    val pureRss = SourceValidator.validateRss(
        sourceUrl = "https://a.com/feed.xml", sourceName = "纯RSS", sortUrl = null,
        ruleArticles = null, ruleTitle = null, ruleLink = null, searchUrl = null
    )
    eq("纯 RSS 兜底合法", pureRss.hasError, false)

    // ================= 影视站点 =================
    val okVideo = SourceValidator.validateVideoSite(
        configUrl = "https://a.com/api.php/provide/vod/", siteKey = "k1",
        name = "站点", type = 1, api = "https://a.com/api.php/provide/vod/", jar = null, ext = null
    )
    eq("正常站点无错误", okVideo.hasError, false)

    val drpySite = SourceValidator.validateVideoSite(
        configUrl = "https://a.com", siteKey = "k2", name = "drpy", type = 3,
        api = "https://a.com/drpy.js", jar = null, ext = null
    )
    eq("drpy 站点合法", drpySite.hasError, false)

    val jarSite = SourceValidator.validateVideoSite(
        configUrl = "https://a.com", siteKey = "k3", name = "jar站", type = 3,
        api = "csp_AppYs", jar = "https://a.com/a.jar", ext = null
    )
    eq("jar 站点报错(暂不支持)", jarSite.hasError, true)
    check("错误字段是 jar", jarSite.errors().any { it.field == "jar" })

    val noApi = SourceValidator.validateVideoSite(
        configUrl = "https://a.com", siteKey = "k4", name = "x", type = 1,
        api = null, jar = null, ext = null
    )
    eq("缺 api 报错", noApi.hasError, true)

    val badType = SourceValidator.validateVideoSite(
        configUrl = "https://a.com", siteKey = "k5", name = "x", type = 7,
        api = "https://a.com/api", jar = null, ext = null
    )
    eq("非法站点类型报错", badType.hasError, true)

    val badExt = SourceValidator.validateVideoSite(
        configUrl = "https://a.com", siteKey = "k6", name = "x", type = 1,
        api = "https://a.com/api", jar = null, ext = "not-json"
    )
    eq("非法 ext 只是告警", badExt.hasError, false)
    check("ext 告警存在", badExt.warnings().any { it.field == "ext" })

    val okExt = SourceValidator.validateVideoSite(
        configUrl = "https://a.com", siteKey = "k7", name = "x", type = 1,
        api = "https://a.com/api", jar = null, ext = "{\"filter\":{}}"
    )
    eq("合法 ext 无告警", okExt.warnings().none { it.field == "ext" }, true)

    // ================= 摘要文案 =================
    val warnOnly = SourceValidator.Report(listOf(
        SourceValidator.Issue("a", Level.WARN, "提示一"),
        SourceValidator.Issue("b", Level.WARN, "提示二")
    ))
    eq("仅告警摘要", warnOnly.summary(), "可用，2 处提示")
    eq("仅告警仍有效", warnOnly.isValid, true)
    val errReport = SourceValidator.Report(listOf(
        SourceValidator.Issue("a", Level.ERROR, "错了")
    ))
    eq("错误摘要", errReport.summary(), "存在 1 处错误：错了")
    eq("错误即无效", errReport.isValid, false)
    eq("空报告有效", SourceValidator.Report(emptyList()).isValid, true)

    println("================ 源调度/校验单测 ================")
    println("PASS = $pass   FAIL = $fail")
    if (failures.isNotEmpty()) {
        println("---- failures ----")
        failures.forEach { println("  " + it) }
    }
    if (fail > 0) kotlin.system.exitProcess(1)
}
