package com.lanyue.reader.source.model

import com.lanyue.reader.data.db.entity.BookSourceEntity
import com.lanyue.reader.data.db.entity.RssSourceEntity
import com.lanyue.reader.data.db.entity.VideoSiteEntity

/**
 * 1.4.2 能力判定表。
 * SourceRouter 调用前必须先过这里，缺失能力则不调用，避免把不支持的操作
 * 抛到源实现里变成异常。
 */
object CapabilityResolver {

    /** 书源能力。 */
    fun ofBook(e: BookSourceEntity): Set<SourceCapability> = buildSet {
        // SEARCHABLE：searchUrl 非空 + ruleSearch 存在
        if (!e.searchUrl.isNullOrBlank() && !e.ruleSearch.isNullOrBlank()) {
            if (!e.searchDegraded) add(SourceCapability.SEARCHABLE)
        }
        // DISCOVERABLE：enabledExplore 且 exploreUrl 非空
        if (e.enabledExplore && !e.exploreUrl.isNullOrBlank()) add(SourceCapability.DISCOVERABLE)
        // HAS_CONTENT：ruleContent 非空
        if (!e.ruleContent.isNullOrBlank()) add(SourceCapability.HAS_CONTENT)
        if (!e.loginUrl.isNullOrBlank()) add(SourceCapability.HIGH_PRIORITY)
    }

    /**
     * 订阅源能力。与书源的差异：
     * - SEARCHABLE 只看 searchUrl（览阅扩展），无独立搜索规则
     * - RSS_FALLBACK：ruleArticles 为空
     */
    fun ofRss(e: RssSourceEntity): Set<SourceCapability> = buildSet {
        if (!e.searchUrl.isNullOrBlank() && !e.searchDegraded) add(SourceCapability.SEARCHABLE)
        // DISCOVERABLE：sortUrl 非空（是否能解析出 ≥1 分类在解析后二次确认）
        if (!e.sortUrl.isNullOrBlank()) add(SourceCapability.DISCOVERABLE)
        if (!e.ruleContent.isNullOrBlank()) add(SourceCapability.HAS_CONTENT)
        if (e.ruleArticles.isNullOrBlank()) add(SourceCapability.RSS_FALLBACK)
    }

    /**
     * 影视站点能力。
     * type 3 仅当 api 以 .js 结尾（drpy）才可用；jar 站点标记 unsupported。
     */
    fun ofVideoSite(e: VideoSiteEntity): Set<SourceCapability> = buildSet {
        if (e.searchable) add(SourceCapability.SEARCHABLE)
        if (e.filterable) add(SourceCapability.FILTERABLE)
    }
}
