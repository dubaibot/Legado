package io.legado.app.model.webBook

import io.legado.app.data.entities.BookChapter

/** Persist route headers in the existing TOC representation, after ordering and deduplication. */
object BookPlaylineParser {
    fun addVolumes(chapters: ArrayList<BookChapter>) {
        if (chapters.any { it.isVolume } || chapters.none { !it.getVariable("playline").isNullOrBlank() }) return
        val grouped = groupPlaylines(chapters) { it.getVariable("playline") }
        val result = ArrayList<BookChapter>()
        grouped.forEach { (name, episodes) ->
            val first = episodes.first()
            result.add(BookChapter(
                bookUrl = first.bookUrl,
                baseUrl = first.baseUrl,
                url = "legado-playline:" + java.net.URLEncoder.encode(name, "UTF-8"),
                title = name,
                isVolume = true
            ))
            result.addAll(episodes)
        }
        chapters.clear()
        chapters.addAll(result)
    }
}

internal fun <T> groupPlaylines(items: List<T>, name: (T) -> String?): Map<String, List<T>> =
    items.groupBy { name(it)?.trim().orEmpty().ifBlank { "线路1" } }
