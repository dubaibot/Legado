package com.lanyue.reader.source.model

/**
 * 1.4.2 / 4.12 源配置校验（纯逻辑，无 Android / 无 Room）。
 *
 * 为什么单独一层：
 * - 源导入是最容易出问题的一步（用户手贴 JSON、订阅更新覆盖本地编辑），
 *   错误必须在「保存前」暴露，而不是等用户点开书才报「解析失败」。
 * - 用纯数据入参而不是实体入参，可以与 Room 解耦并直接单测。
 *
 * 分级原则：
 * - ERROR：结构错误，保存后一定不可用（缺少必要 URL、URL 非法、JS 引号不闭合）
 * - WARN ：可用但有隐患（缺搜索规则、缺目录规则、登录源缺 loginUrl）
 */
object SourceValidator {

    enum class Level { ERROR, WARN }

    data class Issue(val field: String, val level: Level, val message: String)

    data class Report(val issues: List<Issue>) {
        val hasError: Boolean get() = issues.any { it.level == Level.ERROR }
        val isValid: Boolean get() = !hasError
        fun errors(): List<Issue> = issues.filter { it.level == Level.ERROR }
        fun warnings(): List<Issue> = issues.filter { it.level == Level.WARN }
        /** 一句话摘要，供导入结果提示。 */
        fun summary(): String = when {
            issues.isEmpty() -> "配置有效"
            hasError -> "存在 " + errors().size + " 处错误：" + errors().first().message
            else -> "可用，" + warnings().size + " 处提示"
        }
    }

    /**
     * 校验书源。
     *
     * [bookSourceType] 0 文本 / 1 音频 / 2 图片 / 3 文件；音频源不需要 ruleContent
     * （正文直接返回直链），因此对 ruleContent 的检查按类型放宽。
     */
    fun validateBook(
        bookSourceUrl: String?,
        bookSourceName: String?,
        searchUrl: String?,
        ruleSearch: String?,
        exploreUrl: String?,
        enabledExplore: Boolean,
        ruleToc: String?,
        ruleContent: String?,
        loginUrl: String?,
        bookSourceType: Int = 0,
        jsLib: String? = null
    ): Report {
        val issues = ArrayList<Issue>()

        if (bookSourceUrl.isNullOrBlank()) {
            issues.add(Issue("bookSourceUrl", Level.ERROR, "书源地址为空"))
        } else if (!isHttpUrl(bookSourceUrl)) {
            issues.add(Issue("bookSourceUrl", Level.ERROR, "书源地址不是 http/https：" + bookSourceUrl))
        }
        if (bookSourceName.isNullOrBlank()) {
            issues.add(Issue("bookSourceName", Level.WARN, "书源名称为空，列表里会显示为地址"))
        }

        // 搜索：两条同时存在才构成可搜索，只写一半属于配置错误
        val hasSearchUrl = !searchUrl.isNullOrBlank()
        val hasRuleSearch = !ruleSearch.isNullOrBlank()
        if (hasSearchUrl != hasRuleSearch) {
            issues.add(Issue("searchUrl", Level.WARN, "搜索地址与搜索规则需成对配置，当前只写了一项"))
        }

        // 发现：开了发现页却没有地址
        if (enabledExplore && exploreUrl.isNullOrBlank()) {
            issues.add(Issue("exploreUrl", Level.WARN, "已开启发现但未配置发现地址"))
        }
        if (!enabledExplore && !exploreUrl.isNullOrBlank()) {
            issues.add(Issue("exploreUrl", Level.WARN, "配置了发现地址但未开启发现"))
        }

        if (ruleToc.isNullOrBlank()) {
            issues.add(Issue("ruleToc", Level.ERROR, "缺少目录规则，无法列出章节"))
        }
        // 音频源正文是直链，不需要 ruleContent
        if (bookSourceType != 1 && ruleContent.isNullOrBlank()) {
            issues.add(Issue("ruleContent", Level.WARN, "缺少正文规则，将回落到 WebView 打开"))
        }
        if (!loginUrl.isNullOrBlank() && !isHttpUrl(loginUrl)) {
            issues.add(Issue("loginUrl", Level.ERROR, "登录地址非法：" + loginUrl))
        }

        checkJs("ruleSearch", ruleSearch, issues)
        checkJs("ruleToc", ruleToc, issues)
        checkJs("ruleContent", ruleContent, issues)
        checkJs("jsLib", jsLib, issues)

        return Report(issues)
    }

    /** 校验订阅源。订阅源可以完全没有规则（纯 RSS 兜底）。 */
    fun validateRss(
        sourceUrl: String?,
        sourceName: String?,
        sortUrl: String?,
        ruleArticles: String?,
        ruleTitle: String?,
        ruleLink: String?,
        searchUrl: String?,
        type: Int = 0
    ): Report {
        val issues = ArrayList<Issue>()

        if (sourceUrl.isNullOrBlank()) {
            issues.add(Issue("sourceUrl", Level.ERROR, "订阅源地址为空"))
        } else if (!isHttpUrl(sourceUrl)) {
            issues.add(Issue("sourceUrl", Level.ERROR, "订阅源地址不是 http/https：" + sourceUrl))
        }
        if (sourceName.isNullOrBlank()) {
            issues.add(Issue("sourceName", Level.WARN, "订阅源名称为空"))
        }
        if (type !in 0..2) {
            issues.add(Issue("type", Level.ERROR, "内容类型只能是 0/1/2，当前 " + type))
        }
        // 有文章列表规则就必须有标题和链接，否则解析出来是空条目
        if (!ruleArticles.isNullOrBlank()) {
            if (ruleTitle.isNullOrBlank()) {
                issues.add(Issue("ruleTitle", Level.ERROR, "配置了文章列表规则但缺少标题规则"))
            }
            if (ruleLink.isNullOrBlank()) {
                issues.add(Issue("ruleLink", Level.ERROR, "配置了文章列表规则但缺少链接规则"))
            }
        }
        if (!searchUrl.isNullOrBlank() && !isHttpUrl(searchUrl)) {
            issues.add(Issue("searchUrl", Level.ERROR, "搜索地址非法：" + searchUrl))
        }
        if (!sortUrl.isNullOrBlank()) {
            for ((i, line) in sortUrl.split('\n').withIndex()) {
                val t = line.trim()
                if (t.isEmpty()) continue
                if (!t.contains("::")) {
                    issues.add(Issue("sortUrl", Level.WARN, "第 " + (i + 1) + " 行缺少 :: 分隔，应形如 分类名::地址"))
                }
            }
        }

        checkJs("ruleArticles", ruleArticles, issues)
        checkJs("ruleTitle", ruleTitle, issues)
        checkJs("ruleLink", ruleLink, issues)

        return Report(issues)
    }

    /** 校验影视站点。type 3 的 jar 站点不支持，需明确报错而不是静默失效。 */
    fun validateVideoSite(
        configUrl: String?,
        siteKey: String?,
        name: String?,
        type: Int,
        api: String?,
        jar: String?,
        ext: String?
    ): Report {
        val issues = ArrayList<Issue>()

        if (configUrl.isNullOrBlank()) {
            issues.add(Issue("configUrl", Level.ERROR, "配置地址为空"))
        } else if (!isHttpUrl(configUrl)) {
            issues.add(Issue("configUrl", Level.ERROR, "配置地址不是 http/https"))
        }
        if (siteKey.isNullOrBlank()) {
            issues.add(Issue("siteKey", Level.ERROR, "站点 key 为空"))
        }
        if (name.isNullOrBlank()) {
            issues.add(Issue("name", Level.WARN, "站点名称为空"))
        }
        if (api.isNullOrBlank()) {
            issues.add(Issue("api", Level.ERROR, "站点 api 为空"))
        }

        // 7.3：type 3 仅 drpy（api 以 .js 结尾）可用，jar 站点标记暂不支持
        if (type == 3) {
            val isDrpy = api?.trim()?.endsWith(".js") == true
            if (!isDrpy) {
                if (!jar.isNullOrBlank()) {
                    issues.add(Issue("jar", Level.ERROR, "jar 类型解析器暂不支持，该站点将不可用"))
                } else {
                    issues.add(Issue("api", Level.WARN, "type 3 通常为 drpy，api 建议以 .js 结尾"))
                }
            }
        }
        if (type !in 0..3) {
            issues.add(Issue("type", Level.ERROR, "站点类型只能是 0/1/3，当前 " + type))
        }
        if (!ext.isNullOrBlank() && !looksLikeJson(ext)) {
            issues.add(Issue("ext", Level.WARN, "ext 不是合法 JSON，筛选条件可能失效"))
        }

        return Report(issues)
    }

    // ---------------- 基础判定 ----------------

    /** 是否是 http/https 绝对地址。 */
    fun isHttpUrl(value: String?): Boolean {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return false
        if (!v.startsWith("http://") && !v.startsWith("https://")) return false
        val authority = v.removePrefix("http://").removePrefix("https://")
            .substringBefore('/').substringBefore('?').substringBefore('#')
        // 去掉 userInfo@ 与端口号，只保留主机名做判断
        val host = authority.substringAfterLast('@')
            .substringBefore(':')
            .trim()
        if (host.isEmpty()) return false
        if (host == "localhost") return true
        // IPv4 / 域名：必须含点，且不能只有点和数字之外的空白
        return host.contains('.') && !host.startsWith('.') && !host.endsWith('.')
    }

    /**
     * 粗略判断字符串是否是合法 JSON 对象/数组。
     * 只做括号配平与首字符检查，不追求严格解析（严格校验由 JSON 解析器负责）。
     */
    fun looksLikeJson(value: String?): Boolean {
        val v = value?.trim().orEmpty()
        if (v.length < 2) return false
        val first = v.first()
        val last = v.last()
        if (!((first == '{' && last == '}') || (first == '[' && last == ']'))) return false
        var depth = 0
        var inString = false
        var escaped = false
        for (c in v) {
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' || c == '[' -> depth++
                c == '}' || c == ']' -> {
                    depth--
                    if (depth < 0) return false
                }
            }
        }
        return depth == 0
    }

    /**
     * 检查规则里的 JS 片段引号/括号是否闭合。
     *
     * 只检查 `@js:` 段与 `<js>` 块，普通 CSS/JSONPath 规则不参与，
     * 避免把 CSS 里的方括号误判成 JS 语法错误。
     */
    fun checkJsBalanced(rule: String?): Boolean {
        val r = rule?.trim().orEmpty()
        if (r.isEmpty()) return true
        val jsSegments = ArrayList<String>()
        var idx = 0
        while (true) {
            val at = r.indexOf("@js:", idx)
            if (at < 0) break
            val start = at + 4
            // JS 段到下一个顶层 @ 或规则结尾
            var end = r.length
            var depth = 0
            var inS = false
            var inD = false
            var i = start
            while (i < r.length) {
                val c = r[i]
                when {
                    inS -> if (c == '\'' && r.getOrNull(i - 1) != '\\') inS = false
                    inD -> if (c == '"' && r.getOrNull(i - 1) != '\\') inD = false
                    c == '\'' -> inS = true
                    c == '"' -> inD = true
                    c == '{' || c == '(' || c == '[' -> depth++
                    c == '}' || c == ')' || c == ']' -> depth--
                    c == '@' && depth <= 0 -> {
                        end = i
                        i = r.length
                        continue
                    }
                }
                i++
            }
            jsSegments.add(r.substring(start, end))
            idx = end
        }
        for (seg in jsSegments) {
            if (!balanced(seg)) return false
        }
        return true
    }

    /** 判断括号与引号是否配平。 */
    fun balanced(s: String): Boolean {
        var depth = 0
        var inS = false
        var inD = false
        var escaped = false
        for (c in s) {
            when {
                escaped -> escaped = false
                c == '\\' && (inS || inD) -> escaped = true
                inS -> if (c == '\'') inS = false
                inD -> if (c == '"') inD = false
                c == '\'' -> inS = true
                c == '"' -> inD = true
                c == '(' || c == '{' || c == '[' -> depth++
                c == ')' || c == '}' || c == ']' -> {
                    depth--
                    if (depth < 0) return false
                }
            }
        }
        return depth == 0 && !inS && !inD
    }

    private fun checkJs(field: String, rule: String?, issues: MutableList<Issue>) {
        if (rule.isNullOrBlank()) return
        if (!checkJsBalanced(rule)) {
            issues.add(Issue(field, Level.ERROR, field + " 中的 JS 片段引号或括号未闭合"))
        }
    }
}
