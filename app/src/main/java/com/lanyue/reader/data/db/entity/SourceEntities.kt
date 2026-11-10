package com.lanyue.reader.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 4.12 book_sources 书源。
 * bookSourceType: 0 文本 / 1 音频 / 2 图片 / 3 文件 / 4 视频（TODO-DEV-2 枚举边界核对）
 */
@Entity(tableName = "book_sources")
data class BookSourceEntity(
    @PrimaryKey val bookSourceUrl: String,
    val bookSourceName: String = "",
    val bookSourceGroup: String? = null,
    val bookSourceType: Int = 0,
    val enabled: Boolean = true,
    val enabledExplore: Boolean = false,
    val searchUrl: String? = null,
    /** 嵌套规则对象以 JSON 序列化（4.12 TypeConverter）。 */
    val ruleSearch: String? = null,
    val exploreUrl: String? = null,
    val ruleExplore: String? = null,
    val ruleToc: String? = null,
    val ruleContent: String? = null,
    val ruleBookInfo: String? = null,
    val header: String? = null,
    val loginUrl: String? = null,
    val loginUi: String? = null,
    val loginCheckJs: String? = null,
    val jsLib: String? = null,
    val concurrentRate: String? = null,
    val lastUpdateTime: Long = 0,
    /** ADR-019：规则订阅更新时这些字段不覆盖。 */
    val userEditedFields: String? = null,
    /** 1.4.3 搜索失败降级本地标记。 */
    val searchDegraded: Boolean = false,
    val searchFailCount: Int = 0,
    /**
     * Legado 书源 replaceRule 原文（JSON 数组串）。
     * 按原样存储而不是解析成表：书源更新时要能原样回写，且解析失败也不能丢原文。
     * 解析与校验交给 PurifyRuleCodec，见 source/purify/。
     */
    val replaceRule: String? = null
)

/**
 * 4.12 rss_sources 订阅源（Legado RssSource）。
 * 1.4.1 字段按真实实体对齐。
 */
@Entity(tableName = "rss_sources")
data class RssSourceEntity(
    @PrimaryKey val sourceUrl: String,
    val sourceName: String = "",
    val sourceIcon: String? = null,
    val sourceGroup: String? = null,
    val sourceComment: String? = null,
    val enabled: Boolean = true,
    val enabledCookieJar: Boolean = false,
    val enableJs: Boolean = true,
    val loadWithBaseUrl: Boolean = false,
    val singleUrl: Boolean = false,
    val header: String? = null,
    val concurrentRate: String? = null,
    val loginUrl: String? = null,
    val loginUi: String? = null,
    val loginCheckJs: String? = null,
    /** "分类名::url" 多行或 && 分隔；支持 @js: / <js></js> 动态生成。 */
    val sortUrl: String? = null,
    val ruleArticles: String? = null,
    val ruleNextPage: String? = null,
    val ruleTitle: String? = null,
    val rulePubDate: String? = null,
    val ruleImage: String? = null,
    val ruleLink: String? = null,
    val ruleDescription: String? = null,
    val ruleContent: String? = null,
    val style: String? = null,
    val injectJs: String? = null,
    val preloadJs: String? = null,
    val startHtml: String? = null,
    val showWebLog: Boolean = false,
    val shouldOverrideUrlLoading: String? = null,
    val contentWhitelist: String? = null,
    val contentBlacklist: String? = null,
    /** 1.4.1 type：0 网页 / 1 图片 / 2 视频 */
    val type: Int = 0,
    val articleStyle: Int = 0,
    val coverDecodeJs: String? = null,
    /** 览阅扩展：停更前最后新增字段，原版无 UI。 */
    val searchUrl: String? = null,
    val jsLib: String? = null,
    val variables: String? = null,
    val variableComment: String? = null,
    val customOrder: Int = 0,
    val lastUpdateTime: Long = 0,
    val preload: Boolean = false,
    val cacheFirst: Boolean = false,
    val searchDegraded: Boolean = false,
    val searchFailCount: Int = 0,
    val userEditedFields: String? = null
)

/** 4.12 video_apis TVBox 接口配置。 */
@Entity(tableName = "video_apis")
data class VideoApiEntity(
    @PrimaryKey val url: String,
    val name: String? = null,
    val spider: String? = null,
    val lastUpdate: Long = 0,
    /** 原始 JSON 全量留存（hotSearch 已在解析时裁剪，不写入）。 */
    val rawJson: String = ""
)

/**
 * 4.12 video_sites 影视站点。
 * PK = (configUrl, siteKey)；删除接口配置时级联删除（见 Dao）。
 */
@Entity(tableName = "video_sites", primaryKeys = ["configUrl", "siteKey"])
data class VideoSiteEntity(
    val configUrl: String,
    val siteKey: String,
    val name: String = "",
    /** 0 XML(MacCMS V9) / 1 JSON(MacCMS10) / 2 JSON 扩展 / 3 Spider或drpy / 4 JSON扩展1(hipy t4) */
    val type: Int = 1,
    val api: String? = null,
    val searchable: Boolean = true,
    val quickSearch: Boolean = true,
    val filterable: Boolean = false,
    val ext: String? = null,
    val jar: String? = null,
    val timeout: Long = 0,
    val enabled: Boolean = true,
    /** 7.3 type 3 非 drpy 的 jar 站点标记「暂不支持」。 */
    val unsupported: Boolean = false
)

/** 4.12 live_sources 直播源：一期持久化，不暴露 UI。 */
@Entity(tableName = "live_sources")
data class LiveSourceEntity(
    @PrimaryKey val id: String,
    val apiUrl: String,
    val name: String = "",
    val type: String = "txt",
    val url: String = "",
    val epg: String? = null,
    val logo: String? = null
)

/** 4.12 danmaku_sources 弹幕源。appSecret 由 Keystore 加密后存此列。 */
@Entity(tableName = "danmaku_sources")
data class DanmakuSourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val apiBase: String,
    val appId: String? = null,
    /** 4.10.1 Keystore 加密存储；备份导出默认脱敏。 */
    val appSecretCipher: String? = null,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    /** 4.10.6 探活失败标记「未验证」，允许保存。 */
    val verified: Boolean = false
)

/**
 * 4.12 / 4.10.5 danmaku_bindings 匹配记忆。
 * videoKey：本地文件=路径+大小；在线=源ID+剧集URL。
 */
@Entity(tableName = "danmaku_bindings", primaryKeys = ["videoKey", "danmakuSourceId"])
data class DanmakuBindingEntity(
    val videoKey: String,
    val danmakuSourceId: String,
    val episodeId: Long,
    val shift: Long = 0,
    /** HASH / TITLE / MANUAL */
    val matchMethod: String = "TITLE",
    val boundAt: Long = System.currentTimeMillis()
)
