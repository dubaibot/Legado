package com.lanyue.reader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.lanyue.reader.data.db.entity.BookEntity
import com.lanyue.reader.data.db.entity.BookSourceEntity
import com.lanyue.reader.data.db.entity.CacheFileEntity
import com.lanyue.reader.data.db.entity.DanmakuBindingEntity
import com.lanyue.reader.data.db.entity.DanmakuSourceEntity
import com.lanyue.reader.data.db.entity.DownloadTaskEntity
import com.lanyue.reader.data.db.entity.LiveSourceEntity
import com.lanyue.reader.data.db.entity.PlayRecordEntity
import com.lanyue.reader.data.db.entity.ReadRecordEntity
import com.lanyue.reader.data.db.entity.RssFavoriteEntity
import com.lanyue.reader.data.db.entity.RssSourceEntity
import com.lanyue.reader.data.db.entity.RuleSubEntity
import com.lanyue.reader.data.db.entity.SearchHistoryEntity
import com.lanyue.reader.data.db.entity.VideoApiEntity
import com.lanyue.reader.data.db.entity.VideoFavoriteEntity
import com.lanyue.reader.data.db.entity.VideoSiteEntity
import com.lanyue.reader.data.db.entity.WebDavCredentialEntity

/**
 * 4.12 本地数据模型（Room）总装。
 * 全部表按 4.12 表清单一一对应，不多建、不少建。
 */
@Database(
    entities = [
        BookSourceEntity::class,
        RssSourceEntity::class,
        VideoApiEntity::class,
        VideoSiteEntity::class,
        LiveSourceEntity::class,
        DanmakuSourceEntity::class,
        DanmakuBindingEntity::class,
        BookEntity::class,
        RssFavoriteEntity::class,
        VideoFavoriteEntity::class,
        PlayRecordEntity::class,
        ReadRecordEntity::class,
        RuleSubEntity::class,
        DownloadTaskEntity::class,
        CacheFileEntity::class,
        SearchHistoryEntity::class,
        WebDavCredentialEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class LanyueDatabase : RoomDatabase() {

    abstract fun bookSourceDao(): BookSourceDao
    abstract fun rssSourceDao(): RssSourceDao
    abstract fun videoDao(): VideoDao
    abstract fun liveSourceDao(): LiveSourceDao
    abstract fun danmakuDao(): DanmakuDao
    abstract fun bookshelfDao(): BookshelfDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun progressDao(): ProgressDao
    abstract fun ruleSubDao(): RuleSubDao
    abstract fun downloadDao(): DownloadDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun webDavDao(): WebDavDao

    companion object {
        fun build(context: Context): LanyueDatabase =
            Room.databaseBuilder(context, LanyueDatabase::class.java, "lanyue.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
