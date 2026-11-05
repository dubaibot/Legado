package com.lanyue.reader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lanyue.reader.data.db.entity.BookEntity
import com.lanyue.reader.data.db.entity.CacheFileEntity
import com.lanyue.reader.data.db.entity.DownloadTaskEntity
import com.lanyue.reader.data.db.entity.PlayRecordEntity
import com.lanyue.reader.data.db.entity.ReadRecordEntity
import com.lanyue.reader.data.db.entity.RssFavoriteEntity
import com.lanyue.reader.data.db.entity.RuleSubEntity
import com.lanyue.reader.data.db.entity.SearchHistoryEntity
import com.lanyue.reader.data.db.entity.VideoFavoriteEntity
import com.lanyue.reader.data.db.entity.WebDavCredentialEntity

/** 3.1 书架 DAO。 */
@Dao
interface BookshelfDao {
    @Query("SELECT * FROM books WHERE inBookshelf = 1 ORDER BY sortOrder ASC, lastReadAt DESC")
    suspend fun all(): List<BookEntity>

    @Query("SELECT * FROM books WHERE inBookshelf = 1 AND groupName = :group ORDER BY sortOrder ASC")
    suspend fun byGroup(group: String): List<BookEntity>

    @Query("SELECT DISTINCT groupName FROM books WHERE inBookshelf = 1")
    suspend fun groups(): List<String>

    @Query("SELECT * FROM books WHERE bookUrl = :url")
    suspend fun byUrl(url: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: BookEntity)

    @Query("UPDATE books SET inBookshelf = 0 WHERE bookUrl = :url")
    suspend fun removeFromShelf(url: String)
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM rss_favorites ORDER BY savedAt DESC")
    suspend fun rssFavorites(): List<RssFavoriteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveRss(item: RssFavoriteEntity)

    @Query("DELETE FROM rss_favorites WHERE articleUrl = :url")
    suspend fun removeRss(url: String)

    @Query("SELECT * FROM video_favorites ORDER BY savedAt DESC")
    suspend fun videoFavorites(): List<VideoFavoriteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveVideo(item: VideoFavoriteEntity)

    @Query("DELETE FROM video_favorites WHERE videoUrl = :url")
    suspend fun removeVideo(url: String)
}

/** 4.12 播放/阅读进度。 */
@Dao
interface ProgressDao {
    @Query("SELECT * FROM play_records WHERE videoKey = :key")
    suspend fun playRecord(key: String): PlayRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlay(record: PlayRecordEntity)

    @Query("SELECT * FROM read_records WHERE bookUrl = :key")
    suspend fun readRecord(key: String): ReadRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRead(record: ReadRecordEntity)
}

/** 4.12 rule_subs 规则订阅。 */
@Dao
interface RuleSubDao {
    @Query("SELECT * FROM rule_subs ORDER BY name")
    suspend fun all(): List<RuleSubEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(sub: RuleSubEntity)

    @Query("DELETE FROM rule_subs WHERE id = :id")
    suspend fun delete(id: String)
}

/** 4.11 下载与缓存。 */
@Dao
interface DownloadDao {
    @Query("SELECT * FROM download_tasks ORDER BY createdAt DESC")
    suspend fun tasks(): List<DownloadTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: DownloadTaskEntity)

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun deleteTask(id: String)

    @Query("SELECT * FROM cache_files ORDER BY lastAccessAt DESC")
    suspend fun cacheFiles(): List<CacheFileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCache(file: CacheFileEntity)

    /** 4.11 LRU 清理：锁定文件不参与。 */
    @Query("DELETE FROM cache_files WHERE path IN (SELECT path FROM cache_files WHERE locked = 0 ORDER BY lastAccessAt ASC LIMIT :count)")
    suspend fun evictLru(count: Int)

    @Query("SELECT SUM(sizeBytes) FROM cache_files")
    suspend fun totalSize(): Long?
}

/** 3.8 搜索历史。 */
@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history WHERE sourceType = :type ORDER BY searchedAt DESC LIMIT :limit")
    suspend fun recent(type: String, limit: Int = 20): List<SearchHistoryEntity>

    @Insert
    suspend fun insert(item: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE sourceType = :type")
    suspend fun clear(type: String)
}

@Dao
interface WebDavDao {
    @Query("SELECT * FROM webdav_credentials LIMIT 1")
    suspend fun current(): WebDavCredentialEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cred: WebDavCredentialEntity)
}
