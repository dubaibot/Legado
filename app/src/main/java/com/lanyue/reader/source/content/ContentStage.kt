package com.lanyue.reader.source.content

import com.lanyue.reader.source.purify.PurifyPipeline
import com.lanyue.reader.source.purify.PurifyStats

/**
 * 正文获取与净化串联层（纯逻辑，无 Android / 网络依赖）。
 *
 * 把 T15 的两件事收在一处：
 * 1. **净化在排版前**——正文先过 [PurifyPipeline] 再拆段交给排版。
 *    反过来（先排版再净化）会让字数变化导致分页错位：水印被裁进某页后
 *    再被净化掉，该页文字变少、全局字符偏移串位，进度条与翻页都会跟着错。
 * 2. **缓存净化结果**——同一章二次打开直接复用上次的净化产物，不重复跑正则。
 *    [PurifyPipeline] 缓存的是「编译好的净化器」（按源），每次调用仍要对正文
 *    跑一遍替换；本层缓存的是「跑完的结果」（按章），翻章回来时零计算。
 *
 * 正文获取本身（规则取数、网络请求）由上层注入：[prepare] 接收已取到的原始正文，
 * 本层只管净化 + 拆段 + 缓存。这样整条串联可以脱机单测，真机只补网络一段。
 */
class ContentStage(
    private val pipeline: PurifyPipeline,
    private val maxCachedChapters: Int = 32
) {

    /**
     * 一章的可排版产物。
     *
     * @param fromCache 是否命中结果缓存。true 表示本次没有跑净化，直接复用上次结果——
     *   这是 T15「同一章二次打开不重复净化」的观测点，上层可据此打日志或埋点计时。
     */
    data class PreparedContent(
        val chapterKey: String,
        val sourceId: String,
        val raw: String,
        val purified: String,
        val paragraphs: List<String>,
        val stats: PurifyStats,
        val fromCache: Boolean
    )

    private data class CacheEntry(
        val purified: String,
        val paragraphs: List<String>,
        val stats: PurifyStats,
        val fingerprint: String
    )

    private val cache = LinkedHashMap<String, CacheEntry>()

    /**
     * 取（或净化并缓存）一章的可排版正文。
     *
     * @param sourceId 书源标识，用于在 [PurifyPipeline] 里取对应的净化器
     * @param replaceRuleRaw 书源 replaceRule 原文（可为 null / 空）
     * @param chapterKey 章节唯一键（通常是章节 URL），结果缓存按它命中
     * @param rawContent 已取到的原始正文
     */
    fun prepare(
        sourceId: String,
        replaceRuleRaw: String?,
        chapterKey: String,
        rawContent: String
    ): PreparedContent {
        val fp = fingerprint(sourceId, replaceRuleRaw)
        cache[chapterKey]?.let { entry ->
            // 指纹一致才复用：书源规则或全局规则变了，旧结果就是脏的，必须重跑。
            if (entry.fingerprint == fp) {
                return PreparedContent(
                    chapterKey = chapterKey,
                    sourceId = sourceId,
                    raw = rawContent,
                    purified = entry.purified,
                    paragraphs = entry.paragraphs,
                    stats = entry.stats,
                    fromCache = true
                )
            }
        }

        val result = pipeline.purifyContent(sourceId, replaceRuleRaw, rawContent)
        val paragraphs = splitParagraphs(result.text)
        evictIfNeeded()
        cache[chapterKey] = CacheEntry(result.text, paragraphs, result.stats, fp)
        return PreparedContent(
            chapterKey = chapterKey,
            sourceId = sourceId,
            raw = rawContent,
            purified = result.text,
            paragraphs = paragraphs,
            stats = result.stats,
            fromCache = false
        )
    }

    /**
     * 按 `\n` 拆段并去掉空行。
     *
     * 净化器的空白归一已把连续空行折叠成最多一行；这里再把空行去掉，
     * 因为 [com.lanyue.reader.core.render.Paginator] 自己会在段间插视觉空行，
     * 保留空段只会让分页多出无字行、把字符偏移推乱。
     */
    private fun splitParagraphs(text: String): List<String> =
        text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 结果缓存指纹：书源 + 全局规则 + replaceRule 原文。
     *
     * 用原文的 hashCode + length 而非解析结果——用户改书源时改的是原文：
     * 原文不变（哪怕解析逻辑变动导致解析结果不同）时结果应当可复用，
     * 原文一变就必然失效。全局规则指纹取自 [PurifyPipeline.globalRulesHash]，
     * 与它自身的净化器缓存失效口径一致，不会出现「净化器已重建但结果缓存还在用旧货」。
     */
    private fun fingerprint(sourceId: String, replaceRuleRaw: String?): String {
        val raw = replaceRuleRaw.orEmpty()
        return sourceId + "\u0001" + pipeline.globalRulesHash() + "\u0001" +
            raw.hashCode() + "\u0001" + raw.length
    }

    private fun evictIfNeeded() {
        if (cache.size >= maxCachedChapters) {
            val oldest = cache.keys.firstOrNull()
            if (oldest != null) cache.remove(oldest)
        }
    }

    /** 丢弃指定章节的缓存结果（章节正文被源站更新后调用）。 */
    fun invalidate(chapterKey: String) {
        cache.remove(chapterKey)
    }

    fun clear() {
        cache.clear()
    }

    val cachedCount: Int get() = cache.size
}
