package com.lanyue.reader.source.purify

/**
 * 解析后的源级净化配置。
 *
 * [key] 是缓存键：书源 id + replaceRule 原文的指纹。
 * 用原文而不是解析结果做键——解析结果相等不代表原文相等（例如缺省字段 vs 显式写默认值），
 * 而用户改书源时改的正是原文。
 */
data class PurifyProfile(
    val key: String,
    val rules: List<ReplaceRule>,
    val overriddenGlobals: Int = 0,
    /** 该书源 replaceRule 自身的问题（解码时产生），供编辑器展示 */
    val problems: List<String> = emptyList()
) {
    val isEmpty: Boolean get() = rules.isEmpty()
}

/**
 * 按书源解析净化规则并缓存。
 *
 * 为什么需要这一层而不是每次净化都现解：
 * 一次翻章就要跑一遍正文净化，如果每次都把 replaceRule 的 JSON 重新解析、
 * 把每条正则重新编译，长章节列表连续翻页时会明显掉帧。这里按
 * (sourceId, replaceRule 原文) 缓存已编译的 [TextPurifier]，
 * 书源一改原文缓存键就变，自然失效，不需要额外的失效通知。
 *
 * 缓存容量上限的意义：用户可能导入几千个书源，无上限缓存会把
 * 每个源的正则表全留在内存里。超出后按 FIFO 淘汰最旧的条目。
 */
class PurifyPipeline(
    /** 全局规则（跨站通用的水印、站点后缀等） */
    private val globalRules: List<ReplaceRule> = emptyList(),
    private val maxCached: Int = 64
) {
    private val cache = LinkedHashMap<String, TextPurifier>()
    private var cachedHash = 0

    /** 当前全局规则指纹，用于在全局规则变化时整体失效缓存 */
    private fun globalHash(): Int = globalRules.hashCode()

    /**
     * 为某个书源准备净化配置。
     *
     * @param sourceId 书源标识，仅用于缓存键区分
     * @param replaceRuleRaw 书源 replaceRule 原文（可为 null / 空）
     */
    fun profileFor(sourceId: String, replaceRuleRaw: String?): PurifyProfile {
        val decoded = PurifyRuleCodec.decode(replaceRuleRaw)
        val merged = PurifyRuleCodec.merge(globalRules, decoded.rules)
        val raw = replaceRuleRaw.orEmpty()
        val key = sourceId + "\u0001" + raw.hashCode() + "\u0001" + raw.length
        return PurifyProfile(
            key = key,
            rules = merged.rules,
            overriddenGlobals = merged.overridden,
            problems = decoded.problems
        )
    }

    /**
     * 直接净化正文：内部取（或建）该书源的净化器。
     * 这是上层接入时唯一需要调用的方法。
     */
    fun purifyContent(sourceId: String, replaceRuleRaw: String?, text: String): PurifyResult {
        return purifierFor(sourceId, replaceRuleRaw).purifyContent(text)
    }

    fun purifyTitle(sourceId: String, replaceRuleRaw: String?, text: String): PurifyResult {
        return purifierFor(sourceId, replaceRuleRaw).purifyTitle(text)
    }

    /** 取该书源对应的净化器，命中缓存则复用已编译正则 */
    fun purifierFor(sourceId: String, replaceRuleRaw: String?): TextPurifier {
        val gh = globalHash()
        if (gh != cachedHash) {
            // 全局规则变了，所有源的正则表都可能过期，整体清掉
            cache.clear()
            cachedHash = gh
        }
        val profile = profileFor(sourceId, replaceRuleRaw)
        cache[profile.key]?.let { return it }

        val purifier = TextPurifier(profile.rules)
        if (cache.size >= maxCached) {
            // FIFO：淘汰最早插入的一条
            val oldest = cache.keys.firstOrNull()
            if (oldest != null) cache.remove(oldest)
        }
        cache[profile.key] = purifier
        return purifier
    }

    /** 书源 replaceRule 变更后主动丢弃其缓存（可选调用，不调用也会因 key 变化自然失效） */
    fun invalidate(sourceId: String) {
        cache.keys.removeAll { it.startsWith(sourceId + "\u0001") }
    }

    fun clear() {
        cache.clear()
    }

    val cachedCount: Int get() = cache.size
}
