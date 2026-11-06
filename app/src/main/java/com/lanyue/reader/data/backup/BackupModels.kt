package com.lanyue.reader.data.backup

/**
 * 备份用的传输对象（DTO）。
 *
 * 为什么不直接序列化 Room 实体：
 * 1. 实体带 androidx 注解，纯 JVM 环境无法加载，备份逻辑会变成"只能上真机验证"；
 * 2. 备份格式是对外契约，必须比数据库表结构更稳定——加字段、改索引不应该让老备份读不出来；
 * 3. 备份里不应出现只在本机有意义的字段（自增 id、缓存路径、加密后的密文）。
 *
 * 因此这里全部是可变默认值的朴素数据类，字段增删只影响本文件。
 */

/** 书架条目。 */
data class BookBackup(
    val bookUrl: String = "",
    val name: String = "",
    val author: String? = null,
    val cover: String? = null,
    val intro: String? = null,
    val origin: String = "",
    val originName: String? = null,
    val sourceType: Int = 0,
    val groupName: String = "全部",
    val latestChapter: String? = null,
    val totalChapter: Int = 0,
    val lastReadAt: Long = 0,
    val sortOrder: Int = 0
)

/** 书源。 */
data class BookSourceBackup(
    val bookSourceUrl: String = "",
    val bookSourceName: String = "",
    val bookSourceGroup: String? = null,
    val bookSourceType: Int = 0,
    val enabled: Boolean = true,
    val enabledExplore: Boolean = false,
    val searchUrl: String? = null,
    val ruleSearch: String? = null,
    val exploreUrl: String? = null,
    val ruleExplore: String? = null,
    val ruleToc: String? = null,
    val ruleContent: String? = null,
    val ruleBookInfo: String? = null,
    val header: String? = null,
    val loginUrl: String? = null,
    val jsLib: String? = null,
    val lastUpdateTime: Long = 0,
    /** 用户手工改过的字段清单（逗号分隔），用于导入时"保留本地改动"。 */
    val userEditedFields: String? = null
)

/** 订阅源。 */
data class RssSourceBackup(
    val sourceUrl: String = "",
    val sourceName: String = "",
    val sourceIcon: String? = null,
    val sourceGroup: String? = null,
    val enabled: Boolean = true,
    val sortUrl: String? = null,
    val ruleArticles: String? = null,
    val ruleTitle: String? = null,
    val ruleLink: String? = null,
    val rulePubDate: String? = null,
    val ruleContent: String? = null,
    val type: Int = 0,
    val searchUrl: String? = null,
    val customOrder: Int = 0,
    val lastUpdateTime: Long = 0
)

/** 影视站点。 */
data class VideoSiteBackup(
    val configUrl: String = "",
    val siteKey: String = "",
    val name: String = "",
    val type: Int = 1,
    val api: String = "",
    val ext: String? = null,
    val enabled: Boolean = true,
    val searchable: Boolean = true
)

/** 阅读进度。 */
data class ReadRecordBackup(
    val bookUrl: String = "",
    val chapterIndex: Int = 0,
    val chapterTitle: String? = null,
    val chapterUrl: String? = null,
    val position: Int = 0,
    val updatedAt: Long = 0
)

/** 播放进度。 */
data class PlayRecordBackup(
    val videoKey: String = "",
    val title: String = "",
    val episodeIndex: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val updatedAt: Long = 0
)

/** 订阅收藏。 */
data class RssFavoriteBackup(
    val articleUrl: String = "",
    val title: String = "",
    val sourceUrl: String = "",
    val cover: String? = null,
    val pubDate: Long? = null,
    val savedAt: Long = 0
)

/** 影视收藏。 */
data class VideoFavoriteBackup(
    val videoUrl: String = "",
    val title: String = "",
    val configUrl: String = "",
    val siteKey: String = "",
    val cover: String? = null,
    val savedAt: Long = 0
)

/** 整个备份包的内容。字段全部可空/空列表，缺省即"这一块不参与恢复"。 */
data class BackupPayload(
    val books: List<BookBackup> = emptyList(),
    val bookSources: List<BookSourceBackup> = emptyList(),
    val rssSources: List<RssSourceBackup> = emptyList(),
    val videoSites: List<VideoSiteBackup> = emptyList(),
    val readRecords: List<ReadRecordBackup> = emptyList(),
    val playRecords: List<PlayRecordBackup> = emptyList(),
    val rssFavorites: List<RssFavoriteBackup> = emptyList(),
    val videoFavorites: List<VideoFavoriteBackup> = emptyList()
) {
    /** 总条目数，用于界面显示"共 N 项"。 */
    val totalCount: Int
        get() = books.size + bookSources.size + rssSources.size + videoSites.size +
            readRecords.size + playRecords.size + rssFavorites.size + videoFavorites.size

    val isEmpty: Boolean get() = totalCount == 0
}

/** 备份包元信息。 */
data class BackupMeta(
    val format: String = BackupFormat.FORMAT_ID,
    val schemaVersion: Int = BackupFormat.SCHEMA_VERSION,
    /** 导出时间，毫秒时间戳。 */
    val exportedAt: Long = 0,
    /** 导出时的应用版本名，仅用于提示，不参与兼容判断。 */
    val appVersion: String = "",
    /** 内容摘要，用于校验备份是否被截断/篡改。 */
    val checksum: String = ""
)

/** 一份完整备份 = 元信息 + 内容。 */
data class BackupDocument(
    val meta: BackupMeta,
    val payload: BackupPayload
)

/** 备份格式常量。 */
object BackupFormat {
    /** 识别标记：不是本应用的 JSON 直接拒绝，避免误导入。 */
    const val FORMAT_ID = "lanyue-backup"

    /** 当前写出的版本。 */
    const val SCHEMA_VERSION = 2

    /** 最低可读版本：低于此版本的备份直接拒绝（格式差异过大）。 */
    const val MIN_READABLE_VERSION = 1

    /** 单份备份的条目上限，防止导入恶意超大文件把内存打满。 */
    const val MAX_ITEMS_PER_SECTION = 200_000

    /** 备份文件体积上限（字节）：8 MiB。 */
    const val MAX_BYTES = 8 * 1024 * 1024
}
