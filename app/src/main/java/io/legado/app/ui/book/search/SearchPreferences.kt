package io.legado.app.ui.book.search

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.SearchBook
import io.legado.app.utils.defaultSharedPreferences

data class SearchPreferences(
    val precision: Boolean = false,
    val multiGroup: Boolean = false,
    val grid: Boolean = false,
    val columns: Int = 3,
    val hideNoCover: Boolean = false,
    val status: Int = 0,
    val minWords: Int = 0,
) {
    fun save(context: Context) {
        context.defaultSharedPreferences.edit()
            .putBoolean(PreferKey.precisionSearch, precision)
            .putBoolean("bookSearch.multiGroup", multiGroup)
            .putBoolean("bookSearch.grid", grid)
            .putInt("bookSearch.columns", columns)
            .putBoolean("bookSearch.hideNoCover", hideNoCover)
            .putInt("bookSearch.status", status)
            .putInt("bookSearch.minWords", minWords).apply()
    }

    fun accepts(book: SearchBook): Boolean {
        if (hideNoCover && book.coverUrl.isNullOrBlank()) return false
        // Sources expose completion markers through kind and latest chapter.
        val metadata = "${book.kind.orEmpty()} ${book.latestChapterTitle.orEmpty()}"
        val ongoing = Regex("连载|未完结|未完本|未完成").containsMatchIn(metadata)
        val completed = !ongoing && Regex("完结|完本|已完成|全本").containsMatchIn(metadata)
        if (status == 1 && !ongoing || status == 2 && !completed) return false
        if (minWords == 0) return true
        val text = book.wordCount.orEmpty().replace(",", "").replace("，", "")
        val amounts = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*([亿万千]?)").findAll(text).toList()
        val units = amounts.filter { it.groupValues[2].isNotEmpty() }
        val words = if (units.isNotEmpty()) units.sumOf {
            val factor = when (it.groupValues[2]) { "亿" -> 100000000; "万" -> 10000; else -> 1000 }
            it.groupValues[1].toDouble() * factor
        } else amounts.firstOrNull()?.groupValues?.get(1)?.toDoubleOrNull() ?: return false
        return words >= minWords
    }

    companion object {
        fun read(context: Context): SearchPreferences = context.defaultSharedPreferences.let {
            SearchPreferences(
                precision = it.getBoolean(PreferKey.precisionSearch, false),
                multiGroup = it.getBoolean("bookSearch.multiGroup", false),
                grid = it.getBoolean("bookSearch.grid", false),
                columns = it.getInt("bookSearch.columns", 3).coerceIn(2, 5),
                hideNoCover = it.getBoolean("bookSearch.hideNoCover", false),
                status = it.getInt("bookSearch.status", 0).coerceIn(0, 2),
                minWords = it.getInt("bookSearch.minWords", 0).coerceAtLeast(0),
            )
        }
    }
}
