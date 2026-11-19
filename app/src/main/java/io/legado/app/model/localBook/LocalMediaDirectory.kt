package io.legado.app.model.localBook

import androidx.core.net.toUri
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.isLocal
import io.legado.app.utils.FileDoc
import io.legado.app.utils.GSON
import io.legado.app.utils.list

object LocalMediaDirectory {
    private const val MARKER = "localMediaDirectory"

    fun isDirectory(book: Book): Boolean = book.isLocal && book.getVariable(MARKER) == "1"

    fun mark(book: Book) {
        // Drafts must not call putVariable: it also updates the persisted big-variable store.
        book.variableMap[MARKER] = "1"
        book.variable = GSON.toJson(book.variableMap)
    }

    fun files(directory: FileDoc): List<FileDoc> = requireNotNull(directory.list()) {
        "无法读取目录，请重新授权"
    }.filter { !it.isDir && LocalMediaNames.kind(it.name) != null }
        .sortedWith(compareBy(LocalMediaNames.comparator) { it.name })

    fun files(book: Book, kind: LocalMediaNames.Kind? = null): List<FileDoc> {
        val uri = book.bookUrl.toUri()
        // Keep the document ID of nested SAF directories; fromTreeUri resolves the tree root.
        val directory = FileDoc(book.originName, true, 0, 0, uri)
        val selectedKind = kind ?: if (book.type and BookType.video != 0) LocalMediaNames.Kind.VIDEO else LocalMediaNames.Kind.IMAGE
        return files(directory).filter { LocalMediaNames.kind(it.name) == selectedKind }
    }

    fun chapters(book: Book, files: List<FileDoc> = files(book)): ArrayList<BookChapter> =
        files.mapIndexedTo(arrayListOf()) { index, file ->
            BookChapter(
                url = file.uri.toString(), title = file.name, bookUrl = book.bookUrl,
                baseUrl = book.bookUrl, index = index
            )
        }
}
