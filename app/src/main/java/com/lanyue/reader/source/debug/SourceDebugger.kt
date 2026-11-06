package com.lanyue.reader.source.debug

import com.lanyue.reader.source.model.SourceValidator

/**
 * 书源调试管线（纯逻辑，无网络 / 无协程）。
 *
 * 书源调试是这类阅读器最难用的功能：用户面对一堆规则字段，
 * 不知道是哪一条写错、错在哪一步。真实排查路径永远是同一条链：
 * 搜索 -> 详情 -> 目录 -> 正文。
 *
 * 本模块把这四步的「观察结果」拆成可逐项检查的清单，
 * 让界面能画成四行状态灯，而不是只抛一个失败堆栈。
 *
 * 设计取舍：
 * - 不在本模块发请求（网络由上层注入），只判断「拿到的结果是否可用」；
 *   这样整条判定链可以脱机单测，也便于把抓取结果缓存到本地做复盘。
 * - 每一步都记录「原始样本」，让用户能点开看见真实返回，而不是只看到「失败」。
 */
object SourceDebugger {

    /** 四个调试阶段。 */
    enum class Stage(val id: String, val label: String) {
        SEARCH("search", "搜索"),
        DETAIL("detail", "详情"),
        TOC("toc", "目录"),
        CONTENT("content", "正文");

        companion object {
            fun fromId(id: String?): Stage? = entries.firstOrNull { it.id == id }
        }
    }

    /** 单步结果状态。 */
    enum class Status(val id: String, val label: String) {
        /** 通过。 */
        PASS("pass", "通过"),
        /** 可用但有隐患（例如只拿到 1 条、正文过短）。 */
        WARN("warn", "警告"),
        /** 失败。 */
        FAIL("fail", "失败"),
        /** 未执行（前一步失败时后续跳过）。 */
        SKIP("skip", "跳过");

        val isBlocking: Boolean get() = this == FAIL
    }

    /** 一步的执行结果。 */
    data class StepResult(
        val stage: Stage,
        val status: Status,
        /** 抓到的条目数：搜索=结果数，目录=章节数，详情/正文=1。 */
        val count: Int = 0,
        /** 取到的字符数，用于判断正文是否过短。 */
        val chars: Int = 0,
        /** 面向用户的一句话说明。 */
        val message: String = "",
        /** 原始返回样本（截断后），供用户展开查看。 */
        val sample: String = ""
    ) {
        val passed: Boolean get() = status == Status.PASS
    }

    /** 整条管线的结论。 */
    data class PipelineReport(
        val sourceUrl: String,
        val steps: List<StepResult>
    ) {
        /** 第一个失败的阶段；全部通过则为 null。 */
        val failedStage: Stage?
            get() = steps.firstOrNull { it.status == Status.FAIL }?.stage

        /** 是否整链可用（无 FAIL）。 */
        val isUsable: Boolean get() = steps.none { it.status == Status.FAIL }

        val warnCount: Int get() = steps.count { it.status == Status.WARN }

        val passCount: Int get() = steps.count { it.status == Status.PASS }

        fun step(stage: Stage): StepResult? = steps.firstOrNull { it.stage == stage }

        /** 一句话结论，直接做调试页顶部标题。 */
        fun summary(): String {
            val fail = failedStage
            if (fail != null) return fail.label + "阶段失败"
            return when (warnCount) {
                0 -> "四步全部通过，书源可用"
                1 -> "可用，" + warnCount + " 处需要留意"
                else -> "可用，" + warnCount + " 处需要留意"
            }
        }
    }

    /** 判定阈值：正文短于此字符数视为可疑（多半规则抓到了空壳）。 */
    private const val MIN_CONTENT_CHARS = 200

    /** 判定阈值：正文短于此字符数直接判失败。 */
    private const val TINY_CONTENT_CHARS = 20

    /** 搜索结果的合理上限，用于识别「抓到整页 HTML 当结果」的错误规则。 */
    private const val MAX_REASONABLE_SEARCH = 500

    /**
     * 检查搜索阶段。
     *
     * @param results   规则抽出的结果值（书名或书链接）
     * @param baseUrl   书源根地址，用于判断结果是否为相对链接被漏转
     */
    fun checkSearch(results: List<String>, baseUrl: String, sample: String = ""): StepResult {
        if (results.isEmpty()) {
            return StepResult(
                Stage.SEARCH, Status.FAIL, 0, 0,
                "搜索没有返回任何结果，检查搜索地址与「搜索」规则", sample.take(500)
            )
        }
        if (results.size > MAX_REASONABLE_SEARCH) {
            return StepResult(
                Stage.SEARCH, Status.WARN, results.size, 0,
                "返回 " + results.size + " 条，远超正常范围，规则可能选中了整页容器",
                sample.take(500)
            )
        }
        // 结果普遍是空白：规则选中了元素但没取到文本
        val blankRatio = results.count { it.isBlank() }.toDouble() / results.size
        if (blankRatio > 0.5) {
            return StepResult(
                Stage.SEARCH, Status.WARN, results.size, 0,
                "超过一半结果为空文本，规则可能缺少 @text 后缀", sample.take(500)
            )
        }
        return StepResult(
            Stage.SEARCH, Status.PASS, results.size, 0,
            "返回 " + results.size + " 条结果", sample.take(500)
        )
    }

    /**
     * 检查详情阶段（书名 / 封面 / 简介）。
     *
     * 详情阶段宽容度最高：很多源不写简介，缺简介不该判失败。
     * 只有「书名都取不到」才说明详情规则基本没工作。
     */
    fun checkDetail(name: String?, cover: String?, intro: String?, sample: String = ""): StepResult {
        val hasName = !name.isNullOrBlank()
        if (!hasName) {
            return StepResult(
                Stage.DETAIL, Status.FAIL, 0, 0,
                "取不到书名，检查「详情」规则（书名 / 作者）", sample.take(500)
            )
        }
        val missing = ArrayList<String>()
        if (cover.isNullOrBlank()) missing.add("封面")
        if (intro.isNullOrBlank()) missing.add("简介")
        return if (missing.isEmpty()) {
            StepResult(Stage.DETAIL, Status.PASS, 1, 0, "书名、封面、简介均已取到", sample.take(500))
        } else {
            StepResult(
                Stage.DETAIL, Status.WARN, 1, 0,
                "已取到书名，但缺少：" + missing.joinToString("、"), sample.take(500)
            )
        }
    }

    /** 检查目录阶段。 */
    fun checkToc(chapters: List<String>, sample: String = ""): StepResult {
        if (chapters.isEmpty()) {
            return StepResult(
                Stage.TOC, Status.FAIL, 0, 0,
                "目录为空，检查「目录」规则或目录地址是否被反爬拦截", sample.take(500)
            )
        }
        if (chapters.size == 1) {
            return StepResult(
                Stage.TOC, Status.WARN, 1, 0,
                "只取到 1 个章节，规则可能只选中了单个元素", sample.take(500)
            )
        }
        val blank = chapters.count { it.isBlank() }
        if (blank > 0) {
            return StepResult(
                Stage.TOC, Status.WARN, chapters.size, 0,
                "共 " + chapters.size + " 章，其中 " + blank + " 章标题为空",
                sample.take(500)
            )
        }
        return StepResult(
            Stage.TOC, Status.PASS, chapters.size, 0,
            "共 " + chapters.size + " 章", sample.take(500)
        )
    }

    /**
     * 检查正文阶段。
     *
     * 按字符数分三档：过短判失败（多半抓到导航栏或空容器）、
     * 偏短判警告（可能是短章节，也可能是规则截断）。
     */
    fun checkContent(text: String?, sample: String = ""): StepResult {
        val body = text.orEmpty().trim()
        if (body.isEmpty()) {
            return StepResult(
                Stage.CONTENT, Status.FAIL, 0, 0,
                "正文为空，检查「正文」规则或该章是否需要登录", sample.take(500)
            )
        }
        val len = body.length
        if (len < TINY_CONTENT_CHARS) {
            return StepResult(
                Stage.CONTENT, Status.FAIL, 1, len,
                "正文只有 " + len + " 字，规则可能选中了空容器或提示文字",
                sample.ifBlank { body }.take(500)
            )
        }
        if (len < MIN_CONTENT_CHARS) {
            return StepResult(
                Stage.CONTENT, Status.WARN, 1, len,
                "正文仅 " + len + " 字，偏短；若该章本身较长则规则可能截断",
                sample.ifBlank { body }.take(500)
            )
        }
        // 正文里混入大量标签，说明用了 @html 而没做清洗
        val tagCount = countTags(body)
        if (tagCount > 20) {
            return StepResult(
                Stage.CONTENT, Status.WARN, 1, len,
                "正文含 " + tagCount + " 个 HTML 标签，建议改用 @text 或加清洗正则",
                sample.ifBlank { body }.take(500)
            )
        }
        return StepResult(
            Stage.CONTENT, Status.PASS, 1, len,
            "正文 " + len + " 字", sample.ifBlank { body }.take(500)
        )
    }

    /**
     * 组装完整报告。
     *
     * 前一步失败时，后续阶段自动标 SKIP 而不是 FAIL——
     * 否则用户会看到四个红灯，误以为四处都配置错了。
     */
    fun buildReport(
        sourceUrl: String,
        search: StepResult?,
        detail: StepResult?,
        toc: StepResult?,
        content: StepResult?
    ): PipelineReport {
        val steps = ArrayList<StepResult>(4)
        var blocked = false
        listOf(
            search ?: StepResult(Stage.SEARCH, Status.SKIP, 0, 0, "未执行"),
            detail ?: StepResult(Stage.DETAIL, Status.SKIP, 0, 0, "未执行"),
            toc ?: StepResult(Stage.TOC, Status.SKIP, 0, 0, "未执行"),
            content ?: StepResult(Stage.CONTENT, Status.SKIP, 0, 0, "未执行")
        ).forEach { step ->
            if (blocked && step.status != Status.SKIP) {
                steps.add(
                    StepResult(step.stage, Status.SKIP, 0, 0, "上一步失败，未继续")
                )
            } else {
                steps.add(step)
                if (step.status == Status.FAIL) blocked = true
            }
        }
        return PipelineReport(sourceUrl, steps)
    }

    /** 快速自检：只校验配置字段本身（不发请求），用于书源列表的批量体检。 */
    fun staticCheck(
        url: String?,
        name: String?,
        searchUrl: String?,
        ruleSearch: String?,
        ruleToc: String?,
        ruleContent: String?,
        type: Int = 0
    ): PipelineReport {
        val report = SourceValidator.validateBook(
            bookSourceUrl = url,
            bookSourceName = name,
            searchUrl = searchUrl,
            ruleSearch = ruleSearch,
            exploreUrl = null,
            enabledExplore = false,
            ruleToc = ruleToc,
            ruleContent = ruleContent,
            loginUrl = null,
            bookSourceType = type
        )
        // 配置层面有错，就没有必要谈运行期四步，直接给一步结论
        if (report.hasError) {
            val first = report.errors().first()
            return PipelineReport(
                url.orEmpty(),
                listOf(
                    StepResult(
                        Stage.SEARCH, Status.FAIL, 0, 0,
                        "配置错误：" + first.message, ""
                    )
                )
            )
        }
        return PipelineReport(
            url.orEmpty(),
            listOf(
                StepResult(
                    Stage.SEARCH, Status.WARN, 0, 0,
                    "配置可用（" + report.summary() + "），需连网实测", ""
                )
            )
        )
    }

    /** 统计字符串里的尖括号标签数量（粗略估计，够用于判断是否未清洗）。 */
    private fun countTags(text: String): Int {
        var count = 0
        var i = 0
        while (i < text.length) {
            if (text[i] == '<') {
                val close = text.indexOf('>', i + 1)
                if (close > i + 1 && close - i < 120) {
                    count++
                    i = close + 1
                    continue
                }
            }
            i++
        }
        return count
    }
}
