import com.lanyue.reader.source.import.SourceEntityConverter

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

fun main() {
    // ================= 模板归一化 =================
    eq("searchKey 占位符", SourceEntityConverter.normalizeTemplate("http://a/s?q={searchKey}"),
        "http://a/s?q={{key}}")
    eq("name 占位符", SourceEntityConverter.normalizeTemplate("http://a/s?k={name}"),
        "http://a/s?k={{key}}")
    eq("page 占位符", SourceEntityConverter.normalizeTemplate("http://a/s?p={page}"),
        "http://a/s?p={{page}}")
    eq("searchPage 占位符", SourceEntityConverter.normalizeTemplate("http://a/s?p={searchPage}"),
        "http://a/s?p={{page}}")
    eq("key 原样", SourceEntityConverter.normalizeTemplate("http://a/s?k={key}"),
        "http://a/s?k={{key}}")
    eq("无占位符不变", SourceEntityConverter.normalizeTemplate("http://a.com/s"),
        "http://a.com/s")

    // ================= Legado 英文格式（嵌套规则对象）================
    val legado = """{"bookSourceUrl":"https://lg.com/s","bookSourceName":"Legado",
        "bookSourceGroup":"小说","bookSourceType":0,
        "searchUrl":"https://lg.com/s?q={searchKey}",
        "ruleSearch":{"bookList":"","name":"$.x","author":"$.y"},
        "ruleBookInfo":{"name":"$.a","author":"$.b"},
        "ruleToc":{"chapterList":"$.c li a@href","title":"$.t"},
        "ruleContent":{"content":"$.d"},
        "header":"UA/1.0","concurrentRate":"1"}"""
    val e1 = SourceEntityConverter.toBookEntity(legado)
    check("Legado 能转换", e1 != null, "返回 null")
    if (e1 != null) {
        eq("Legado 地址", e1.bookSourceUrl, "https://lg.com/s")
        eq("Legado 名称", e1.bookSourceName, "Legado")
        eq("Legado 分组", e1.bookSourceGroup, "小说")
        eq("Legado 类型", e1.bookSourceType, 0)
        eq("Legado searchUrl 归一化", e1.searchUrl, "https://lg.com/s?q={{key}}")
        eq("Legado header", e1.header, "UA/1.0")
        eq("Legado 并发率", e1.concurrentRate, "1")
        // 嵌套规则对象原样序列化，不为空且是合法 JSON 对象
        check("Legado ruleSearch 非空", !e1.ruleSearch.isNullOrBlank(), e1.ruleSearch.orEmpty())
        check("Legado ruleSearch 含 bookList",
            e1.ruleSearch?.contains("bookList") == true, e1.ruleSearch.orEmpty())
        check("Legado ruleBookInfo 含 name",
            e1.ruleBookInfo?.contains("name") == true, e1.ruleBookInfo.orEmpty())
        check("Legado ruleToc 含 chapterList",
            e1.ruleToc?.contains("chapterList") == true, e1.ruleToc.orEmpty())
        check("Legado ruleContent 含 content",
            e1.ruleContent?.contains("content") == true, e1.ruleContent.orEmpty())
        // 嵌套规则对象原样返回：含 name 键（单一 bookList 包裹则无），证明未被二次包裹
        check("Legado ruleSearch 是原始对象（含 name 键）",
            e1.ruleSearch?.contains("\"name\"") == true, e1.ruleSearch.orEmpty())
    }

    // ================= 乐享中文扁平格式（无地址键，从模板推导）================
    val lexiang = """{"名称":"ABC小说","类型":0,"分组":"默认",
        "搜索":"http://www.abc.com/s?type={type}&key={name}",
        "详情":"http://www.abc.com/book/{id}",
        "目录":"$.div//li/a",
        "内容":"$.div//div"}"""
    val e2 = SourceEntityConverter.toBookEntity(lexiang)
    check("乐享能转换（地址可推导）", e2 != null, "返回 null")
    if (e2 != null) {
        eq("乐享地址从搜索模板推导", e2.bookSourceUrl, "http://www.abc.com/s")
        eq("乐享名称", e2.bookSourceName, "ABC小说")
        eq("乐享分组", e2.bookSourceGroup, "默认")
        eq("乐享类型", e2.bookSourceType, 0)
        // {name} -> {{key}}；{type} 不在本引擎占位符表，原样保留
        eq("乐享 searchUrl 归一化",
            e2.searchUrl, "http://www.abc.com/s?type={type}&key={{key}}")
        // 搜索是 URL 模板（已入 searchUrl），不作为 result 规则塞进 ruleSearch
        check("乐享 搜索是 URL 不入 ruleSearch",
            e2.ruleSearch == null, e2.ruleSearch ?: "null")
        check("乐享 ruleToc 包裹 chapterList",
            e2.ruleToc?.startsWith("{\"chapterList\":") == true, e2.ruleToc.orEmpty())
        check("乐享 ruleContent 取内容规则",
            e2.ruleContent?.contains("$.div//div") == true, e2.ruleContent.orEmpty())
    }

    // ================= 乐享音频书源（类型 1）================
    val lexiangAudio = """{"名称":"XYZ听书","类型":1,"分组":"有声",
        "searchUrl":"http://xyz.audio/s","ruleSearch":"http://xyz.audio/s?name={name}",
        "ruleToc":"http://xyz.audio/book/{id}","ruleContent":"$.div//a"}"""
    val e3 = SourceEntityConverter.toBookEntity(lexiangAudio)
    check("乐享音频能转换", e3 != null)
    if (e3 != null) {
        eq("乐享音频类型", e3.bookSourceType, 1)
        eq("乐享音频显式 searchUrl", e3.searchUrl, "http://xyz.audio/s")
        eq("乐享音频分组", e3.bookSourceGroup, "有声")
    }

    // ================= 订阅源（id + 扁平规则）================
    val feed = """{"id":"http://rss.a.com/feed","名称":"A订阅","分组":"资讯","类型":0,
        "列表":"$.item","标题":"$.title","链接":"$.link"}"""
    val r1 = SourceEntityConverter.toRssEntity(feed)
    check("订阅源能转换", r1 != null)
    if (r1 != null) {
        eq("订阅源地址", r1.sourceUrl, "http://rss.a.com/feed")
        eq("订阅源名称", r1.sourceName, "A订阅")
        eq("订阅源分组", r1.sourceGroup, "资讯")
        eq("订阅源类型", r1.type, 0)
        eq("订阅源列表规则", r1.ruleArticles, "$.item")
        eq("订阅源标题规则", r1.ruleTitle, "$.title")
        eq("订阅源链接规则", r1.ruleLink, "$.link")
    }

    // ================= 订阅源（Legado 嵌套对象 ruleArticles 取 list）================
    val feedNested = """{"id":"http://rss.b.com/feed","名称":"B订阅",
        "ruleArticles":{"list":"@latest||<rule>@list","title":"<rule>@text"}}"""
    val r2 = SourceEntityConverter.toRssEntity(feedNested)
    check("订阅嵌套能转换", r2 != null)
    if (r2 != null) {
        eq("订阅嵌套列表取 list", r2.ruleArticles, "@latest||<rule>@list")
    }

    // ================= 订阅源分类模板归一化 =================
    val feedSort = """{"id":"http://rss.c.com/feed","名称":"C订阅","分类":
        "http://rss.c.com/cat/{page}"}"""
    val r3 = SourceEntityConverter.toRssEntity(feedSort)
    check("订阅分类能转换", r3 != null)
    if (r3 != null) {
        eq("订阅分类归一化", r3.sortUrl, "http://rss.c.com/cat/{{page}}")
    }

    // ================= 边界：地址缺失 / 非法 / 类型越界 =================
    // 没有任何地址键、也没有地址模板
    eq("无地址键返回 null",
        SourceEntityConverter.toBookEntity("""{"bookSourceName":"无地址"}"""), null)
    // 地址非 http
    eq("ftp 地址返回 null",
        SourceEntityConverter.toBookEntity("""{"bookSourceUrl":"ftp://x.com","bookSourceName":"F"}"""), null)
    // 非 JSON 对象
    eq("数组返回 null",
        SourceEntityConverter.toBookEntity("""[{"bookSourceUrl":"https://a.com"}]"""), null)
    eq("坏 JSON 返回 null",
        SourceEntityConverter.toBookEntity("""{this is broken"""), null)
    eq("空串返回 null", SourceEntityConverter.toBookEntity(""), null)
    // 类型越界夹紧
    val clamp = SourceEntityConverter.toBookEntity(
        """{"bookSourceUrl":"https://t.com","bookSourceName":"T","bookSourceType":99}"""
    )
    check("类型越界能转换", clamp != null)
    if (clamp != null) eq("类型夹到 3", clamp.bookSourceType, 3)
    // 无名称时用域名兜底
    val noName = SourceEntityConverter.toBookEntity(
        """{"bookSourceUrl":"https://host-only.com/path"}"""
    )
    check("无名称能转换", noName != null)
    if (noName != null) eq("无名称用域名", noName.bookSourceName, "host-only.com")

    println("================ SourceEntityConverter 单测 ================")
    println("PASS = $pass   FAIL = $fail")
    if (failures.isNotEmpty()) {
        println("---- failures ----")
        failures.forEach { println("  " + it) }
    }
    if (fail > 0) kotlin.system.exitProcess(1)
}
