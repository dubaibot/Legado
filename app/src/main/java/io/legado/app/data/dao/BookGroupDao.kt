package io.legado.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.legado.app.constant.BookType
import io.legado.app.data.entities.BookGroup
import kotlinx.coroutines.flow.Flow

@Dao
interface BookGroupDao {

    @Query("select * from book_groups where groupId = :id")
    fun getByID(id: Long): BookGroup?

    @Query("select * from book_groups where groupName = :groupName")
    fun getByName(groupName: String): BookGroup?

    @Query("SELECT * FROM book_groups ORDER BY `order`")
    fun flowAll(): Flow<List<BookGroup>>

    @get:Query(
        """
        with const as (SELECT coalesce(sum(groupId), 0) sumGroupId FROM book_groups where groupId > 0)
        SELECT book_groups.* FROM book_groups join const 
        where show > 0 
        and (
            (groupId >= 0  and exists (select 1 from books where `group` & book_groups.groupId > 0))
              or groupId in (-1, -3, -6, -7, -8, -9)
            or (groupId = -2 and exists (select 1 from books where type & ${BookType.local} > 0))
            or (groupId = -11 and exists (select 1 from books where type & ${BookType.updateError} > 0))
            or (groupId = -4 
                and exists (
                    select 1 from books 
                    where type & ${BookType.audio} = 0
                    and type & ${BookType.video} = 0
                    and type & ${BookType.local} = 0
                    and const.sumGroupId & `group` = 0
                )
            )
            or (groupId = -5
                and exists (
                    select 1 from books 
                    where type & ${BookType.audio} = 0
                    and type & ${BookType.video} = 0
                    and type & ${BookType.local} > 0
                    and const.sumGroupId & `group` = 0
                )
            )
        )
        ORDER BY `order`"""
    )
    val show: LiveData<List<BookGroup>>

    @Query("SELECT * FROM book_groups where groupId >= 0 ORDER BY `order`")
    fun flowSelect(): Flow<List<BookGroup>>

    @get:Query("SELECT coalesce(sum(groupId), 0) FROM book_groups where groupId >= 0")
    val idsSum: Long

    @get:Query("SELECT MAX(`order`) FROM book_groups where groupId >= 0")
    val maxOrder: Int

    @get:Query("SELECT * FROM book_groups ORDER BY `order`")
    val all: List<BookGroup>

    @get:Query("select count(*) < 64 from book_groups where groupId >= 0 or groupId == ${Long.MIN_VALUE}")
    val canAddGroup: Boolean

    @Query("update book_groups set show = 1 where groupId = :groupId")
    fun enableGroup(groupId: Long)

    @Query("select groupName from book_groups where groupId > 0 and (groupId & :id) > 0")
    fun getGroupNames(id: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg bookGroup: BookGroup)

    @Update
    fun update(vararg bookGroup: BookGroup)

    @Query("update book_groups set show = 0 where groupId in (-2, -4, -5, -11, -100)")
    fun hideLegacyDefaults()

    @Transaction
    fun ensureDefaults(restoring: Boolean = false) {
        val migrate = restoring || getByID(BookGroup.IdNovel) == null ||
            getByID(BookGroup.IdManga) == null || getByID(BookGroup.IdOther) == null
        BookGroup.defaults().forEach { default ->
            val existing = getByID(default.groupId)
            if (existing == null) insert(default)
            else if (migrate) update(existing.copy(order = default.order))
        }
        if (migrate) hideLegacyDefaults()
    }

    @Delete
    fun delete(vararg bookGroup: BookGroup)

    fun isInRules(id: Long): Boolean {
        if (id < 0) {
            return true
        }
        return id and (id - 1) == 0L
    }

    fun getUnusedId(): Long {
        var id = 1L
        val idsSum = idsSum
        while (id and idsSum != 0L) {
            id = id.shl(1)
        }
        return id
    }
    @Query("delete from book_groups")
    fun deleteAll()

    @get:Query("select count(*) from book_groups")
    val count: Int

}
