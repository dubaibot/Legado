import com.lanyue.reader.core.engine.JsExecMode
import com.lanyue.reader.core.security.BridgeWhitelist
import com.lanyue.reader.core.security.FetchVerdict
import com.lanyue.reader.core.security.HtmlRequestClassifier
import com.lanyue.reader.core.security.NativeFetchGuard
import com.lanyue.reader.core.security.PermissionGrant
import com.lanyue.reader.core.security.SourcePermissionLevel
import com.lanyue.reader.core.security.SubmitGuard
import kotlin.system.exitProcess

private var passed = 0
private var failed = 0

fun sgPass(label: String) {
    passed++
    println("[PASS] " + label)
}

fun sgFail(label: String, detail: String) {
    failed++
    println("[FAIL] " + label + " (" + detail + ")")
}

fun sgEq(actual: Any?, expected: Any?, label: String) {
    if (actual == expected) sgPass(label)
    else sgFail(label, "expected=" + expected + " actual=" + actual)
}

fun sgCheck(label: String, cond: Boolean) {
    if (cond) sgPass(label)
    else sgFail(label, "条件不满足")
}

private fun grantFields(
    level: SourcePermissionLevel,
    mode: JsExecMode,
    allowJs: Boolean,
    allowDom: Boolean,
    allowGet: Boolean,
    allowPost: Boolean,
    confirm: Boolean
) {
    val g = PermissionGrant.of(level)
    sgEq(g.level, level, level.name + " level 保留")
    sgEq(g.mode, mode, level.name + " mode")
    sgEq(g.allowJs, allowJs, level.name + " allowJs")
    sgEq(g.allowDom, allowDom, level.name + " allowDom")
    sgEq(g.allowGet, allowGet, level.name + " allowGet")
    sgEq(g.allowPost, allowPost, level.name + " allowPost")
    sgEq(g.requireHighRiskConfirm, confirm, level.name + " requireHighRiskConfirm")
}

fun main() {
    println("================ 安全闸门与权限分级测试 ================")

    // ── BridgeWhitelist 白名单 ──
    sgEq(BridgeWhitelist.ALLOWED.size, 3, "桥白名单恰好三项")
    sgCheck("NATIVE_LOG 在白名单", BridgeWhitelist.NATIVE_LOG in BridgeWhitelist.ALLOWED)
    sgCheck("NATIVE_FETCH 在白名单", BridgeWhitelist.NATIVE_FETCH in BridgeWhitelist.ALLOWED)
    sgCheck("NATIVE_POST_MESSAGE 在白名单", BridgeWhitelist.NATIVE_POST_MESSAGE in BridgeWhitelist.ALLOWED)
    sgCheck("__dispatchResult 不在白名单（防伪造）", BridgeWhitelist.DISPATCH_RESULT !in BridgeWhitelist.ALLOWED)

    // ── NativeFetchGuard scheme / host ──
    val allowed = { host: String -> host == "93.184.216.34" }
    val guard = NativeFetchGuard(allowed, maxResponseBytes = 8L * 1024 * 1024)
    sgEq(guard.maxBytes(), 8L * 1024 * 1024, "默认响应上限 8MB")

    sgCheck("malformed url 拒绝", guard.verify("http://[foo", "GET", false, false) is FetchVerdict.Deny)
    sgCheck("缺 scheme 拒绝", guard.verify("www.example.com/path", "GET", false, false) is FetchVerdict.Deny)
    sgCheck("file scheme 拒绝", guard.verify("file:///etc/hosts", "GET", false, false) is FetchVerdict.Deny)
    sgCheck("缺 host 拒绝", guard.verify("http://", "GET", false, false) is FetchVerdict.Deny)
    sgCheck("loopback 127.0.0.1 拒绝", guard.verify("http://127.0.0.1/", "GET", false, false) is FetchVerdict.Deny)
    sgCheck("loopback ::1 拒绝", guard.verify("http://[::1]/", "GET", false, false) is FetchVerdict.Deny)
    sgCheck("localhost 拒绝", guard.verify("http://localhost/", "GET", false, false) is FetchVerdict.Deny)
    sgCheck("site-local 10.0.0.1 拒绝", guard.verify("http://10.0.0.1/", "GET", false, false) is FetchVerdict.Deny)
    sgCheck("link-local 169.254.1.1 拒绝", guard.verify("http://169.254.1.1/", "GET", false, false) is FetchVerdict.Deny)
    sgCheck("非白名单域名拒绝", guard.verify("http://1.1.1.1/", "GET", false, false) is FetchVerdict.Deny)

    // ── method 裁决 ──
    val vGet = guard.verify("http://93.184.216.34/api", "GET", false, false)
    sgCheck("白名单 GET 放行", vGet is FetchVerdict.Allow)
    if (vGet is FetchVerdict.Allow) {
        sgEq(vGet.method, "GET", "GET 方法名保留")
        sgEq(vGet.normalizedUrl, "http://93.184.216.34/api", "Allow 返回原始 url")
    }
    sgCheck("HEAD 放行", guard.verify("http://93.184.216.34", "HEAD", false, false) is FetchVerdict.Allow)
    sgCheck("小写 get 归一化放行", guard.verify("http://93.184.216.34", "get", false, false) is FetchVerdict.Allow)
    sgCheck("POST 未声明被拒", guard.verify("http://93.184.216.34", "POST", false, false) is FetchVerdict.Deny)
    sgCheck("POST 规则声明放行", guard.verify("http://93.184.216.34", "POST", true, false) is FetchVerdict.Allow)
    sgCheck("POST postAllowed 放行", guard.verify("http://93.184.216.34", "POST", false, true) is FetchVerdict.Allow)
    sgCheck("PUT 未声明被拒", guard.verify("http://93.184.216.34", "PUT", false, false) is FetchVerdict.Deny)
    sgCheck("PUT 声明放行", guard.verify("http://93.184.216.34", "PUT", true, false) is FetchVerdict.Allow)

    // ── isInternalHost ──
    sgCheck("127.0.0.1 内网", guard.isInternalHost("127.0.0.1"))
    sgCheck("::1 内网", guard.isInternalHost("::1"))
    sgCheck("10.1.2.3 内网", guard.isInternalHost("10.1.2.3"))
    sgCheck("169.254.1.2 内网", guard.isInternalHost("169.254.1.2"))
    sgCheck("0.0.0.0 内网", guard.isInternalHost("0.0.0.0"))
    sgCheck("8.8.8.8 公网", !guard.isInternalHost("8.8.8.8"))
    sgCheck("93.184.216.34 公网", !guard.isInternalHost("93.184.216.34"))

    // ── verifyHop 逐跳 ──
    val hopSame = guard.verifyHop("http://93.184.216.34/a", "/b")
    sgCheck("同域相对重定向放行", hopSame != null)
    sgEq(hopSame, "http://93.184.216.34/b", "重定向解析")
    sgCheck("跳内网地址拒绝", guard.verifyHop("http://93.184.216.34/a", "http://127.0.0.1/c") == null)
    sgCheck("跳非白名单域名拒绝", guard.verifyHop("http://93.184.216.34/a", "http://8.8.8.8/c") == null)
    sgCheck("跳非 http scheme 拒绝", guard.verifyHop("http://93.184.216.34/a", "ftp://93.184.216.34/c") == null)

    // ── SubmitGuard 限流 ──
    val sg = SubmitGuard()
    val t0 = 1_000L
    (1..5).forEach { i -> sgCheck("子请求 #$i 每秒内放行", sg.allowSubmit("src1", "93.184.216.34", "box1", t0)) }
    sgCheck("第 6 个被域名配额拒绝", !sg.allowSubmit("src1", "93.184.216.34", "box1", t0))
    sgCheck("另一 source 仍受域名配额拦下", !sg.allowSubmit("src2", "93.184.216.34", "box1", t0))
    sgCheck("每秒后配额刷新", sg.allowSubmit("src1", "93.184.216.34", "box1", t0 + 1_000))
    sgCheck("不同域名同秒内放行", sg.allowSubmit("src1", "1.1.1.1", "box1", t0))
    sgCheck("不同沙箱同秒内放行", sg.allowSubmit("src1", "1.1.1.1", "box2", t0))
    val id1 = sg.newTaskId()
    val id2 = sg.newTaskId()
    sgCheck("taskId 唯一", id1 != id2)
    sgCheck("taskId 符合 UUID 格式", id1.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))

    // ── HtmlRequestClassifier ──
    val htmlApple = mapOf("Accept" to "text/html,application/xhtml+xml")
    sgCheck("Accept text/html 判为 HTML", HtmlRequestClassifier.isHtmlLike("http://a/b", htmlApple))
    sgCheck("Accept xhtml 判为 HTML", HtmlRequestClassifier.isHtmlLike("http://a/b", mapOf("Accept" to "application/xhtml+xml")))
    sgCheck("无 Accept html 后缀判为 HTML", HtmlRequestClassifier.isHtmlLike("http://site.com/index.html", emptyMap()))
    sgCheck("php 后缀判为 HTML（忽略查询串）", HtmlRequestClassifier.isHtmlLike("http://site.com/a.php?v=1#top", emptyMap()))
    sgCheck("JSON Accept + 路径判为非 HTML", !HtmlRequestClassifier.isHtmlLike("http://api.site.com/v1/data", mapOf("Accept" to "application/json")))
    sgCheck("无点路径可疑判为 HTML", HtmlRequestClassifier.isHtmlLike("http://api/data", mapOf("Accept" to "text/plain")))
    sgCheck("非 HTML 后缀不判 HTML", !HtmlRequestClassifier.isHtmlLike("http://a/api.v1", mapOf("Accept" to "text/plain")))

    // ── isEmptyHtmlBody ──
    sgCheck("null body 判空", HtmlRequestClassifier.isEmptyHtmlBody(null))
    sgCheck("纯空白 body 判空", HtmlRequestClassifier.isEmptyHtmlBody("   "))
    sgCheck("短文本无标签判空", HtmlRequestClassifier.isEmptyHtmlBody("hello"))
    sgCheck("含标签不判空", !HtmlRequestClassifier.isEmptyHtmlBody("<div>"))
    sgCheck("200 空 body 判空", HtmlRequestClassifier.isEmptyHtmlBody(" ".repeat(200)))
    sgCheck("刚好 1024 字节不判空", !HtmlRequestClassifier.isEmptyHtmlBody("x".repeat(1024)))
    sgCheck("2048 字节不判空", !HtmlRequestClassifier.isEmptyHtmlBody("x".repeat(2048)))

    // ── 权限分级表 ──
    grantFields(
        SourcePermissionLevel.BOOK, JsExecMode.MAIN,
        allowJs = true, allowDom = true, allowGet = true, allowPost = true, confirm = false
    )
    grantFields(
        SourcePermissionLevel.SUBSCRIPTION, JsExecMode.MAIN,
        allowJs = true, allowDom = true, allowGet = true, allowPost = true, confirm = false
    )
    grantFields(
        SourcePermissionLevel.VIDEO_CMS, JsExecMode.MAIN,
        allowJs = false, allowDom = false, allowGet = true, allowPost = false, confirm = false
    )
    grantFields(
        SourcePermissionLevel.VIDEO_DRPY, JsExecMode.SANDBOX_REUSE,
        allowJs = true, allowDom = true, allowGet = true, allowPost = true, confirm = false
    )
    grantFields(
        SourcePermissionLevel.VIDEO_SPIDER, JsExecMode.SANDBOX_ISOLATED,
        allowJs = true, allowDom = false, allowGet = true, allowPost = false, confirm = true
    )
    grantFields(
        SourcePermissionLevel.DANMAKU, JsExecMode.MAIN,
        allowJs = false, allowDom = false, allowGet = true, allowPost = true, confirm = false
    )
    grantFields(
        SourcePermissionLevel.UNKNOWN, JsExecMode.SANDBOX_ISOLATED,
        allowJs = false, allowDom = false, allowGet = true, allowPost = false, confirm = true
    )
    sgEq(SourcePermissionLevel.entries.size, 7, "权限等级共 7 种")

    println("================ 安全闸门与权限分级测试结束 ================")
    println("Pass: " + passed + ", Fail: " + failed)
    if (failed > 0) exitProcess(1)
    exitProcess(0)
}
