import com.lanyue.reader.source.model.SourceValidator
import com.lanyue.reader.source.model.SourceValidator.Level
import com.lanyue.reader.source.model.SourceValidator.Issue
import com.lanyue.reader.source.model.SourceValidator.Report

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
    val V = SourceValidator

    // ---------------- 书源校验 ----------------
    val bookOk = V.validateBook(
        bookSourceUrl = "http://src.lanyue.test/meta",
        bookSourceName = "优质书源",
        searchUrl = "http://src.lanyue.test/search",
        ruleSearch = "search##right@text",
        exploreUrl = null,
        enabledExplore = false,
        ruleToc = "class.list@a",
        ruleContent = "class.content@html",
        loginUrl = ""
    )
    check("完整文本源通过", bookOk.isValid, bookOk.summary())
    check("有效源零问题", bookOk.issues.isEmpty())

    val bookBad = V.validateBook(
        bookSourceUrl = "",
        bookSourceName = "",
        searchUrl = null,
        ruleSearch = null,
        exploreUrl = null,
        enabledExplore = false,
        ruleToc = "",
        ruleContent = "",
        loginUrl = "notaurl"
    )
    check("空/非法源被拦截", !bookBad.isValid)
    eq("空地址错误字段", bookBad.errors().first().field, "bookSourceUrl")
    eq("缺toc报错数", bookBad.errors().filter { it.field == "ruleToc" }.size, 1)
    check("非法loginUrl报错误", bookBad.errors().any { it.field == "loginUrl" })
    check("名称为空是提示", bookBad.warnings().any { it.field == "bookSourceName" })
    check("缺正文是提示", bookBad.warnings().any { it.field == "ruleContent" })

    val halfSearch = V.validateBook("http://a.com", "s", "http://a.com/s", null, "", false, "t", "c", "")
    eq("搜索半项报一条提示", halfSearch.warnings().filter { it.field == "searchUrl" }.size, 1)
    val halfSearch2 = V.validateBook("http://a.com", "s", null, "r", "", false, "t", "c", "")
    check("缺searchUrl同样半项提示", halfSearch2.warnings().any { it.field == "searchUrl" })

    val exploreOff = V.validateBook("http://a.com", "s", "u", "r", "http://a.com/e", false, "t", "c", "")
    check("发现地址存在但关闭=提示", exploreOff.warnings().any { it.field == "exploreUrl" })
    val exploreOnNoUrl = V.validateBook("http://a.com", "s", "u", "r", "", true, "t", "c", "")
    check("开发现但无地址=提示", exploreOnNoUrl.warnings().any { it.field == "exploreUrl" })

    val audioNoContent = V.validateBook(
        bookSourceUrl = "http://a.com", bookSourceName = "a", searchUrl = "u", ruleSearch = "r",
        exploreUrl = null, enabledExplore = false, ruleToc = "t", ruleContent = "",
        loginUrl = "", bookSourceType = 1
    )
    check("音频源缺正文可接受", audioNoContent.isValid && audioNoContent.warnings().none { it.field == "ruleContent" })

    val jsBadBook = V.validateBook("http://a.com", "s", "u", "r", "", false, "t", "@js:if(x){", "")
    check("JS片段未闭合=错误", jsBadBook.errors().any { it.field == "ruleContent" })
    check(
        "JS错误为ERROR级",
        jsBadBook.errors().first { it.field == "ruleContent" }.level == Level.ERROR
    )

    // ---------------- 订阅源校验 ----------------
    val rssOk = V.validateRss("http://a.com/rss.xml", "订阅", null, "", "", "", null)
    check("纯RSS兜底通过", rssOk.isValid)
    eq("兜底无问题", rssOk.issues.size, 0)

    val rssBadUrl = V.validateRss("", "", null, "articles", "title", "", "ftp://")
    check("订阅源地址为空=错误", rssBadUrl.errors().any { it.field == "sourceUrl" })
    check("订阅搜索地址非法=错误", rssBadUrl.errors().any { it.field == "searchUrl" })
    check("类型越界=错误", V.validateRss("http://a.com", "n", null, null, null, null, null, type = 9)
        .errors().any { it.field == "type" })

    val rssHalfRules = V.validateRss("http://a.com", "n", null, "articles", null, null, null)
    check("缺标题=错误", rssHalfRules.errors().any { it.field == "ruleTitle" })
    check("缺链接=错误", rssHalfRules.errors().any { it.field == "ruleLink" })

    val split = "漫画::http://a.com/m\n电影::http://a.com/d"
    check("分类双行合法", V.validateRss("http://a.com", "n", " " + split + "\n", null, null, null, null)
        .issues.none { it.field == "sortUrl" })
    val badLine = "漫画::http://a.com\n电影\n\n动漫::http://a.com/a"
    val w = V.validateRss("http://a.com", "n", badLine, null, null, null, null).warnings()
    eq("缺::只报一次", w.filter { it.field == "sortUrl" }.size, 1)
    eq("缺::行号以换行计数", w.first { it.field == "sortUrl" }.message, "第 2 行缺少 :: 分隔，应形如 分类名::地址")

    // ---------------- 影视站点校验 ----------------
    check("完整影视站点通过", V.validateVideoSite("http://a.com/cms.json", "sx", "影视", 0, "http://a.com/api", "", "{}").isValid)

    check("站点key空=错误", V.validateVideoSite("http://a.com", "", "n", 1, "u", null, null)
        .errors().any { it.field == "siteKey" })
    check("api空=错误", V.validateVideoSite("http://a.com", "k", "n", 1, "", null, null)
        .errors().any { it.field == "api" })

    check("drpy八点阵合法", V.validateVideoSite("http://a.com", "k", "n", 3, "http://a.com/py/main.js", null, null).isValid)
    eq(
        "jar暂不支持=错误",
        V.validateVideoSite("http://a.com", "k", "n", 3, "http://a.com/py", "demo.jar", null)
            .errors().first().field,
        "jar"
    )
    val type3Warn = V.validateVideoSite("http://a.com", "k", "n", 3, "http://a.com/py", null, null)
    check("type3非drpy无jar=提示", type3Warn.isValid && type3Warn.warnings().any { it.field == "api" })

    check("ext非JSON=提示", V.validateVideoSite("http://a.com", "k", "n", 1, "u", null, "not json")
        .warnings().any { it.field == "ext" })
    check("浅层仅对象不完整报ext提示", V.validateVideoSite("http://a.com", "k", "n", 1, "u", null, "{\"flag\":")
        .warnings().any { it.field == "ext" })

    // ---------------- helper ----------------
    check("isHttpUrl http", V.isHttpUrl("http://a.com"))
    check("isHttpUrl https带查询", V.isHttpUrl("https://a.com/p?q=1"))
    check("isHttpUrl 拒绝ftp", !V.isHttpUrl("ftp://a.com"))
    check("isHttpUrl 拒绝空", !V.isHttpUrl(""))
    check("isHttpUrl 拒绝null", !V.isHttpUrl(null))
    check("isHttpUrl localhost加端口", V.isHttpUrl("http://localhost:8080/api"))
    check("isHttpUrl 带端口识别", V.isHttpUrl("http://a.com:9999/s"))
    check("isHttpUrl 拒绝仅主机名", !V.isHttpUrl("http://a"))
    check("isHttpUrl 拒绝前导点", !V.isHttpUrl("http://.com"))
    check("isHttpUrl 拒绝尾点", !V.isHttpUrl("http://a.com."))
    check("isHttpUrl userInfo放行", V.isHttpUrl("http://user:pass@a.com:80/x"))
    check("looksLikeJson 对象", V.looksLikeJson("{\"a\":[1,2]}"))
    check("looksLikeJson 数组", V.looksLikeJson("[\"a\",{\"b\":1}]"))
    check("looksLikeJson 拒绝单括号", !V.looksLikeJson("["))
    check("looksLikeJson 拒绝字符串", !V.looksLikeJson("\"abc\""))
    check("looksLikeJson 转义引号平衡", V.looksLikeJson("{\"k\":\"v\\\"v\"}"))

    check("balanced 成对", V.balanced("if(a) { return 'x' }"))
    check("balanced 深嵌套引号", V.balanced("(((())))['\"']"))
    check("balanced 未闭合", !V.balanced("if(a) {"))
    check("balanced 空串", V.balanced(""))
    check("balanced 引号内括号", V.balanced("'('"))
    check("balanced 类型错配不配平", !V.balanced("(}"))
    check("balanced 方收圆放", !V.balanced("[}"))
    check("balanced 深嵌套类型对应", V.balanced("f({[1],[2]})"))
    check("balanced 引号含反括号", V.balanced("if ('(x]') { return 1 }"))

    check("@js 段检查闭合", V.checkJsBalanced("@js:1+1@js:'abc'"))
    check("@js 未闭合引号", !V.checkJsBalanced("@js:'unclosed"))
    check("空规则通过", V.checkJsBalanced(""))
    check("无JS片段通过", V.checkJsBalanced("css .a:hover {}"))

    // ---------------- Report 汇总 ----------------
    val errReport = Report(listOf(Issue("x", Level.ERROR, "boom")))
    val warnReport = Report(listOf(Issue("x", Level.WARN, "hmm"), Issue("y", Level.WARN, "hmm2")))
    val cleanReport = Report(emptyList())
    check("error标记", errReport.hasError && !errReport.isValid)
    check("错误过滤", errReport.errors().size == 1 && errReport.warnings().isEmpty())
    check("提示过滤", warnReport.warnings().size == 2 && !warnReport.hasError)
    check("有效标记", cleanReport.isValid && !cleanReport.hasError)
    eq("summary有效", cleanReport.summary(), "配置有效")
    check("summary报错详情", errReport.summary().contains("1 处错误") && errReport.summary().contains("boom"))
    eq("summary提示条数", warnReport.summary(), "可用，2 处提示")

    println("===== 源配置校验与能力判定 =====")
    println("PASS = $pass   FAIL = $fail")
    if (fail > 0) kotlin.system.exitProcess(1)
}
