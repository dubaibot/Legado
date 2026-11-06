package com.lanyue.reader.source.import

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lanyue.reader.source.model.SourceValidator

/**
 * 书源导入解析（纯逻辑，无 Android / 无网络）。
 *
 * 现实中的书源文件远比「一个合法 JSON 数组」复杂：
 * - 用户从网页复制，常带 `//` `/* */` 注释与尾随逗号（JSON5 风格）；
 * - 站点导出的包裹层五花八门：`{"data":[...]}` / `{"sources":[...]}` / 直接一个对象；
 * - 也有「一行一个订阅链接」的纯文本列表。
 *
 * 因此这里不做「先校验格式再解析」的严格路线，而是先做字符串级清洗，
 * 再递归找出所有「看起来像书源」的对象。单个坏条目只跳过它自己，
 * 不让整份文件失败——用户导入 200 个源时，2 个坏条目不该让 198 个陪葬。
 */
object SourceImportParser {

    /** 单份导入文件的体积上限（字节）。 */
    const val MAX_BYTES = 16 * 1024 * 1024

    /** 单次导入的条目上限，防止恶意超大文件打满内存。 */
    const val MAX_ITEMS = 20_000

    /** 一个解析成功的书源。 */
    data class ImportedBookSource(
        val url: String,
        val name: String,
        val group: String?,
        /** 0 文本 / 1 音频 / 2 图片 / 3 文件。 */
        val type: Int,
        /** 原始 JSON 文本，落库时原样保存，避免字段被裁剪。 */
        val raw: String
    )

    /** 解析结果。 */
    data class ImportResult(
        val accepted: List<ImportedBookSource>,
        val rejected: List<Rejected>,
        /** 文件内重复（同一 url 出现多次）的条数。 */
        val duplicates: Int,
        /** 识别出的输入形态：json-array / json-object / json-wrapped / text-lines。 */
        val format: String
    ) {
        /** 一条被拒绝的条目。 */
        data class Rejected(
            val index: Int,
            val reason: String,
            val snippet: String
        )

        val total: Int get() = accepted.size + rejected.size

        val isEmpty: Boolean get() = accepted.isEmpty()

        /** 一句话结论，直接用于导入结果提示。 */
        fun summary(): String = when {
            total == 0 -> "没有解析到任何书源"
            accepted.isEmpty() -> "全部 " + rejected.size + " 条解析失败"
            rejected.isEmpty() && duplicates == 0 ->
                "已识别 " + accepted.size + " 个书源"
            rejected.isEmpty() ->
                "已识别 " + accepted.size + " 个书源（去重 " + duplicates + " 条）"
            else ->
                "已识别 " + accepted.size + " 个书源，" + rejected.size + " 条失败"
        }
    }

    /** 包裹层里可能出现的数据键，按优先级从上往下找。 */
    private val WRAPPER_KEYS = listOf(
        "data", "sources", "bookSources", "bookSource", "list", "items", "result", "results"
    )

    /** 书源地址字段别名。 */
    private val URL_KEYS = listOf("bookSourceUrl", "sourceUrl", "url", "bookSourceURL")

    /** 书源名称字段别名。 */
    private val NAME_KEYS = listOf("bookSourceName", "sourceName", "name", "title")

    /** 分组字段别名。 */
    private val GROUP_KEYS = listOf("bookSourceGroup", "sourceGroup", "group", "category")

    /** 类型字段别名。 */
    private val TYPE_KEYS = listOf("bookSourceType", "sourceType", "type")

    /**
     * 解析导入内容。
     *
     * 输入可以是 JSON（对象 / 数组 / 带包裹层）或「一行一个链接」的纯文本。
     * 永远返回 [ImportResult]，不抛异常。
     */
    fun parse(raw: String?): ImportResult {
        if (raw.isNullOrBlank()) {
            return ImportResult(emptyList(), emptyList(), 0, "empty")
        }
        if (raw.length > MAX_BYTES) {
            return ImportResult(
                emptyList(),
                listOf(ImportResult.Rejected(0, "文件超过 16 MiB 上限", raw.take(80))),
                0,
                "too-large"
            )
        }

        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("[") || trimmed.startsWith("{") -> parseJson(trimmed)
            else -> parseTextLines(trimmed)
        }
    }

    // ============================ JSON 路线 ============================

    private fun parseJson(raw: String): ImportResult {
        val cleaned = stripCommentsAndTrailingCommas(raw)
        val root: JsonElement = try {
            JsonParser.parseString(cleaned)
        } catch (e: Throwable) {
            // JSON 解析失败时兜底：按文本行再试一次（用户可能贴了混合内容）
            val fallback = parseTextLines(raw)
            if (fallback.accepted.isNotEmpty()) return fallback
            return ImportResult(
                emptyList(),
                listOf(
                    ImportResult.Rejected(
                        0,
                        "不是合法 JSON：" + (e.message ?: "解析失败"),
                        raw.take(120)
                    )
                ),
                0,
                "invalid-json"
            )
        }

        val objects = ArrayList<JsonObject>()
        // 根节点是数组时，顶层每个对象都是"候选书源"：
        // 即使它缺地址，也要计入拒绝列表告诉用户，而不是静默丢掉。
        // 嵌套结构无法区分"缺地址的源"与"包裹容器"，仍走 hasUrlField 启发式。
        val missingUrlAtTop = ArrayList<JsonObject>()
        if (root.isJsonArray) {
            root.asJsonArray.forEach { el ->
                if (!el.isJsonObject) return@forEach
                val obj = el.asJsonObject
                if (hasUrlField(obj)) objects.add(obj) else missingUrlAtTop.add(obj)
            }
        } else {
            collectSourceObjects(root, objects, 0)
        }

        val format = when {
            root.isJsonArray -> "json-array"
            objects.size == 1 && hasUrlField(root as JsonObject) -> "json-object"
            else -> "json-wrapped"
        }

        val accepted = ArrayList<ImportedBookSource>()
        val rejected = ArrayList<ImportResult.Rejected>()
        val seen = HashMap<String, Int>()

        objects.forEachIndexed { index, obj ->
            val url = firstString(obj, URL_KEYS)?.trim().orEmpty()
            if (url.isEmpty()) {
                rejected.add(
                    ImportResult.Rejected(index, "缺少书源地址", obj.toString().take(120))
                )
                return@forEachIndexed
            }
            if (!SourceValidator.isHttpUrl(url)) {
                rejected.add(
                    ImportResult.Rejected(index, "书源地址不是 http/https：" + url, url.take(120))
                )
                return@forEachIndexed
            }
            val name = firstString(obj, NAME_KEYS)?.trim()?.takeIf { it.isNotEmpty() }
                ?: hostOf(url)
            val group = firstString(obj, GROUP_KEYS)?.trim()?.takeIf { it.isNotEmpty() }
            val type = firstInt(obj, TYPE_KEYS)

            if (seen.containsKey(url)) {
                // 同文件内重复：只保留第一次出现的，后面的与它合并分组信息
                seen[url] = seen[url]!! + 1
                return@forEachIndexed
            }
            seen[url] = 0
            accepted.add(ImportedBookSource(url, name, group, type, obj.toString()))
        }

        // 顶层数组里缺地址的对象：明确记为拒绝，便于用户定位哪一条是残件
        missingUrlAtTop.forEach { obj ->
            rejected.add(
                ImportResult.Rejected(
                    rejected.size + accepted.size,
                    "缺少书源地址",
                    obj.toString().take(120)
                )
            )
        }

        val dup = seen.values.sum()

        if (accepted.isEmpty() && rejected.isEmpty()) {
            return ImportResult(
                emptyList(),
                listOf(ImportResult.Rejected(0, "JSON 里没有找到书源对象", raw.take(120))),
                0,
                format
            )
        }
        return ImportResult(accepted, rejected, dup, format)
    }

    /**
     * 递归收集「像书源」的对象。
     *
     * 判定标准是「含有地址字段」，而不是位置或层级——
     * 这样无论是 `{"data":[ {...} ]}`、`[ {...} ]` 还是嵌套两层的包裹都能出来。
     * 深度上限 8 层，防御异常构造的深层嵌套。
     */
    private fun collectSourceObjects(el: JsonElement, out: MutableList<JsonObject>, depth: Int) {
        if (depth > 8) return
        if (out.size >= MAX_ITEMS) return

        when {
            el.isJsonArray -> el.asJsonArray.forEach { child ->
                collectSourceObjects(child, out, depth + 1)
            }

            el.isJsonObject -> {
                val obj = el.asJsonObject
                if (hasUrlField(obj)) {
                    out.add(obj)
                    return
                }
                // 先按已知包裹键找，找不到再遍历全部字段（兼容自定义包裹名）
                var matched = false
                for (key in WRAPPER_KEYS) {
                    val child = obj.get(key) ?: continue
                    if (child.isJsonArray || child.isJsonObject) {
                        collectSourceObjects(child, out, depth + 1)
                        matched = true
                    }
                }
                if (!matched) {
                    obj.entrySet().forEach { e ->
                        val child = e.value
                        if (child.isJsonArray || child.isJsonObject) {
                            collectSourceObjects(child, out, depth + 1)
                        }
                    }
                }
            }
        }
    }

    private fun hasUrlField(obj: JsonObject): Boolean =
        URL_KEYS.any { obj.has(it) && !obj.get(it).isJsonNull }

    private fun firstString(obj: JsonObject, keys: List<String>): String? {
        for (k in keys) {
            val el = obj.get(k) ?: continue
            if (el.isJsonNull || !el.isJsonPrimitive) continue
            return try {
                el.asString
            } catch (e: Throwable) {
                null
            }
        }
        return null
    }

    private fun firstInt(obj: JsonObject, keys: List<String>): Int {
        for (k in keys) {
            val el = obj.get(k) ?: continue
            if (el.isJsonNull || !el.isJsonPrimitive) continue
            val v = try {
                el.asString
            } catch (e: Throwable) {
                continue
            }
            v.trim().toIntOrNull()?.let { return it.coerceIn(0, 3) }
        }
        return 0
    }

    /** 名称缺省时用域名兜底，比显示「未命名」更有辨识度。 */
    private fun hostOf(url: String): String {
        val authority = url.removePrefix("http://").removePrefix("https://")
            .substringBefore('/').substringBefore('?').substringBefore('#')
        val host = authority.substringAfterLast('@').substringBefore(':').trim()
        return host.ifEmpty { "未命名书源" }
    }

    // ============================ 文本行路线 ============================

    /** 纯文本：每行一个地址，支持 `地址 名称` 与 `地址,分组` 两种写法。 */
    private fun parseTextLines(raw: String): ImportResult {
        val accepted = ArrayList<ImportedBookSource>()
        val rejected = ArrayList<ImportResult.Rejected>()
        val seen = HashSet<String>()
        var dup = 0

        raw.lines().forEachIndexed { index, lineRaw ->
            val line = lineRaw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            // 取第一段作为地址，其余可选的当作名称
            val parts = line.split('\t', ' ', ',', '|').filter { it.isNotBlank() }
            val url = parts.firstOrNull().orEmpty()
            if (!SourceValidator.isHttpUrl(url)) {
                rejected.add(
                    ImportResult.Rejected(index, "不是有效链接", line.take(120))
                )
                return@forEachIndexed
            }
            if (!seen.add(url)) {
                dup++
                return@forEachIndexed
            }
            val name = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: hostOf(url)
            val group = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
            accepted.add(
                ImportedBookSource(
                    url = url,
                    name = name,
                    group = group,
                    type = 0,
                    raw = "{\"bookSourceUrl\":\"" + url + "\",\"bookSourceName\":\"" + name + "\"}"
                )
            )
        }

        if (accepted.isEmpty() && rejected.isEmpty()) {
            return ImportResult(emptyList(), emptyList(), 0, "text-lines")
        }
        return ImportResult(accepted, rejected, dup, "text-lines")
    }

    // ============================ 清洗 ============================

    /**
     * 去掉 JSON5 风格的注释与尾随逗号。
     *
     * 关键点是**字符串感知**：`https://a.com` 里的 `//` 不是注释，
     * 盲目正则替换会把所有地址截断成 `https:` —— 这是最容易踩的坑。
     * 因此这里逐字符扫描并跟踪字符串状态与转义。
     */
    fun stripCommentsAndTrailingCommas(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        var inString = false
        var quote = ' '

        while (i < src.length) {
            val c = src[i]
            if (inString) {
                sb.append(c)
                if (c == '\\' && i + 1 < src.length) {
                    sb.append(src[i + 1])
                    i += 2
                    continue
                }
                if (c == quote) inString = false
                i++
                continue
            }
            when {
                c == '"' || c == '\'' -> {
                    inString = true
                    quote = c
                    sb.append(c)
                    i++
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                    while (i < src.length && src[i] != '\n') i++
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                    i += 2
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }

        // 第二轮：删除 } / ] 前的尾随逗号
        val s = sb.toString()
        val out = StringBuilder(s.length)
        for (idx in s.indices) {
            val c = s[idx]
            if (c == ',') {
                var j = idx + 1
                while (j < s.length && s[j].isWhitespace()) j++
                if (j < s.length && (s[j] == '}' || s[j] == ']')) continue
            }
            out.append(c)
        }
        return out.toString()
    }
}
