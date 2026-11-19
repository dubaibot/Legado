package io.legado.app.data.entities

import io.legado.app.constant.BookType

/** Keep SQL and in-memory classification identical, including mixed media flags. */
object BookGroupRules {
    const val typeGroupSql = "CASE " +
        "WHEN (type & ${BookType.video}) > 0 THEN ${BookGroup.IdVideo} " +
        "WHEN (type & ${BookType.audio}) > 0 THEN ${BookGroup.IdAudio} " +
        "WHEN (type & ${BookType.image}) > 0 THEN ${BookGroup.IdManga} " +
        "WHEN (type & ${BookType.text + BookType.webFile + BookType.local}) > 0 THEN ${BookGroup.IdNovel} " +
        "ELSE ${BookGroup.IdOther} END"

    fun typeGroup(type: Int): Long = when {
        type and BookType.video != 0 -> BookGroup.IdVideo
        type and BookType.audio != 0 -> BookGroup.IdAudio
        type and BookType.image != 0 -> BookGroup.IdManga
        type and (BookType.text or BookType.webFile or BookType.local) != 0 -> BookGroup.IdNovel
        else -> BookGroup.IdOther
    }

    fun contains(groupId: Long, type: Int, bookGroup: Long, userGroupMask: Long = 0): Boolean {
        if (type and BookType.notShelf != 0) return false
        return when (groupId) {
            BookGroup.IdAll -> true
            BookGroup.IdNovel, BookGroup.IdAudio, BookGroup.IdManga,
            BookGroup.IdVideo, BookGroup.IdOther -> typeGroup(type) == groupId
            BookGroup.IdLocal -> type and BookType.local != 0
            BookGroup.IdError -> type and BookType.updateError != 0
            BookGroup.IdNetNone -> type and (BookType.audio or BookType.video or BookType.local) == 0 &&
                bookGroup and userGroupMask == 0L
            BookGroup.IdLocalNone -> type and BookType.local != 0 && bookGroup and userGroupMask == 0L
            else -> groupId > 0 && bookGroup and groupId != 0L
        }
    }
}
