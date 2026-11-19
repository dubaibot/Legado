package io.legado.app.ui.book.search

/** A trailing space-separated author is independent of exact title matching. */
data class BookSearchQuery(val title: String, val author: String = "") {
    fun matches(name: String, candidateAuthor: String, exact: Boolean): Boolean {
        val authorMatches = author.isEmpty() || cleanAuthor(candidateAuthor) == cleanAuthor(author)
        val titleMatches = if (exact) name == title else
            name.contains(title, ignoreCase = true) || (author.isEmpty() && candidateAuthor.contains(title, ignoreCase = true))
        return authorMatches && titleMatches
    }

    companion object {
        fun parse(raw: String): BookSearchQuery {
            val text = raw.trim()
            val split = Regex("\\s+").findAll(text).lastOrNull() ?: return BookSearchQuery(text)
            return BookSearchQuery(text.substring(0, split.range.first), text.substring(split.range.last + 1))
        }

        private fun cleanAuthor(value: String): String = value.trim()
            .replace(Regex("^(作者[：:]?\\s*)"), "").replace(Regex("\\s*著$"), "").trim()
    }
}
