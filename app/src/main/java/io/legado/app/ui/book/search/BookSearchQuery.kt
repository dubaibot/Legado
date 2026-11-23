package io.legado.app.ui.book.search

/**
 * 搜索关键字拆分为书名与可选作者。
 * 仅在存在显式分隔符（`书名|作者` 或 `作者：`）时才提取作者，
 * 否则整串作为书名，避免把英文书名中的空格误判为作者分隔。
 */
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
            if (text.isEmpty()) return BookSearchQuery(text)

            // 显式分隔符：书名|作者
            val pipeIndex = text.indexOf('|')
            if (pipeIndex > 0) {
                val title = text.substring(0, pipeIndex).trim()
                val author = text.substring(pipeIndex + 1).trim()
                if (title.isNotEmpty() && author.isNotEmpty()) {
                    return BookSearchQuery(title, author)
                }
            }

            // 显式作者标记：书名 作者：作者名 / 书名 作者:作者名
            val authorMarker = Regex("\\s*作者[：:]").find(text)
            if (authorMarker != null && authorMarker.range.first > 0) {
                val title = text.substring(0, authorMarker.range.first).trim()
                val author = text.substring(authorMarker.range.last + 1).trim()
                if (title.isNotEmpty() && author.isNotEmpty()) {
                    return BookSearchQuery(title, author)
                }
            }

            // 未使用显式分隔符时整串视为书名，保留书名中的空格
            return BookSearchQuery(text)
        }

        private fun cleanAuthor(value: String): String = value.trim()
            .replace(Regex("^(作者[：:]?\\s*)"), "").replace(Regex("\\s*著$"), "").trim()
    }
}
