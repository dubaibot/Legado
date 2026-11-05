package com.lanyue.reader.source

import com.lanyue.reader.data.db.entity.BookSourceEntity
import com.lanyue.reader.data.db.entity.DanmakuSourceEntity
import com.lanyue.reader.data.db.entity.RssSourceEntity
import com.lanyue.reader.data.db.entity.VideoSiteEntity
import com.lanyue.reader.source.model.IDanmakuSource
import com.lanyue.reader.source.model.IBookSource
import com.lanyue.reader.source.model.ISubscriptionSource
import com.lanyue.reader.source.model.IVideoSource

/**
 * 源实例工厂入口。
 *
 * SourceRegistry 只负责「实体 -> 源实现」的装配，不做调度决策；
 * 调度（能力判定、失败隔离、降级顺序）全部在 SourceRouter。
 *
 * 这样保证：
 * 1. 源实现构造函数里不出现数据库/DAO，便于单测
 * 2. 关闭某类源时（enabled=false）在装配阶段直接过滤，路由层看不到它
 */
class SourceRegistry(
    private val bookFactory: (BookSourceEntity) -> IBookSource?,
    private val rssFactory: (RssSourceEntity) -> ISubscriptionSource?,
    private val videoFactory: (VideoSiteEntity) -> IVideoSource?,
    private val danmakuFactory: (DanmakuSourceEntity) -> IDanmakuSource?
) {

    fun book(entity: BookSourceEntity): IBookSource? =
        if (entity.enabled) bookFactory(entity) else null

    fun rss(entity: RssSourceEntity): ISubscriptionSource? =
        if (entity.enabled) rssFactory(entity) else null

    /**
     * 影视站点：enabled + 非 unsupported。
     * 7.3 type 3 非 drpy 的 jar 站点在 CapabilityResolver 之前就被挡掉，
     * UI 上以「暂不支持」呈现，不进入路由候选集。
     */
    fun video(entity: VideoSiteEntity): IVideoSource? =
        if (entity.enabled && !entity.unsupported) videoFactory(entity) else null

    fun danmaku(entity: DanmakuSourceEntity): IDanmakuSource? =
        if (entity.enabled) danmakuFactory(entity) else null
}
