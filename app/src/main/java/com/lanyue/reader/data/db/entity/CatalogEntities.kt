package com.lanyue.reader.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 3.1 书架。分组：全部/小说/音频/追剧/订阅。 */
@Entity(tableName = "books", indices = [Index("groupName"), Index("sourceType")])
data class BookEntity(
    @PrimaryKey val bookUrl: String,
    val name: String,
    val author: String? = null,
    val cover: String? = null,
    val intro: String? = null,
    val origin: String,
    val originName: String? = null,
    /** 0 小说 / 1 音频 / 2 追剧 / 3 订阅 */
    val sourceType: Int = 0,
    val groupName: String = "全部",
    val latestChapter: String? = null,
    val totalChapter: Int = 0,
    val lastReadAt: Long = 0,
    val sortOrder: Int = 0,
    val inBookshelf: Boolean = true
)

/** 3.3 订阅收藏（RssFavorites）。 */
@Entity(tableName = "rss_favorites")
data class RssFavoriteEntity(
    @PrimaryKey val articleUrl: String,
    val title: String,
    val sourceUrl: String,
    val cover: String? = null,
    val pubDate: Long? = null,
    val savedAt: Long = System.currentTimeMillis()
)

/** 影视收藏。 */
@Entity(tableName = "video_favorites")
data class VideoFavoriteEntity(
    @PrimaryKey val videoUrl: String,
    val title: String,
    val configUrl: String,
    val siteKey: String,
    val cover: String? = null,
    val savedAt: Long = System.currentTimeMillis()
)

/** 4.12 play_records 播放进度。 */
@Entity(tableName = "play_records")
data class PlayRecordEntity(
    @PrimaryKey val videoKey: String,
    val title: String,
    val episodeIndex: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

/** 4.12 read_records 阅读进度。 */
@Entity(tableName = "read_records")
data class ReadRecordEntity(
    @PrimaryKey val bookUrl: String,
    val chapterIndex: Int = 0,
    val chapterTitle: String? = null,
    val chapterUrl: String? = null,
    val position: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

/** 4.12 rule_subs 规则订阅。 */
@Entity(tableName = "rule_subs")
data class RuleSubEntity(
    @PrimaryKey val id: String,
    /** BOOK / SUBSCRIPTION */
    val sourceType: String,
    val url: String,
    val name: String = "",
    val periodHours: Int = 24,
    val lastRunAt: Long = 0,
    val enabled: Boolean = true
)

/** 4.12 download_tasks 下载任务。 */
@Entity(tableName = "download_tasks")
data class DownloadTaskEntity(
    @PrimaryKey val id: String,
    /** TEXT / MEDIA */
    val kind: String,
    val targetKey: String,
    val name: String = "",
    val state: Int = 0,
    val progress: Long = 0,
    val total: Long = 0,
    val errorMsg: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/** 4.12 cache_files 缓存文件。 */
@Entity(tableName = "cache_files")
data class CacheFileEntity(
    @PrimaryKey val path: String,
    val kind: String,
    val sizeBytes: Long = 0,
    val locked: Boolean = false,
    val lastAccessAt: Long = System.currentTimeMillis()
)

/** 3.8 搜索历史落 search_history 表。 */
@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val keyword: String,
    /** BOOK / SUBSCRIPTION / VIDEO */
    val sourceType: String,
    val searchedAt: Long = System.currentTimeMillis()
)

/** 4.12 webdav_credentials。password 由 Keystore 加密。 */
@Entity(tableName = "webdav_credentials")
data class WebDavCredentialEntity(
    @PrimaryKey val id: String,
    val serverUrl: String,
    val username: String,
    val passwordCipher: String
)
