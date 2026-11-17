import com.lanyue.reader.source.import.SourceEntityMapper

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

/** 一条规则齐全的真实书源。 */
private val fullBook = """
{
  "bookSourceUrl": "https://www.example.com",
  "bookSourceName": "示例书源",
  "bookSourceGroup": "小说",
  "bookSourceType": 0,
  "enabled": true,
  "enabledExplore": true,
  "searchUrl": "/search?q={{key}}&p={{page}}",
  "exploreUrl": "玄幻::/list/xhuan",
  "ruleSearch": {"bookList": "class.book-list@li", "name": "tag.a@text", "author": "class.author@text"},
  "ruleExplore": "class.item",
  "ruleToc": {"chapterList": "class.chapter@li", "chapterName": "tag.a@text", "chapterUrl": "tag.a@href"},
  "ruleContent": "id.content@html",
  "ruleBookInfo": {"name": "class.title@text", "author": "class.author@text"},
  "header": "{\"User-Agent\":\"Mozilla\"}",
  "concurrentRate": "2/1000",
  "lastUpdateTime": 1700000000000,
  "replaceRule": "[{\"pattern\":\"广告\",\"replacement\":\"\"}]"
}
""".trimIndent()

fun main() {
    // ================= 一、完整书源：规则字段全部落地 =================
    val e = SourceEntityMapper.toBookEntity(fullBook)
    check("完整书源能映射", e != null)
    if (e != null) {
        eq("地址", e.bookSourceUrl, "https://www.example.com")
        eq("名称", e.bookSourceName, "示例书源")
        eq("分组", e.bookSourceGroup, "小说")
        eq("类型", e.bookSourceType, 0)
        eq("启用", e.enabled, true)

        // 这是本次修复的核心：规则必须真的进实体，而不是只躺在 raw 里
        eq("searchUrl 落地", e.searchUrl, "/search?q={{key}}&p={{page}}")
        check("ruleSearch 落地", !e.ruleSearch.isNullOrBlank(), "ruleSearch=${e.ruleSearch}")
        check("ruleSearch 含 bookList", e.ruleSearch?.contains("bookList") == true)
        check("ruleSearch 保留了 name 规则", e.ruleSearch?.contains("tag.a@text") == true)
        eq("ruleContent 原文保留", e.ruleContent, "id.content@html")
        check("ruleToc 落地", !e.ruleToc.isNullOrBlank())
        check("ruleBookInfo 落地", !e.ruleBookInfo.isNullOrBlank())
        eq("ruleContent 未被加引号", e.ruleContent!!.startsWith("\""), false)

        eq("exploreUrl", e.exploreUrl, "玄幻::/list/xhuan")
        eq("ruleExplore", e.ruleExplore, "class.item")
        eq("header 原样", e.header, "{\"User-Agent\":\"Mozilla\"}")
        eq("并发", e.concurrentRate, "2/1000")
        eq("更新时间", e.lastUpdateTime, 1700000000000L)
        check("replaceRule 落地", !e.replaceRule.isNullOrBlank())

        // 入库后应当可搜索、可发现
        eq("可搜索", SourceEntityMapper.isSearchable(e), true)
        eq("可发现", SourceEntityMapper.isDiscoverable(e), true)
    }

    // ================= 二、字符串规则不能被 JSON 化污染 =================
    // 若把 "class.content@html" 序列化成 "\"class.content@html\""，
    // RuleEngine 会把引号当选择器的一部分，规则直接失效。
    val strRule = SourceEntityMapper.toBookEntity(
        """{"bookSourceUrl":"https://a.com","ruleContent":"class.content@html"}"""
    )
    eq("字符串规则不加引号", strRule?.ruleContent, "class.content@html")
    eq("字符串规则无包裹引号", strRule?.ruleContent?.contains("\"") , false)

    // 对象规则以紧凑 JSON 存（与 Converters.objectToJson 同一约定）
    val objRule = SourceEntityMapper.toBookEntity(
        """{"bookSourceUrl":"https://b.com","ruleToc":{"chapterList":"class.c@li","chapterName":"tag.a@text"}}"""
    )
    check("对象规则转 JSON 串", objRule?.ruleToc?.startsWith("{") == true, "ruleToc=${objRule?.ruleToc}")
    check("对象规则内容完整", objRule?.ruleToc?.contains("chapterList") == true)

    // ================= 三、类型宽容：真实书源常见脏类型 =================
    val strType = SourceEntityMapper.toBookEntity(
        """{"bookSourceUrl":"https://c.com","bookSourceType":"1","enabled":"false","enabledExplore":1}"""
    )
    eq("字符串类型转 Int", strType?.bookSourceType, 1)
    eq("字符串 false 转 Boolean", strType?.enabled, false)
    eq("数字 1 转 true", strType?.enabledExplore, true)

    val numEnabled = SourceEntityMapper.toBookEntity("""{"bookSourceUrl":"https://d.com","enabled":0}""")
    eq("数字 0 转 false", numEnabled?.enabled, false)

    val missingEnabled = SourceEntityMapper.toBookEntity("""{"bookSourceUrl":"https://e.com"}""")
    eq("缺 enabled 默认 true", missingEnabled?.enabled, true)
    eq("缺类型默认 0", missingEnabled?.bookSourceType, 0)
    eq("缺名称默认空串", missingEnabled?.bookSourceName, "")

    // 浮点型 lastUpdateTime（JSON 里没引号的大数会被解析成浮点）
    val floatTime = SourceEntityMapper.toBookEntity(
        """{"bookSourceUrl":"https://f.com","lastUpdateTime":1700000000000}"""
    )
    check("大数时间不丢精度", floatTime?.lastUpdateTime == 1700000000000L, "got=${floatTime?.lastUpdateTime}")

    // ================= 四、判废：只拒不可用的，不乱拒 =================
    eq("空串判废", SourceEntityMapper.toBookEntity(""), null)
    eq("null 判废", SourceEntityMapper.toBookEntity(null), null)
    eq("非 JSON 判废", SourceEntityMapper.toBookEntity("这不是 JSON"), null)
    eq("JSON 数组判废", SourceEntityMapper.toBookEntity("[1,2,3]"), null)
    eq("缺 bookSourceUrl 判废", SourceEntityMapper.toBookEntity("""{"bookSourceName":"无名"}"""), null)
    eq("非 http 地址判废", SourceEntityMapper.toBookEntity("""{"bookSourceUrl":"ftp://x.com"}"""), null)
    eq("空地址判废", SourceEntityMapper.toBookEntity("""{"bookSourceUrl":""}"""), null)

    // 只有地址、没有任何规则的源**不判废**：它可能是纯 WebView 源（1.4.2）
    val bare = SourceEntityMapper.toBookEntity("""{"bookSourceUrl":"https://g.com"}""")
    check("无规则的源仍可入库", bare != null)
    eq("无规则不可搜索", bare?.let { SourceEntityMapper.isSearchable(it) }, false)
    eq("无规则无 searchUrl", bare?.searchUrl, null)
    eq("无规则 ruleContent 为空", bare?.ruleContent, null)

    // http 与 https 都放行
    check("http 地址接受", SourceEntityMapper.toBookEntity("""{"bookSourceUrl":"http://h.com"}""") != null)

    // ================= 五、可搜索判定与搜索池一致 =================
    val noRule = SourceEntityMapper.toBookEntity(
        """{"bookSourceUrl":"https://i.com","searchUrl":"/s?q={{key}}"}"""
    )
    eq("有 searchUrl 无 ruleSearch 不可搜", noRule?.let { SourceEntityMapper.isSearchable(it) }, false)

    val degraded = SourceEntityMapper.toBookEntity(
        """{"bookSourceUrl":"https://j.com","searchUrl":"/s","ruleSearch":"class.b@li","searchDegraded":true}"""
    )
    eq("降级源不可搜", degraded?.let { SourceEntityMapper.isSearchable(it) }, false)

    val disabled = SourceEntityMapper.toBookEntity(
        """{"bookSourceUrl":"https://k.com","searchUrl":"/s","ruleSearch":"class.b@li","enabled":false}"""
    )
    eq("停用源不可搜", disabled?.let { SourceEntityMapper.isSearchable(it) }, false)

    val okSearch = SourceEntityMapper.toBookEntity(
        """{"bookSourceUrl":"https://l.com","searchUrl":"/s","ruleSearch":"class.b@li"}"""
    )
    eq("齐全才可搜", okSearch?.let { SourceEntityMapper.isSearchable(it) }, true)

    // 可发现需 enabledExplore + exploreUrl 同时具备
    val expNoFlag = SourceEntityMapper.toBookEntity(
        """{"bookSourceUrl":"https://m.com","exploreUrl":"分类::/a"}"""
    )
    eq("缺 enabledExplore 不可发现", expNoFlag?.let { SourceEntityMapper.isDiscoverable(it) }, false)

    // ================= 六、批量：坏条目不连坐 =================
    val batch = SourceEntityMapper.mapBooks(
        listOf(
            fullBook,
            "垃圾",
            """{"bookSourceUrl":"https://n.com","bookSourceName":"正常"}""",
            """{"bookSourceUrl":"not-a-url"}"""
        )
    )
    eq("批量接受 2 条", batch.accepted, 2)
    eq("批量拒绝 2 条", batch.rejected, 2)
    eq("批量总数", batch.total, 4)
    eq("拒绝下标", batch.rejects.map { it.index }, listOf(1, 3))
    check("拒绝带原因", batch.rejects.all { it.reason.isNotBlank() })
    eq("拒绝原因-非JSON", batch.rejects.firstOrNull { it.index == 1 }?.reason, "不是 JSON 对象")
    eq("拒绝原因-坏地址", batch.rejects.firstOrNull { it.index == 3 }?.reason, "bookSourceUrl 不是 http/https")
    eq("保序", batch.entities.map { it.bookSourceUrl },
        listOf("https://www.example.com", "https://n.com"))

    // 同 url 重复：后者覆盖前者（与 SourceRepository.install 去重语义一致）
    val dup = SourceEntityMapper.mapBooks(
        listOf(
            """{"bookSourceUrl":"https://p.com","bookSourceName":"旧"}""",
            """{"bookSourceUrl":"https://p.com","bookSourceName":"新"}"""
        )
    )
    eq("重复只留一条", dup.accepted, 1)
    eq("重复留后者", dup.entities.first().bookSourceName, "新")

    // 空列表
    eq("空列表零接受", SourceEntityMapper.mapBooks(emptyList()).accepted, 0)
    eq("全空零接受", SourceEntityMapper.mapBooks(listOf(null, "")).accepted, 0)
    eq("全空两条拒绝", SourceEntityMapper.mapBooks(listOf(null, "")).rejected, 2)

    // ================= 七、订阅源映射 =================
    val rss = """
    {
      "sourceUrl": "https://rss.example.com",
      "sourceName": "示例订阅",
      "sourceGroup": "资讯",
      "type": 0,
      "enabled": true,
      "sortUrl": "全部::/all",
      "ruleArticles": "class.post@li",
      "ruleTitle": "tag.a@text",
      "ruleLink": "tag.a@href",
      "rulePubDate": "class.date@text",
      "ruleImage": "tag.img@src",
      "ruleDescription": "class.desc@text",
      "ruleContent": "class.content@html",
      "searchUrl": "/search?q={{key}}",
      "articleStyle": 1,
      "preload": true,
      "cacheFirst": true
    }
    """.trimIndent()

    val r = SourceEntityMapper.toRssEntity(rss)
    check("订阅源能映射", r != null)
    if (r != null) {
        eq("订阅地址", r.sourceUrl, "https://rss.example.com")
        eq("订阅名称", r.sourceName, "示例订阅")
        eq("订阅分组", r.sourceGroup, "资讯")
        // 订阅源规则是扁平字符串，不能被 JSON 化
        eq("ruleArticles 原样", r.ruleArticles, "class.post@li")
        eq("ruleTitle 原样", r.ruleTitle, "tag.a@text")
        eq("ruleLink 原样", r.ruleLink, "tag.a@href")
        eq("ruleContent 原样", r.ruleContent, "class.content@html")
        eq("ruleArticles 无包裹引号", r.ruleArticles?.contains("\""), false)
        eq("sortUrl", r.sortUrl, "全部::/all")
        eq("扩展搜索地址", r.searchUrl, "/search?q={{key}}")
        eq("articleStyle", r.articleStyle, 1)
        eq("preload", r.preload, true)
        eq("cacheFirst", r.cacheFirst, true)
    }

    // 订阅源判废条件同书源
    eq("订阅缺地址判废", SourceEntityMapper.toRssEntity("""{"sourceName":"无地址"}"""), null)
    eq("订阅坏地址判废", SourceEntityMapper.toRssEntity("""{"sourceUrl":"abc"}"""), null)
    eq("订阅非 JSON 判废", SourceEntityMapper.toRssEntity("{oops"), null)
    // 只有地址的 RSS 源合法：ruleArticles 空时走 RSS/Atom 兜底（ADR-016）
    val bareRss = SourceEntityMapper.toRssEntity("""{"sourceUrl":"https://feed.com/rss"}""")
    check("裸 RSS 源可入库", bareRss != null)
    eq("裸 RSS 源 ruleArticles 为空", bareRss?.ruleArticles, null)

    // 批量订阅源
    val rssBatch = SourceEntityMapper.mapRss(
        listOf(rss, "坏", """{"sourceUrl":"https://f2.com","sourceName":"订阅二"}""")
    )
    eq("订阅批量接受 2", rssBatch.accepted, 2)
    eq("订阅批量拒绝 1", rssBatch.rejected, 1)
    eq("订阅拒绝下标", rssBatch.rejects.map { it.index }, listOf(1))

    // 订阅源 type 宽容
    val rssStrType = SourceEntityMapper.toRssEntity("""{"sourceUrl":"https://t.com","type":"2"}""")
    eq("订阅字符串类型", rssStrType?.type, 2)

    // ================= 八、与 RuleEngine 的衔接前提 =================
    // 映射器输出的规则串必须能被 RuleEngine 直接消费：
    // 嵌套对象字段要能取到具体规则值，扁平字段要原样可用。
    val m = SourceEntityMapper.toBookEntity(fullBook)
    if (m != null) {
        check("ruleToc 是 JSON 对象串", m.ruleToc?.trim()?.startsWith("{") == true)
        check("ruleContent 是纯规则", m.ruleContent == "id.content@html")
        eq("searchUrl 含 key 占位", m.searchUrl?.contains("{{key}}"), true)
        eq("searchUrl 含 page 占位", m.searchUrl?.contains("{{page}}"), true)
    }

    println("================ 书源/订阅源实体映射（T 修复断链）单测 ================")
    println("PASS = $pass   FAIL = $fail")
    if (failures.isNotEmpty()) {
        println("---- failures ----")
        failures.forEach { println("  " + it) }
    }
    if (fail > 0) kotlin.system.exitProcess(1)
}
