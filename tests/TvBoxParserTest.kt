import com.google.gson.JsonParser
import com.lanyue.reader.source.video.TvBoxParser
import com.lanyue.reader.source.video.TvBoxParser.ParsedConfig
import com.lanyue.reader.source.video.TvBoxParser.Result
import com.lanyue.reader.source.video.TvBoxParser.Result.Invalid
import com.lanyue.reader.source.video.TvBoxParser.Result.Ok

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

private fun mainSection(label: String) = println("===== $label =====")

fun main() {
    mainSection("TVBox 配置解析")
    val p = TvBoxParser()

    val fullJson = """
        {
          "name": "我的视界",
          "spider": "pkg.jar",
          "sites": [
            {"key": "csp_AppYsV2", "name": "YsV2", "api": "http://a.com/api.js", "type": 3},
            {"key": "csp_X", "api": "https://b.com/x.js", "type": 3, "timeout": 5000},
            {"key": "xml1", "api": "http://c.com", "type": 0},
            {"key": "json1", "api": "http://d.com/api.php", "type": 1, "ext": "{\"key\":\"v\"}"},
            {"key": "jar1", "api": "https://e.com/j.jar", "type": 3},
            {"key": "jar2", "jar": "local.jar", "type": 3},
            {"name": "无key站点"},
            "不是对象",
            null
          ],
          "parses": [
            {"name": "在线99", "type": 0, "url": "http://p.com/jx"},
            {"url": "http://p2.com/jx"},
            {"name": "缺type", "url": "http://p3.com/jx"},
            {"name": "无url"},
            "坏元素"
          ],
          "flags": ["a", "b"],
          "lives": [
            {"name": "央视", "type": "txt", "url": "http://live1/list.txt", "epg": "http://epg", "logo": "http://logo"},
            {"url": "http://live2/list.txt"},
            {"name": "无url直播"},
            "坏元素2"
          ],
          "hotSearch": {"key": "data-csp-ckey"},
          "homepage": ["http://h.com/v2?filter=hot&amp;pg=1&amp;fcat=new"]
        }
    """.trimIndent()

    val r = p.parse("http://tb.lanyue.test/cfg.json", fullJson)
    val ok = (r as? Ok)?.config
    check("合法配置解析成功", ok != null, "got $r")
    val c = ok ?: return@main

    eq("api URL 记录配置地址", c.api.url, "http://tb.lanyue.test/cfg.json")
    eq("api name", c.api.name, "我的视界")
    eq("api spider", c.api.spider, "pkg.jar")
    check("api lastUpdate 为正", c.api.lastUpdate > 0)
    check("api 原文留存", c.api.rawJson.contains("hotSearch"))

    eq("站点数含全部有效项", c.sites.size, 6)
    eq("非对象站点被跳过", c.sites.any { it.siteKey == "无key站点" }, false)
    eq("null 站点被跳过", c.sites.count { it.siteKey == "null" }, 0)

    val drpy = c.sites.first { it.siteKey == "csp_AppYsV2" }
    eq("drpy(type3+.js) enabled", drpy.enabled, true)
    eq("drpy 类型", drpy.type, 3)
    eq("drpy api", drpy.api, "http://a.com/api.js")
    eq("drpy 缺name回落key", drpy.name, "YsV2")

    val jarSite = c.sites.first { it.siteKey == "jar1" }
    eq("jar(type3+非.js) 标记不支持", jarSite.unsupported, true)
    eq("jar 站点禁用", jarSite.enabled, false)
    eq("jar2 同样标记", c.sites.first { it.siteKey == "jar2" }.unsupported, true)
    eq("jar 裁剪计数", c.dropsJarSites, 2)

    val defaultSite = c.sites.first { it.siteKey == "xml1" }
    eq("xml 站点默认可搜", defaultSite.searchable, true)
    eq("xml 站点默认快搜", defaultSite.quickSearch, true)
    eq("xml 站点默认不可筛", defaultSite.filterable, false)
    eq("xml 站点类型", defaultSite.type, 0)
    eq("xml 超时默认0", defaultSite.timeout, 0L)

    val extSite = c.sites.first { it.siteKey == "json1" }
    check("ext 保留序列化形态（带引号）",
        extSite.ext != null && extSite.ext.startsWith("\"") && extSite.ext.endsWith("\""), "got ${extSite.ext}")
    val extParsed = JsonParser.parseString(extSite.ext)
    check("ext 首个回环为字符串", extParsed.isJsonPrimitive, "got ${extSite.ext}")
    val extObj = JsonParser.parseString(extParsed.getAsString()).asJsonObject
    eq("ext 二次回环取回键值", extObj.get("key")?.asString, "v")

    val timeoutSite = c.sites.first { it.siteKey == "csp_X" }
    eq("自定义超时保留", timeoutSite.timeout, 5000L)
    eq("csp_X api", timeoutSite.api, "https://b.com/x.js")

    eq("parses 链数量（url 必须）", c.parses.size, 3)
    eq("parses name 缺省空串", c.parses[1].name, "")
    eq("parses type 缺省0", c.parses[1].type, 0)
    eq("parses 显式类型", c.parses[0].type, 0)
    eq("parses url 顺序", c.parses.map { it.url }, listOf("http://p.com/jx", "http://p2.com/jx", "http://p3.com/jx"))
    val p3 = c.parses[2]
    eq("parses name 保留", p3.name, "缺type")
    check("parses 无 ext", p3.ext == null)

    eq("flags 全量", c.flags, listOf("a", "b"))

    eq("lives 数量（url 必须）", c.lives.size, 2)
    val live1 = c.lives[0]
    eq("live id 组成", live1.id, "http://tb.lanyue.test/cfg.json#live#0")
    eq("live apiUrl", live1.apiUrl, "http://tb.lanyue.test/cfg.json")
    eq("live name", live1.name, "央视")
    eq("live type", live1.type, "txt")
    eq("live url", live1.url, "http://live1/list.txt")
    eq("live epg", live1.epg, "http://epg")
    eq("live logo", live1.logo, "http://logo")

    val live2 = c.lives[1]
    check("live 缺字段用默认", live2.name == "" && live2.type == "txt")
    eq("live id 索引含位置", live2.id, "http://tb.lanyue.test/cfg.json#live#1")

    check("hotSearch 未落库到 api.rawJson 之外（rawJson 仅留存）",
        c.api.rawJson.contains("hotSearch") && c.sites.none { it.ext != null && it.ext.contains("hotSearch") })

    // ---------------- 边界与非法输入 ----------------
    val invalid = p.parse("http://tb.lanyue.test/cfg.json", "{ 这不是 JSON")
    check("非法 JSON Invalid", invalid is Invalid, "got $invalid")
    eq("非法 JSON 原因", (invalid as? Invalid)?.reason, "config is not a JSON object")

    val noSites = p.parse("http://tb.lanyue.test/cfg.json", "{\"name\":\"x\",\"parses\":[]}")
    check("缺 sites Invalid", noSites is Invalid, "got $noSites")
    eq("缺 sites 原因", (noSites as? Invalid)?.reason, "missing sites")

    val emptySites = p.parse("u", "{\"sites\":[]}")
    check("空 sites 仍 Ok", (emptySites as? Ok) != null, "got $emptySites")

    val sitesNull = p.parse("u", "{\"sites\":null}")
    check("sites:null Invalid", sitesNull is Invalid, "got $sitesNull")

    val apiEmpty = p.parse("http://tb.lanyue.test/x.json",
        "{\"spider\":null,\"name\":null,\"sites\":[{\"key\":\"k\",\"api\":\"http://a.com\"}]}")
    val ae = (apiEmpty as? Ok)
    check("null 字段不炸解析", ae != null)
    eq("null name/spider 按 null 存", ae?.config?.api?.name, null)
    check("lastUpdate 始终轰鸣", (ae?.config?.api?.lastUpdate ?: 0) > 0)

    // 站点字段类型异常的鲁棒性：类型错误字段走 runCatching 回退默认
    val weird = p.parse("u",
        "{\"sites\":[{\"key\":\"k\",\"type\":\"notint\",\"timeout\":\"x\",\"searchable\":\"y\",\"filterable\":123}]}")
    val we = (weird as? Ok)?.config?.sites?.firstOrNull()
    check("类型错误字段回退默认值", we != null && we.type == 1 && we.timeout == 0L, "got $weird")

    // 单站点配置展开、去重在仓库层
    val single = p.parse("u", "{\"sites\":[{\"key\":\"k\"}]}")
    val sc = (single as? Ok)?.config
    check("最小站点配置展开1条", sc?.sites?.size == 1, "got $single")
    eq("最小站点名回落 key", sc?.sites?.first()?.name, "k")
    eq("最小站点类型默认1(JSON)", sc?.sites?.first()?.type, 1)

    // 可选字段为 JSON null 时的健壮性（曾触发 ClassCastException——真实缺陷修复）
    val nullFields = p.parse("u",
        "{\"sites\":[{\"key\":\"k\"}],\"parses\":null,\"flags\":null,\"lives\":null}")
    val nf = (nullFields as? Ok)?.config
    check("可选字段 null 不炸解析", nf != null, "got $nullFields")
    eq("parses null 回退空链", nf?.parses, emptyList<com.lanyue.reader.source.video.TvBoxParser.ParseRule>())
    eq("flags null 回退空", nf?.flags, emptyList<String>())
    eq("lives null 回退空", nf?.lives, emptyList<com.lanyue.reader.data.db.entity.LiveSourceEntity>())

    println()
    println("PASS = $pass   FAIL = $fail")
    if (fail > 0) kotlin.system.exitProcess(1)
}
