import com.lanyue.reader.data.db.entity.BookSourceEntity
import com.lanyue.reader.data.db.entity.RssSourceEntity
import com.lanyue.reader.data.db.entity.VideoSiteEntity
import com.lanyue.reader.source.model.CapabilityResolver
import com.lanyue.reader.source.model.SourceCapability

private var pass = 0
private var fail = 0

private fun eq(label: String, actual: Any?, expected: Any?) {
    if (actual == expected) { pass++; println("[PASS] $label") }
    else { fail++; println("[FAIL] $label -> actual=$actual expected=$expected") }
}

private fun check(label: String, cond: Boolean, detail: String = "") {
    if (cond) { pass++; println("[PASS] $label") }
    else { fail++; println("[FAIL] $label -> $detail") }
}

fun main() {
    // ---------------- 书源能力 ----------------
    val fullBook = CapabilityResolver.ofBook(
        BookSourceEntity(
            bookSourceUrl = "http://a.com",
            bookSourceName = "书",
            searchUrl = "http://a.com/s",
            ruleSearch = "r1",
            enabledExplore = true,
            exploreUrl = "http://a.com/e",
            ruleContent = "rc",
            loginUrl = "http://a.com/login"
        )
    )
    eq(
        "书源全集含登录优先",
        fullBook,
        setOf(
            SourceCapability.SEARCHABLE,
            SourceCapability.DISCOVERABLE,
            SourceCapability.HAS_CONTENT,
            SourceCapability.HIGH_PRIORITY
        )
    )
    eq("集合大小4", fullBook.size, 4)

    val noLogin = CapabilityResolver.ofBook(
        BookSourceEntity(bookSourceUrl = "http://a.com", searchUrl = "u", ruleSearch = "r", ruleContent = "rc")
    )
    eq(
        "无登录只缺优先级",
        noLogin,
        setOf(SourceCapability.SEARCHABLE, SourceCapability.HAS_CONTENT)
    )

    val degraded = CapabilityResolver.ofBook(
        BookSourceEntity(bookSourceUrl = "http://a.com", searchUrl = "u", ruleSearch = "r", searchDegraded = true)
    )
    check("降级退攀登SEARCHABLE", !degraded.contains(SourceCapability.SEARCHABLE), degraded.toString())
    eq("无正文降级后仅剩空集合校验", degraded, emptySet<SourceCapability>())

    check(
        "空规则不可搜",
        !CapabilityResolver.ofBook(BookSourceEntity(bookSourceUrl = "http://a.com", searchUrl = "u", ruleSearch = ""))
            .contains(SourceCapability.SEARCHABLE)
    )
    check(
        "只配规则不配地址不可搜",
        !CapabilityResolver.ofBook(BookSourceEntity(bookSourceUrl = "http://a.com", ruleSearch = "r"))
            .contains(SourceCapability.SEARCHABLE)
    )
    check(
        "空地址字符串不可搜",
        !CapabilityResolver.ofBook(
            BookSourceEntity(bookSourceUrl = "http://a.com", searchUrl = " ", ruleSearch = "r")
        ).contains(SourceCapability.SEARCHABLE)
    )
    check(
        "仅搜索能力也成立",
        CapabilityResolver.ofBook(
            BookSourceEntity(bookSourceUrl = "http://a.com", searchUrl = "u", ruleSearch = "r")
        ).contains(SourceCapability.SEARCHABLE)
    )
    check(
        "幂等：同实体两次结果一致",
        CapabilityResolver.ofBook(BookSourceEntity(bookSourceUrl = "u", searchUrl = "s", ruleSearch = "r")) ==
            CapabilityResolver.ofBook(BookSourceEntity(bookSourceUrl = "u", searchUrl = "s", ruleSearch = "r"))
    )

    // ---------------- 订阅源能力 ----------------
    val fullRss = CapabilityResolver.ofRss(
        RssSourceEntity(
            sourceUrl = "http://a.com/rss",
            searchUrl = "http://a.com/s",
            sortUrl = "漫画::http://a.com/m",
            ruleContent = "rc",
            ruleArticles = "articles"
        )
    )
    eq(
        "订阅常规三能力",
        fullRss,
        setOf(SourceCapability.SEARCHABLE, SourceCapability.DISCOVERABLE, SourceCapability.HAS_CONTENT)
    )
    check("有articles不置兜底", !fullRss.contains(SourceCapability.RSS_FALLBACK))

    check(
        "无articles置兜底",
        CapabilityResolver.ofRss(
            RssSourceEntity(sourceUrl = "http://a.com/rss", searchUrl = "u", ruleContent = "rc")
        ).contains(SourceCapability.RSS_FALLBACK)
    )
    eq(
        "rss降级去搜索",
        CapabilityResolver.ofRss(
            RssSourceEntity(
                sourceUrl = "http://a.com/rss",
                searchUrl = "u",
                sortUrl = "c::u",
                ruleArticles = "a",
                searchDegraded = true
            )
        ),
        setOf(SourceCapability.DISCOVERABLE)
    )
    eq(
        "rss无ruleArticles置兜底",
        CapabilityResolver.ofRss(
            RssSourceEntity(sourceUrl = "http://a.com/rss", searchUrl = "u")
        ),
        setOf(SourceCapability.SEARCHABLE, SourceCapability.RSS_FALLBACK)
    )
    eq(
        "rss仅兜底：无搜索无菜单",
        CapabilityResolver.ofRss(RssSourceEntity(sourceUrl = "http://a.com/rss")),
        setOf(SourceCapability.RSS_FALLBACK)
    )

    // ---------------- 影视站点 ----------------
    eq(
        "影视双能",
        CapabilityResolver.ofVideoSite(
            VideoSiteEntity(configUrl = "http://a.com", siteKey = "v", searchable = true, filterable = true)
        ),
        setOf(SourceCapability.SEARCHABLE, SourceCapability.FILTERABLE)
    )
    eq(
        "不可筛只留搜索",
        CapabilityResolver.ofVideoSite(
            VideoSiteEntity(configUrl = "u", siteKey = "v", searchable = true)
        ),
        setOf(SourceCapability.SEARCHABLE)
    )
    eq(
        "不可搜空集",
        CapabilityResolver.ofVideoSite(
            VideoSiteEntity(configUrl = "u", siteKey = "v", searchable = false)
        ),
        emptySet<SourceCapability>()
    )

    println("=====源配置文件级能力判定 =====")
    println("PASS = $pass   FAIL = $fail")
    if (fail > 0) kotlin.system.exitProcess(1)
}
