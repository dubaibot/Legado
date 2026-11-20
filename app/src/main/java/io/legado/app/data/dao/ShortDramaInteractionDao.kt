package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.ShortDramaInteraction

@Dao
interface ShortDramaInteractionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(interaction: ShortDramaInteraction)

    @Query("select * from shortDramaInteractions order by timestamp desc limit :limit")
    fun latest(limit: Int): List<ShortDramaInteraction>

    @Query("select * from shortDramaInteractions where timestamp >= :since order by timestamp desc")
    fun since(since: Long): List<ShortDramaInteraction>

    @Query("select * from shortDramaInteractions where seriesId = :seriesId order by timestamp desc limit 1")
    fun latestOfSeries(seriesId: String): ShortDramaInteraction?

    @Query("select * from shortDramaInteractions where action = 'progress' and seriesId = :seriesId order by timestamp desc limit 1")
    fun latestProgress(seriesId: String): ShortDramaInteraction?

    @Query("select distinct seriesId from shortDramaInteractions order by timestamp desc limit :limit")
    fun recentSeriesIds(limit: Int): List<String>

    @Query("delete from shortDramaInteractions where seriesId = :seriesId")
    fun deleteSeries(seriesId: String)

    @Query(
        "delete from shortDramaInteractions where seriesId = :seriesId and action = :action " +
            "and timestamp = (select max(timestamp) from shortDramaInteractions " +
            "where seriesId = :seriesId and action = :action)"
    )
    fun deleteLatestOfSeriesAndAction(seriesId: String, action: String)

    @Query("delete from shortDramaInteractions")
    fun clearAll()

    @Query("select count(*) from shortDramaInteractions")
    fun count(): Int

    @Query(
        "delete from shortDramaInteractions where rowid not in (" +
            "select rowid from shortDramaInteractions order by timestamp desc limit :keep)"
    )
    fun trimTo(keep: Int)
}