package com.lanyue.reader.source.purify

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** 解码结果。problems 是给书源编辑器直接展示的中文提示，不是异常。 */
data class PurifyDecodeResult(
    val rules: List<ReplaceRule>,
    val problems: List<String> = emptyList(),
    /** 原文非空但整体解析失败，规则已全部丢弃 */
    val broken: Boolean = false
) {
    val isEmpty: Boolean get() = rules.isEmpty()
}

/** 规则集合并结果 */
data class PurifyMergeResult(
    val rules: List<ReplaceRule>,
    /** 被源级规则覆盖掉的全局规则条数 */
    val overridden: Int = 0
)

/**
 * Legado `replaceRule` 的编解码。
 *
 * 为什么要单独一层：书源 JSON 里 replaceRule 的形态和 Legado 源码里的默认值
 * 有两处极易踩坑的地方，散落在业务代码里必然写错：
 *
 * 1. **Legado 的 isRegex 默认是 false**，不是 true。也就是说书源作者不写
 *    `isRegex` 时，这条规则是字面量替换。若按我们 ReplaceRule 的默认值 true 反序列化，
 *    作者写的 `(*广告*)` 会被当正则编译失败，整条规则静默失效。
 * 2. **Legado 用 scope 字符串区分作用域**：null / "" 表示全部，"title" 标题，
 *    "content" 正文。拼错（如 "titel"）在 Legado 里也是静默失效。
 *    这里对未知值给出明确 problem 并退回 ALL，让作者能看见问题。
 *
 * 另外，解析失败绝不能抛异常给上层：一份坏书源不该让整个导入流程崩掉，
 * 也不能丢掉 replaceRule 原文（原文仍留在实体字段里）。
 */
object PurifyRuleCodec {

    private const val K_NAME = "name"
    private const val K_PATTERN = "pattern"
    private const val K_REPLACEMENT = "replacement"
    private const val K_IS_REGEX = "isRegex"
    private const val K_IS_ENABLED = "isEnabled"
    private const val K_SCOPE = "scope"
    private const val K_SORT_ORDER = "sortOrder"
    private const val K_FIRST_LINE = "firstLineOnly"

    /**
     * 解析 replaceRule 原文。
     * 接受：JSON 数组、单个 JSON 对象、null / 空串 / 纯空白。
     */
    fun decode(raw: String?): PurifyDecodeResult {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return PurifyDecodeResult(emptyList())

        val root: JsonElement = try {
            JsonParser.parseString(text)
        } catch (e: Throwable) {
            return PurifyDecodeResult(
                rules = emptyList(),
                problems = listOf("replaceRule 不是合法 JSON（${e.message ?: "语法错误"}），已忽略全部净化规则"),
                broken = true
            )
        }

        val array: JsonArray = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> JsonArray().apply { add(root) }
            else -> return PurifyDecodeResult(
                rules = emptyList(),
                problems = listOf("replaceRule 既不是数组也不是对象，已忽略"),
                broken = true
            )
        }

        val rules = ArrayList<ReplaceRule>(array.size())
        val problems = ArrayList<String>()

        array.forEachIndexed { index, element ->
            val label = "第 ${index + 1} 条净化规则"
            if (!element.isJsonObject) {
                problems += "$label：不是对象，已跳过"
                return@forEachIndexed
            }
            val obj = element.asJsonObject
            val name = str(obj, K_NAME).orEmpty()
            val display = if (name.isNotBlank()) "$label（$name）" else label

            val pattern = str(obj, K_PATTERN)
            if (pattern == null) {
                problems += "$display：缺少 pattern，已跳过"
                return@forEachIndexed
            }
            if (pattern.isEmpty()) {
                // 保留但提示：空匹配串在执行期会被 TextPurifier 跳过，
                // 保留是为了让作者能在编辑器里看见并修掉它，而不是被静默删除
                problems += "$display：pattern 为空，执行时会被跳过"
            }

            val scope = decodeScope(str(obj, K_SCOPE), display, problems)
            val order = int(obj, K_SORT_ORDER) ?: 0
            if (order < 0) {
                problems += "$display：sortOrder 为负数，会排在全部规则之前"
            }

            rules += ReplaceRule(
                name = name,
                pattern = pattern,
                replacement = str(obj, K_REPLACEMENT).orEmpty(),
                // Legado 默认 false，这里必须跟随，不能沿用我们自己的默认值 true
                isRegex = bool(obj, K_IS_REGEX) ?: false,
                scope = scope,
                enabled = bool(obj, K_IS_ENABLED) ?: true,
                order = order,
                firstLineOnly = bool(obj, K_FIRST_LINE) ?: false
            )
        }

        return PurifyDecodeResult(rules, problems)
    }

    /** 编码回 Legado 兼容的 JSON 数组串，便于原样写回书源。 */
    fun encode(rules: List<ReplaceRule>): String {
        val array = JsonArray()
        rules.forEach { r ->
            array.add(JsonObject().apply {
                addProperty(K_NAME, r.name)
                addProperty(K_PATTERN, r.pattern)
                addProperty(K_REPLACEMENT, r.replacement)
                addProperty(K_IS_REGEX, r.isRegex)
                addProperty(K_IS_ENABLED, r.enabled)
                addProperty(K_SCOPE, encodeScope(r.scope))
                addProperty(K_SORT_ORDER, r.order)
                if (r.firstLineOnly) addProperty(K_FIRST_LINE, true)
            })
        }
        return array.toString()
    }

    /**
     * 合并全局规则与源级规则。
     *
     * 源级规则优先：同一 (pattern, scope) 视为同一条规则，源作者更清楚这个站的情况。
     * 不用「源级完全替换全局」的做法 —— 全局规则通常解决的是跨站通用脏数据
     * （盗版水印、站点后缀），被某本书源整份顶掉会让那些站点重新脏回来。
     */
    fun merge(global: List<ReplaceRule>, source: List<ReplaceRule>): PurifyMergeResult {
        val sourceKeys = source.map { key(it) }.toHashSet()
        val kept = global.filter { key(it) !in sourceKeys }
        val overridden = global.size - kept.size
        // 全局在前、源级在后；执行顺序仍由各自 order 决定
        return PurifyMergeResult(kept + source, overridden)
    }

    private fun key(rule: ReplaceRule): String = rule.scope.name + "\u0000" + rule.pattern

    private fun decodeScope(
        raw: String?,
        label: String,
        problems: MutableList<String>
    ): PurifyScope {
        val v = raw?.trim().orEmpty()
        return when (v.lowercase()) {
            "", "all" -> PurifyScope.ALL
            "title" -> PurifyScope.TITLE
            "content" -> PurifyScope.CONTENT
            else -> {
                problems += "$label：未知作用域 \"$v\"，已按全部处理（可选 title / content / all）"
                PurifyScope.ALL
            }
        }
    }

    private fun encodeScope(scope: PurifyScope): String = when (scope) {
        PurifyScope.ALL -> "all"
        PurifyScope.TITLE -> "title"
        PurifyScope.CONTENT -> "content"
    }

    /** 取字符串；数字也按字符串读，书源里 pattern 写成数字的情况真实存在 */
    private fun str(obj: JsonObject, key: String): String? {
        val e = obj.get(key) ?: return null
        if (e.isJsonNull) return null
        return try {
            if (e.isJsonPrimitive) e.asJsonPrimitive.asString else null
        } catch (ex: Throwable) {
            null
        }
    }

    /**
     * 取布尔。容忍 "true" / "false" 字符串与 1 / 0，
     * 这些形态在社区书源里都能见到（作者手写 JSON 时常见）。
     */
    private fun bool(obj: JsonObject, key: String): Boolean? {
        val e = obj.get(key) ?: return null
        if (e.isJsonNull) return null
        if (!e.isJsonPrimitive) return null
        val p = e.asJsonPrimitive
        return try {
            when {
                p.isBoolean -> p.asBoolean
                p.isNumber -> p.asInt != 0
                p.isString -> when (p.asString.trim().lowercase()) {
                    "true", "1", "yes" -> true
                    "false", "0", "no" -> false
                    else -> null
                }
                else -> null
            }
        } catch (ex: Throwable) {
            null
        }
    }

    private fun int(obj: JsonObject, key: String): Int? {
        val e = obj.get(key) ?: return null
        if (e.isJsonNull || !e.isJsonPrimitive) return null
        val p = e.asJsonPrimitive
        return try {
            when {
                p.isNumber -> p.asInt
                p.isString -> p.asString.trim().toIntOrNull()
                else -> null
            }
        } catch (ex: Throwable) {
            null
        }
    }
}
