package com.lanyue.reader.source.manage

import com.lanyue.reader.source.import.SourceImportParser
import com.lanyue.reader.source.import.SourceImportParser.ImportedBookSource
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 书源管理页的导入入口（T11）。
 *
 * [SourceImportParser] 只回答「一段已经能读的文本怎么拆成书源」，它不关心字节
 * 从哪来，也不区分「文件根本不是 JSON」和「JSON 没错但缺字段」。这两种失败对
 * 用户是完全不同的两件事：前者要换文件，后者要改书源。所以这里把两条来源
 * （导入文件、粘贴文本）收成同一个入口，并把失败分类成界面可区分、可提示的几档。
 *
 * 这一层不碰 IO：字节由调用方读好传进来，便于在纯 JVM 单测里覆盖编码判定。
 */
object SourceIntake {

    /** 与 [SourceImportParser] 的 16 MiB 上限保持一致，由单测反向校验。 */
    const val MAX_BYTES = 16 * 1024 * 1024

    /** 导入来源。文件与粘贴的差别只在于取字节还是取文本，以及提示措辞。 */
    enum class Origin(val id: String, val label: String) {
        FILE("file", "文件"),
        PASTE("paste", "粘贴"),
    }

    /**
     * 失败档位。顺序上先排除「拿不到内容」（空 / 过大 / 编码），
     * 再区分「拿到了但读不懂」（语法）与「读懂了但不可用」（缺字段）。
     */
    enum class FailureKind(val id: String, val label: String) {
        NONE("none", ""),
        EMPTY("empty", "内容为空"),
        TOO_LARGE("too-large", "文件过大"),
        ENCODING("encoding", "编码无法识别"),
        SYNTAX("syntax", "语法错误"),
        NO_FIELDS("no-fields", "缺少必填字段"),
        NOT_SOURCES("not-sources", "未识别到书源"),
        ;

        /** 语法错要换文件，字段错要改书源，二者不该给同一句提示。 */
        val actionable: Boolean get() = this == SYNTAX || this == NO_FIELDS
    }

    /** 解码结果。[encoding] 在失败时也会填，方便把「猜过的编码」告诉用户。 */
    data class Decoded(val text: String, val encoding: String, val ok: Boolean)

    data class IntakeResult(
        val origin: Origin,
        val failure: FailureKind,
        val detail: String,
        val encoding: String,
        val parsed: SourceImportParser.ImportResult?,
    ) {
        val ok: Boolean get() = failure == FailureKind.NONE

        val accepted: List<ImportedBookSource> get() = parsed?.accepted ?: emptyList()

        val rejectedCount: Int get() = parsed?.rejected?.size ?: 0

        val duplicates: Int get() = parsed?.duplicates ?: 0

        /** 供界面直接显示的一行结论。 */
        fun hint(): String = when (failure) {
            FailureKind.NONE -> buildString {
                append("已识别 ").append(accepted.size).append(" 条")
                if (duplicates > 0) append("，跳过重复 ").append(duplicates).append(" 条")
                if (rejectedCount > 0) append("，").append(rejectedCount).append(" 条无效")
            }
            FailureKind.EMPTY ->
                if (origin == Origin.FILE) "文件是空的，没有可导入的内容" else "粘贴内容为空"
            FailureKind.TOO_LARGE -> "文件超过 16 MiB 上限，请拆分后再导入"
            FailureKind.ENCODING -> "文本既不是 UTF-8 也不是 GBK，无法解码"
            FailureKind.SYNTAX -> "内容不是合法 JSON，请确认导出文件是否完整"
            FailureKind.NO_FIELDS -> "内容能解析，但书源缺少必填字段（多为书源地址），请检查后重试"
            FailureKind.NOT_SOURCES -> "内容能解析，但里面没有书源条目"
        }
    }

    // ============================ 两条入口 ============================

    /** 粘贴路径：已经是文本，只判空。 */
    fun fromText(text: String?, origin: Origin = Origin.PASTE): IntakeResult =
        parse(text, origin, "UTF-8")

    /**
     * 文件路径：先按大小拦，再判编码，最后才解析。
     *
     * 顺序有意义——16 MiB 的乱码文件应当报「过大」而不是「编码错」，
     * 否则用户会去折腾编码，而真正的问题是一次导了太多。
     */
    fun fromBytes(bytes: ByteArray?, origin: Origin = Origin.FILE): IntakeResult {
        if (bytes == null || bytes.isEmpty()) {
            return IntakeResult(origin, FailureKind.EMPTY, "0 字节", "none", null)
        }
        if (bytes.size > MAX_BYTES) {
            val mb = bytes.size.toDouble() / (1024 * 1024)
            return IntakeResult(
                origin,
                FailureKind.TOO_LARGE,
                String.format("%.1f MiB", mb),
                "none",
                null,
            )
        }
        val decoded = decode(bytes)
        if (!decoded.ok) {
            return IntakeResult(origin, FailureKind.ENCODING, "UTF-8 / GBK 均失败", decoded.encoding, null)
        }
        return parse(decoded.text, origin, decoded.encoding)
    }

    // ============================ 编码判定 ============================

    /**
     * 先看 BOM（最确定），再按严格解码试 UTF-8、GBK。
     *
     * 严格模式是关键：默认解码器遇到非法字节会静默替换成 U+FFFD，
     * 那样 GBK 文本会被当成「UTF-8 但全是问号」而蒙混过关。
     */
    fun decode(bytes: ByteArray): Decoded {
        if (bytes.isEmpty()) return Decoded("", "none", false)

        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return Decoded(String(bytes, 3, bytes.size - 3, Charsets.UTF_8), "UTF-8", true)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Decoded(String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE), "UTF-16LE", true)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Decoded(String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE), "UTF-16BE", true)
        }

        strictDecode(bytes, "UTF-8")?.let { return Decoded(it, "UTF-8", true) }
        strictDecode(bytes, "GBK")?.let { return Decoded(it, "GBK", true) }

        return Decoded(String(bytes, Charsets.UTF_8), "unknown", false)
    }

    private fun strictDecode(bytes: ByteArray, charsetName: String): String? = try {
        val decoder = Charset.forName(charsetName).newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    // ============================ 分类 ============================

    private fun parse(text: String?, origin: Origin, encoding: String): IntakeResult {
        if (text == null || text.isBlank()) {
            return IntakeResult(origin, FailureKind.EMPTY, "", encoding, null)
        }
        val parsed = SourceImportParser.parse(text)
        return classify(origin, parsed, encoding)
    }

    /** 字段级：对象本身找到了，只是地址空着或不合法。 */
    private fun isFieldLevel(reason: String): Boolean =
        reason.contains("地址") || reason.contains("链接")

    private fun classify(
        origin: Origin,
        parsed: SourceImportParser.ImportResult,
        encoding: String,
    ): IntakeResult {
        val failure = when (parsed.format) {
            "empty" -> FailureKind.EMPTY
            "too-large" -> FailureKind.TOO_LARGE
            "invalid-json" -> FailureKind.SYNTAX
            // 有收下的就成功；否则再分两种：拒绝理由是「地址/链接」这类
            // 字段级问题才算缺字段，其余（空数组、容器里没找到源）都归「未识别到书源」。
            else -> when {
                parsed.accepted.isNotEmpty() -> FailureKind.NONE
                parsed.rejected.any { isFieldLevel(it.reason) } -> FailureKind.NO_FIELDS
                else -> FailureKind.NOT_SOURCES
            }
        }
        val detail = if (failure == FailureKind.NONE) {
            ""
        } else {
            parsed.rejected.firstOrNull()?.reason.orEmpty()
        }
        return IntakeResult(origin, failure, detail, encoding, parsed)
    }
}
