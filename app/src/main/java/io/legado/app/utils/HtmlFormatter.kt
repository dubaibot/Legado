package io.legado.app.utils

import io.legado.app.model.analyzeRule.AnalyzeUrl
import java.net.URL
import java.util.regex.Pattern

@Suppress("RegExpRedundantEscape")
object HtmlFormatter {
    // 空块元素的占位标记：正文里不会出现，也不属于 \s，便于后续统计空段落个数
    private const val EMPTY_BLOCK_MARK = '\u0001'

    // 连续空行最多保留几行，防止个别站点堆一大串空段落撑出半屏空白
    private const val MAX_KEEP_BLANK_LINES = 5

    private val nbspRegex = "(&nbsp;)+".toRegex()
    private val espRegex = "(&ensp;|&emsp;)".toRegex()
    private val noPrintRegex = "(&thinsp;|&zwnj;|&zwj;|\u2009|\u200C|\u200D)".toRegex()
    private val wrapHtmlRegex = "</?(?:div|p|br|hr|h\\d|article|dd|dl)[^>]*>".toRegex()
    private val commentRegex = "<!--[^>]*-->".toRegex() // 注释
    private val notImgHtmlRegex = "</?(?!img)[a-zA-Z]+(?=[ >])[^<>]*>".toRegex()
    private val otherHtmlRegex = "</?[a-zA-Z]+(?=[ >])[^<>]*>".toRegex()
    private val blockContentRegex = "<(style|script)\\b[^>]*>[\\s\\S]*?</\\1\\s*>".toRegex(RegexOption.IGNORE_CASE)
    private val unclosedBlockRegex = "<(style|script)\\b[^>]*>[\\s\\S]*$".toRegex(RegexOption.IGNORE_CASE)
    private val formatImagePattern = Pattern.compile(
        "<img[^>]*\\ssrc\\s*=\\s*['\"]([^'\"{>]*\\{(?:[^{}]|\\{[^}>]+\\})+\\})['\"][^>]*>|<img[^>]*\\sdata-(?:src|original|srcset)\\s*=\\s*['\"]([^'\">]+)['\"][^>]*>|<img[^>]*\\ssrc\\s*=\\s*\"([^\">]+)\"[^>]*>|<img[^>]*\\s(?:data-[^=>]*|src)=\\s*['\"]([^'\">]*)['\"][^>]*>",
        Pattern.CASE_INSENSITIVE,
    )
    private val indent1Regex = "\\s*\\n+\\s*".toRegex()
    private val indent2Regex = "^[\\n\\s]+".toRegex()
    private val lastRegex = "[\\n\\s]+$".toRegex()

    // 空块元素：<p>/<div> 内只有空白、&nbsp;、<br>、<hr>，网页用它表示空行
    private val emptyBlockRegex =
        "<(p|div)\\b[^>]*>(?:[\\s　]|&nbsp;|<br\\s*/?>|<hr\\s*/?>)*</\\1\\s*>"
            .toRegex(RegexOption.IGNORE_CASE)

    /**
     * 相邻的块级标签串（含标签之间的空白）整体只算**一个**换行。
     *
     * 开启保留空行时必须先做这一步：`wrapHtmlRegex` 会给每个块级标签各补一个 `\n`，
     * 于是 `</p><p>`、`</div><div>` 天然产生连续两个换行，会被 [countBlankLines] 的
     * 「连续换行 = 原文空行」兜底规则误判成原文空行，结果是**每个段落之间都插进一个空行**。
     *
     * `<br>`、`<img>` 都不在其中：前者本身就是原文的换行信号（`<br><br>` 仍须能还原出空行），
     * 后者要留给 `formatKeepImg` 之后用 [formatImagePattern] 提取图片，标签不能被这条规则吃掉。
     */
    private val blockTagRunRegex =
        "(?:\\s*</?(?:div|p|hr|h\\d|article|dd|dl)[^>]*>\\s*)+".toRegex(RegexOption.IGNORE_CASE)

    // 段落间隔：空白 + 空块标记 + 换行（标记须包含在内，否则会阻断匹配把间隔切碎）
    private val gapRegex =
        "[\\s　$EMPTY_BLOCK_MARK]*\\n[\\s　\\n$EMPTY_BLOCK_MARK]*".toRegex()

    fun format(
        html: String?,
        otherRegex: Regex = otherHtmlRegex,
        keepBlankLine: Boolean = false,
    ): String {
        html ?: return ""
        // 开启保留空行时先做两步归一化，让后面统计到的是真实空段落个数，
        // 而不是每个标签都换成一个 \n 带来的噪声：
        // 1. 「空块元素」整体换成一个标记；
        // 2. 相邻块级标签串压成一个换行（否则 </p><p> 会被当成原文空行）
        val source = if (keepBlankLine) {
            html.replace(emptyBlockRegex, "\n$EMPTY_BLOCK_MARK")
                .replace(blockTagRunRegex, "\n")
        } else {
            html
        }
        val text = source.replace(nbspRegex, " ")
            .replace(espRegex, " ")
            .replace(noPrintRegex, "")
            .replace(wrapHtmlRegex, "\n")
            .replace(commentRegex, "")
            .replace(otherRegex, "")
        // 保留空行：默认会把连续换行压成一个（原文空行就此丢失）。
        // 开启后按「空段落个数」还原空行数量，最多 MAX_KEEP_BLANK_LINES 个，
        // 防止个别站点堆一大串空段落把正文撑出半屏空白；
        // 没有空段落但出现连续换行时（如 <br><br>）仍保留一个空行。
        val indented = if (keepBlankLine) {
            text.replace(gapRegex) { match ->
                val blankLines = countBlankLines(match.value)
                if (blankLines > 0) "\n" + "\n".repeat(blankLines) + "　　" else "\n　　"
            }
        } else {
            text.replace(indent1Regex, "\n　　")
        }
        return indented
            .replace(indent2Regex, "　　")
            .replace(lastRegex, "")
            // 兜底：清掉可能残留在开头/结尾的空块标记
            .replace(EMPTY_BLOCK_MARK.toString(), "")
    }

    private fun countBlankLines(gap: String): Int {
        val marks = gap.count { it == EMPTY_BLOCK_MARK }
        return when {
            marks > 0 -> minOf(marks, MAX_KEEP_BLANK_LINES)
            gap.count { it == '\n' } >= 2 -> 1
            else -> 0
        }
    }

    /**
     * 格式化为纯文本：在 format 基础上先移除 style/script 块的内容
     * format 只删除标签本身，<style> 内的 CSS 源码会被当作正文保留，
     * 因此书架简介等纯文本场景必须用此方法
     */
    fun formatToPlainText(html: String?): String {
        html ?: return ""
        return format(
            html.replace(blockContentRegex, "")
                .replace(unclosedBlockRegex, ""),
        )
    }

    fun formatKeepImg(
        html: String?,
        redirectUrl: URL? = null,
        keepBlankLine: Boolean = false,
    ): String {
        html ?: return ""
        val keepImgHtml = format(html, notImgHtmlRegex, keepBlankLine)

        // 正则的“|”处于顶端而不处于（）中时，具有类似||的熔断效果，故以此机制简化原来的代码
        val matcher = formatImagePattern.matcher(keepImgHtml)
        var appendPos = 0
        val sb = StringBuilder()
        while (matcher.find()) {
            var param = ""
            sb.append(
                keepImgHtml.substring(appendPos, matcher.start()),
                "<img src=\"${
                    NetworkUtils.getAbsoluteURL(
                        redirectUrl,
                        matcher.group(1)?.let {
                            val urlMatcher = AnalyzeUrl.paramPattern.matcher(it)
                            if (urlMatcher.find()) {
                                param = ',' + it.substring(urlMatcher.end())
                                it.substring(0, urlMatcher.start())
                            } else {
                                it
                            }
                        } ?: matcher.group(2) ?: matcher.group(3) ?: matcher.group(4)!!,
                    ) + param
                }\">",
            )
            appendPos = matcher.end()
        }
        if (appendPos < keepImgHtml.length) {
            sb.append(
                keepImgHtml.substring(
                    appendPos,
                    keepImgHtml.length,
                ),
            )
        }
        return sb.toString()
    }
}
