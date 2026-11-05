import com.lanyue.reader.source.rule.JsonPath
import com.lanyue.reader.source.rule.XPathLite

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

private const val DOC = """
{
  "code": 0,
  "data": {
    "total": 3,
    "list": [
      {"name":"三体","type":"book","words":880000,"author":"刘慈欣"},
      {"name":"活着","type":"book","words":120000,"author":"余华"},
      {"name":"某漫画","type":"comic","words":0,"author":null}
    ]
  },
  "meta": {"nested": {"name":"深层"}}
}
"""

fun main() {
    // ---------------- 基础字段 ----------------
    eq("根字段", JsonPath.firstString(DOC, "$.code"), "0")
    eq("两级字段", JsonPath.firstString(DOC, "$.data.total"), "3")
    eq("字符串字段", JsonPath.firstString(DOC, "$.data.list[0].name"), "三体")
    eq("负索引", JsonPath.firstString(DOC, "$.data.list[-1].name"), "某漫画")
    eq("越界索引", JsonPath.selectText(DOC, "$.data.list[99].name").size, 0)

    // ---------------- 通配 ----------------
    eq("数组通配", JsonPath.toStrings(JsonPath.selectText(DOC, "$.data.list[*].name")),
        listOf("三体", "活着", "某漫画"))
    eq("对象通配", JsonPath.selectText(DOC, "$.data.*").size, 2)
    check("通配后取字段", JsonPath.toStrings(JsonPath.selectText(DOC, "$.data.list[*].author")).contains("刘慈欣"))

    // ---------------- 递归下降 ----------------
    val recursive = JsonPath.toStrings(JsonPath.selectText(DOC, "$..name"))
    check("递归下降取到 4 个 name", recursive.size == 4, "got=$recursive")
    check("递归含深层值", recursive.contains("深层"))

    // ---------------- 切片 ----------------
    eq("切片 0:2", JsonPath.toStrings(JsonPath.selectText(DOC, "$.data.list[0:2].name")),
        listOf("三体", "活着"))
    eq("切片省略尾", JsonPath.toStrings(JsonPath.selectText(DOC, "$.data.list[1:].name")),
        listOf("活着", "某漫画"))
    eq("切片负端点", JsonPath.toStrings(JsonPath.selectText(DOC, "$.data.list[-2:].name")),
        listOf("活着", "某漫画"))
    eq("切片空区间", JsonPath.selectText(DOC, "$.data.list[2:1].name").size, 0)

    // ---------------- 方括号字符串键 ----------------
    val weird = """{"a b":{"c-d":1}}"""
    eq("方括号字符串键", JsonPath.firstString(weird, "${'$'}['a b']['c-d']"), "1")
    eq("双引号方括号键", JsonPath.firstString(weird, "${'$'}[\"a b\"][\"c-d\"]"), "1")

    // ---------------- 过滤：相等 ----------------
    eq("按字符串相等过滤", JsonPath.toStrings(JsonPath.selectText(DOC, "$.data.list[?(@.type=='book')].name")),
        listOf("三体", "活着"))
    eq("按数字相等过滤", JsonPath.toStrings(JsonPath.selectText(DOC, "$.data.list[?(@.words==880000)].name")),
        listOf("三体"))

    // ---------------- 过滤：不等 ----------------
    eq("按不等过滤", JsonPath.toStrings(JsonPath.selectText(DOC, "$.data.list[?(@.type!='book')].name")),
        listOf("某漫画"))

    // ---------------- 过滤：比较 ----------------
    eq("大于", JsonPath.toStrings(JsonPath.selectText(DOC, "$.data.list[?(@.words>500000)].name")),
        listOf("三体"))
    eq("大于等于", JsonPath.toStrings(JsonPath.selectText(DOC, "$.data.list[?(@.words>=120000)].name")),
        listOf("三体", "活着"))
    eq("小于", JsonPath.toStrings(JsonPath.selectText(DOC, "$.data.list[?(@.words<1000)].name")),
        listOf("某漫画"))
    eq("小于等于", JsonPath.toStrings(JsonPath.selectText(DOC, "$.data.list[?(@.words<=120000)].name")),
        listOf("活着", "某漫画"))

    // ---------------- 过滤：存在性 ----------------
    eq("存在性过滤", JsonPath.toStrings(JsonPath.selectText(DOC, "$.data.list[?(@.author)].name")),
        listOf("三体", "活着"))
    // null 值不算存在
    check("null 不算存在", !JsonPath.toStrings(JsonPath.selectText(DOC, "$.data.list[?(@.author)].name")).contains("某漫画"))

    // ---------------- 非法输入 ----------------
    eq("非法 JSON", JsonPath.selectText("{oops", "$.a").size, 0)
    eq("空路径", JsonPath.selectText(DOC, "").size, 0)
    eq("非法路径", JsonPath.selectText(DOC, "$.a[?]").size, 0)
    eq("未闭合方括号", JsonPath.selectText(DOC, "$.a[0").size, 0)
    eq("含空白的名字判非法", JsonPath.selectText(DOC, "$.a b").size, 0)
    eq("空 JSON 对象取字段", JsonPath.selectText("{}", "$.a").size, 0)
    eq("数组取字段判空", JsonPath.selectText("[1,2]", "$.a").size, 0)
    eq("对象取索引判空", JsonPath.selectText(DOC, "$.code[0]").size, 0)

    // ---------------- 结果形态 ----------------
    val objs = JsonPath.selectText(DOC, "$.data.list[*]")
    eq("对象结果保留结构", objs.size, 3)
    check("对象转字符串是 JSON", JsonPath.toStrings(objs).first().startsWith("{"))
    eq("null 被过滤", JsonPath.selectText(DOC, "$.data.list[2].author").size, 0)

    // ---------------- looksLikeJsonPath ----------------
    eq("识别 $.", JsonPath.looksLikeJsonPath("$.a"), true)
    eq("识别 $[", JsonPath.looksLikeJsonPath("$[0]"), true)
    eq("CSS 不算", JsonPath.looksLikeJsonPath("class.a@text"), false)
    eq("XPath 不算", JsonPath.looksLikeJsonPath("//div/a"), false)
    eq("单 $ 算", JsonPath.looksLikeJsonPath("$"), true)

    // ================= XPathLite =================
    eq("识别 XPath //", XPathLite.looksLikeXPath("//div/a"), true)
    eq("识别 XPath /", XPathLite.looksLikeXPath("/html/body"), true)
    eq("协议相对 URL 不算", XPathLite.looksLikeXPath("//example.com/a"), false)
    eq("CSS 不算 XPath", XPathLite.looksLikeXPath("class.a"), false)

    // 基本转换
    eq("后代 + 取文本", XPathLite.toCss("//div[@class='x']/a/text()"),
        XPathLite.Converted("div[class=\"x\"] > a", "text"))
    eq("class 谓词(全等)", XPathLite.toCss("//div[@class='x']")?.css, "div[class=\"x\"]")
    eq("id 谓词(全等)", XPathLite.toCss("//div[@id='y']//span")?.css, "div[id=\"y\"] span")
    eq("位置谓词", XPathLite.toCss("//ul/li[2]")?.css, "ul > li:nth-child(2)")
    eq("last()", XPathLite.toCss("//p[last()]")?.css, "p:last-child")
    eq("属性取值", XPathLite.toCss("//a[@href]/@href")?.directive, "attr:href")
    eq("属性取值的 css", XPathLite.toCss("//a[@href]/@href")?.css, "a[href]")
    eq("contains", XPathLite.toCss("//div[contains(@class,'box')]")?.css, "div[class*=\"box\"]")
    eq("绝对路径", XPathLite.toCss("/html/body/div[1]")?.css, "html > body > div:nth-child(1)")
    eq("通配段", XPathLite.toCss("//div/*/a")?.css, "div > * > a")

    // 不等谓词 -> :not()
    eq("不等谓词", XPathLite.toCss("//div[@class!='x']")?.css, "div:not([class=\"x\"])")

    // 无取值指令
    eq("无指令", XPathLite.toCss("//div/a")?.directive, null)

    // 明确不支持的
    eq("轴不支持", XPathLite.toCss("//div/ancestor::span"), null)
    eq("and 不支持", XPathLite.toCss("//div[@a and @b]"), null)
    eq("or 不支持", XPathLite.toCss("//div[@a or @b]"), null)
    eq("非 / 开头", XPathLite.toCss("div/a"), null)
    eq("未知函数", XPathLite.toCss("//div[normalize-space(@class)]"), null)
    eq("位置 0 非法", XPathLite.toCss("//li[0]"), null)

    // 引号转义
    val q = XPathLite.toCss("//div[@class='a\"b']")
    check("引号被转义", q != null && q.css.contains("\\\""), "css=${q?.css}")

    println("================ JSONPath / XPathLite 单测 ================")
    println("PASS = $pass   FAIL = $fail")
    if (failures.isNotEmpty()) {
        println("---- failures ----")
        failures.forEach { println("  " + it) }
    }
    if (fail > 0) kotlin.system.exitProcess(1)
}
