package com.lanyue.reader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.lanyue.reader.data.db.entity.BookSourceEntity
import com.lanyue.reader.data.db.entity.DanmakuBindingEntity
import com.lanyue.reader.data.db.entity.DanmakuSourceEntity
import com.lanyue.reader.data.db.entity.LiveSourceEntity
import com.lanyue.reader.data.db.entity.RssSourceEntity
import com.lanyue.reader.data.db.entity.VideoApiEntity
import com.lanyue.reader.data.db.entity.VideoSiteEntity

/** 4.12 书源 DAO。 */
@Dao
interface BookSourceDao {
    @Query("SELECT * FROM book_sources ORDER BY bookSourceName")
    suspend fun all(): List<BookSourceEntity>

    @Query("SELECT * FROM book_sources WHERE enabled = 1")
    suspend fun enabled(): List<BookSourceEntity>

    /** 3.2 状态 A：仅展示启用且 enabledExplore + exploreUrl 非空的书源。 */
    @Query("SELECT * FROM book_sources WHERE enabled = 1 AND enabledExplore = 1 AND exploreUrl IS NOT NULL AND exploreUrl != ''")
    suspend fun discoverable(): List<BookSourceEntity>

    /** 1.4.2 SEARCHABLE：searchUrl 非空 + ruleSearch 存在。 */
    @Query("SELECT * FROM book_sources WHERE enabled = 1 AND searchDegraded = 0 AND searchUrl IS NOT NULL AND searchUrl != '' AND ruleSearch IS NOT NULL")
    suspend fun searchable(): List<BookSourceEntity>

    @Query("SELECT * FROM book_sources WHERE bookSourceUrl = :url")
    suspend fun byUrl(url: String): BookSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: BookSourceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sources: List<BookSourceEntity>)

    @Query("DELETE FROM book_sources WHERE bookSourceUrl = :url")
    suspend fun delete(url: String)

    @Query("UPDATE book_sources SET enabled = :enabled WHERE bookSourceUrl = :url")
    suspend fun setEnabled(url: String, enabled: Boolean)

    /** 1.4.3 / ADR-018：搜索失败计数 +1，达 3 次标记降级。 */
    @Query("UPDATE book_sources SET searchFailCount = searchFailCount + 1, searchDegraded = CASE WHEN searchFailCount + 1 >= 3 THEN 1 ELSE searchDegraded END WHERE bookSourceUrl = :url")
    suspend fun recordSearchFailure(url: String)

    @Query("UPDATE book_sources SET searchFailCount = 0 WHERE bookSourceUrl = :url")
    suspend fun resetSearchFailure(url: String)

    @Query("UPDATE book_sources SET searchDegraded = :degraded WHERE bookSourceUrl = :url")
    suspend fun setSearchDegraded(url: String, degraded: Boolean)
}

/** 4.12 / 1.4 订阅源 DAO。 */
@Dao
interface RssSourceDao {
    @Query("SELECT * FROM rss_sources ORDER BY customOrder ASC, sourceName ASC")
    suspend fun all(): List<RssSourceEntity>

    @Query("SELECT * FROM rss_sources WHERE enabled = 1 ORDER BY customOrder ASC")
    suspend fun enabled(): List<RssSourceEntity>

    /** 3.3 搜索入口按 SEARCHABLE 动态显示：searchUrl 非空且未降级。 */
    @Query("SELECT * FROM rss_sources WHERE enabled = 1 AND searchDegraded = 0 AND searchUrl IS NOT NULL AND searchUrl != ''")
    suspend fun searchable(): List<RssSourceEntity>

    /** 1.4.2 DISCOVERABLE：sortUrl 非空。 */
    @Query("SELECT * FROM rss_sources WHERE enabled = 1 AND sortUrl IS NOT NULL AND sortUrl != ''")
    suspend fun discoverable(): List<RssSourceEntity>

    @Query("SELECT * FROM rss_sources WHERE sourceUrl = :url")
    suspend fun byUrl(url: String): RssSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: RssSourceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sources: List<RssSourceEntity>)

    @Query("DELETE FROM rss_sources WHERE sourceUrl = :url")
    suspend fun delete(url: String)

    @Query("UPDATE rss_sources SET searchFailCount = searchFailCount + 1, searchDegraded = CASE WHEN searchFailCount + 1 >= 3 THEN 1 ELSE searchDegraded END WHERE sourceUrl = :url")
    suspend fun recordSearchFailure(url: String)
}

/** 4.12 / 7.3 TVBox 接口与站点 DAO。 */
@Dao
interface VideoDao {
    @Query("SELECT * FROM video_apis ORDER BY name")
    suspend fun allApis(): List<VideoApiEntity>

    @Query("SELECT * FROM video_apis WHERE url = :url")
    suspend fun api(url: String): VideoApiEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertApi(api: VideoApiEntity)

    @Query("SELECT * FROM video_sites WHERE enabled = 1 ORDER BY name")
    suspend fun enabledSites(): List<VideoSiteEntity>

    @Query("SELECT * FROM video_sites WHERE configUrl = :configUrl ORDER BY name")
    suspend fun sitesOf(configUrl: String): List<VideoSiteEntity>

    @Query("SELECT * FROM video_sites WHERE configUrl = :configUrl AND siteKey = :siteKey")
    suspend fun site(configUrl: String, siteKey: String): VideoSiteEntity?

    /** 仅 searchable 站点参与搜索聚合（3.4 / 3.8）。 */
    @Query("SELECT * FROM video_sites WHERE enabled = 1 AND searchable = 1 AND unsupported = 0")
    suspend fun searchableSites(): List<VideoSiteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSites(sites: List<VideoSiteEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSite(site: VideoSiteEntity)

    /**
     * 4.12 / 附录 B 用例 21：删除接口配置时级联删除其站点。
     * 用事务保证配置与站点不会出现半删状态。
     */
    @Transaction
    suspend fun deleteConfigCascade(configUrl: String) {
        deleteSitesOf(configUrl)
        deleteApi(configUrl)
    }

    @Query("DELETE FROM video_sites WHERE configUrl = :configUrl")
    suspend fun deleteSitesOf(configUrl: String)

    @Query("DELETE FROM video_apis WHERE url = :configUrl")
    suspend fun deleteApi(configUrl: String)
}

/** 4.12 live_sources：一期只写不读 UI。 */
@Dao
interface LiveSourceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sources: List<LiveSourceEntity>)

    @Query("SELECT * FROM live_sources WHERE apiUrl = :apiUrl")
    suspend fun ofApi(apiUrl: String): List<LiveSourceEntity>

    @Query("SELECT COUNT(*) FROM live_sources")
    suspend fun count(): Int
}

/** 4.10 / 4.12 弹幕源与匹配记忆 DAO。 */
@Dao
interface DanmakuDao {
    @Query("SELECT * FROM danmaku_sources ORDER BY sortOrder ASC")
    suspend fun all(): List<DanmakuSourceEntity>

    @Query("SELECT * FROM danmaku_sources WHERE enabled = 1 ORDER BY sortOrder ASC")
    suspend fun enabledOrdered(): List<DanmakuSourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: DanmakuSourceEntity)

    @Query("DELETE FROM danmaku_sources WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE danmaku_sources SET verified = :verified WHERE id = :id")
    suspend fun setVerified(id: String, verified: Boolean)

    /** 4.10.5 匹配记忆：按 视频标识 + 弹幕源 查。 */
    @Query("SELECT * FROM danmaku_bindings WHERE videoKey = :videoKey AND danmakuSourceId = :sourceId")
    suspend fun binding(videoKey: String, sourceId: String): DanmakuBindingEntity?

    @Query("SELECT * FROM danmaku_bindings WHERE videoKey = :videoKey")
    suspend fun bindingsOf(videoKey: String): List<DanmakuBindingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBinding(binding: DanmakuBindingEntity)

    /** 设置 → 弹幕 → 清除匹配记录。 */
    @Query("DELETE FROM danmaku_bindings")
    suspend fun clearBindings()

    @Query("DELETE FROM danmaku_bindings WHERE videoKey = :videoKey")
    suspend fun clearBinding(videoKey: String)
}
