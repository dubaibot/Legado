package io.legado.app.ui.book.import.local

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern.archiveFileRegex
import io.legado.app.constant.AppPattern.bookFileRegex
import io.legado.app.constant.PreferKey
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.model.localBook.LocalMediaDirectory
import io.legado.app.model.localBook.LocalMediaNames
import io.legado.app.utils.ArchiveUtils
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.FileDoc
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.delete
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.list
import io.legado.app.utils.mapParallel
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import java.util.Collections

class ImportBookViewModel(application: Application) : BaseViewModel(application) {
    private data class DraftState(
        val book: Book,
        val mediaUris: List<String>,
        val mediaNames: List<String>,
        val archiveEntry: Boolean,
        val kind: LocalMediaNames.Kind?
    )

    data class Draft(
        val book: Book,
        val media: List<FileDoc> = emptyList(),
        val archiveEntry: Boolean = false
    ) {
        var kind: LocalMediaNames.Kind? = media.firstOrNull()?.let { LocalMediaNames.kind(it.name) }
    }

    val drafts = arrayListOf<Draft>()
    var preparing = false
    var saving = false
    var draftIndex = 0

    fun prepareImport(files: List<FileDoc>, onReady: () -> Unit) {
        if (preparing || saving || drafts.isNotEmpty()) return
        preparing = true
        execute {
            val result = arrayListOf<Draft>()
            val seenUris = hashSetOf<String>()
            files.forEach { file ->
                if (!seenUris.add(file.toString())) return@forEach
                if (appDb.bookDao.has(file.toString())) return@forEach
                when {
                    file.isDir -> {
                        val media = LocalMediaDirectory.files(file)
                        require(media.isNotEmpty()) { "${file.name}：当前目录没有支持的图片或视频" }
                        val book = Book(bookUrl = file.toString(), name = file.name,
                            originName = file.name, type = BookType.local or BookType.image)
                        LocalMediaDirectory.mark(book)
                        result.add(Draft(book, media))
                    }
                    ArchiveUtils.isArchive(file.name) -> {
                        val entries = ArchiveUtils.deCompress(file) { it.matches(bookFileRegex) }
                        require(entries.isNotEmpty()) { "${file.name}：压缩包没有支持的书籍" }
                        entries.forEach { entry ->
                            result.add(Draft(LocalBook.prepareImport(android.net.Uri.fromFile(entry)), archiveEntry = true))
                        }
                    }
                    else -> result.add(Draft(LocalBook.prepareImport(file.uri)))
                }
            }
            drafts.addAll(result)
        }.onSuccess {
            if (drafts.isEmpty()) context.toastOnUi("所选 URI 已在书架中，已跳过")
            else onReady()
        }.onError {
            context.toastOnUi("准备导入失败：${it.localizedMessage}")
        }.onFinally { preparing = false }
    }

    fun confirmImport(onSuccess: () -> Unit, onError: () -> Unit) {
        if (saving || drafts.isEmpty()) return
        saving = true
        execute {
            val books = arrayListOf<Book>()
            val chapters = arrayListOf<BookChapter>()
            val names = hashSetOf<Pair<String, String>>()
            val pendingFiles = arrayListOf<Pair<java.io.File, () -> Unit>>()
            val createdFiles = arrayListOf<java.io.File>()
            drafts.forEach { draft ->
                val book = draft.book.copy()
                require(book.name.isNotBlank()) { "书名不能为空" }
                require(names.add(book.name to book.author)) { "待导入条目重名：${book.name}，请修改名称或作者" }
                require(appDb.bookDao.getBook(book.name, book.author) == null) {
                    "书架已有同名同作者条目：${book.name}，请修改名称或作者"
                }
                if (draft.media.isNotEmpty()) {
                    val kind = requireNotNull(draft.kind)
                    book.type = BookType.local or if (kind == LocalMediaNames.Kind.IMAGE) BookType.image else BookType.video
                    val matching = LocalMediaDirectory.files(draft.book, kind)
                    require(matching.isNotEmpty()) { "${book.name}：所选类型已无可用文件" }
                    val toc = LocalMediaDirectory.chapters(book, matching)
                    book.totalChapterNum = toc.size
                    book.durChapterTitle = toc.first().title
                    book.latestChapterTitle = toc.last().title
                    if (kind == LocalMediaNames.Kind.IMAGE && book.customCoverUrl.isNullOrBlank()) {
                        book.coverUrl = matching.first().uri.toString()
                    }
                    chapters.addAll(toc)
                }
                if (draft.archiveEntry) {
                    val uri = android.net.Uri.parse(book.bookUrl)
                    val source = java.io.File(requireNotNull(uri.path))
                    require(source.isFile && source.canRead()) { "无法读取书籍文件：${book.originName}" }
                    book.type = BookType.local or BookType.text
                }
                book.customCoverUrl?.let { cover ->
                    val uri = android.net.Uri.parse(cover)
                    if (uri.scheme != "http" && uri.scheme != "https") {
                        val input = if (uri.scheme == "content") context.contentResolver.openInputStream(uri)
                        else java.io.FileInputStream(uri.path ?: cover)
                        requireNotNull(input) { "无法读取封面" }.use { it.read() }
                    }
                }
                books.add(book)
            }
            try {
                drafts.forEachIndexed { index, draft ->
                    val book = books[index]
                    if (draft.archiveEntry) {
                        val source = java.io.File(requireNotNull(android.net.Uri.parse(book.bookUrl).path))
                        val target = java.io.File(context.filesDir, "${java.util.UUID.randomUUID()}-${book.originName}")
                        pendingFiles.add(target to {
                            createdFiles.add(target)
                            val saved = LocalBook.saveBookFile(source.inputStream(), target.name)
                            if (saved.scheme == "file") {
                                val savedFile = java.io.File(requireNotNull(saved.path))
                                if (savedFile != target) createdFiles.add(savedFile)
                            }
                            book.bookUrl = FileDoc.fromUri(saved, false).toString()
                        })
                    }
                    book.customCoverUrl?.let { cover ->
                        val uri = android.net.Uri.parse(cover)
                        if (uri.scheme != "http" && uri.scheme != "https") {
                            val target = java.io.File(context.filesDir, "import-covers/${java.util.UUID.randomUUID()}")
                            pendingFiles.add(target to {
                                createdFiles.add(target)
                                target.parentFile?.mkdirs()
                                val input = if (uri.scheme == "content") context.contentResolver.openInputStream(uri)
                                else java.io.FileInputStream(uri.path ?: cover)
                                requireNotNull(input) { "无法读取封面" }.use { stream ->
                                    target.outputStream().use { stream.copyTo(it) }
                                }
                                book.customCoverUrl = target.absolutePath
                            })
                        }
                    }
                }
                appDb.runInTransaction {
                    val seenNames = hashSetOf<Pair<String, String>>()
                    books.forEach { book ->
                        require(appDb.bookDao.getBook(book.name, book.author) == null &&
                            seenNames.add(book.name to book.author)) { "书名冲突：${book.name}，请重命名" }
                        require(!appDb.bookDao.has(book.bookUrl)) { "该 URI 已导入：${book.name}" }
                    }
                    val order = appDb.bookDao.minOrder
                    books.forEachIndexed { index, book -> book.order = order - index - 1 }
                    pendingFiles.forEach { (_, copy) -> copy() }
                    appDb.bookDao.insert(*books.toTypedArray())
                    if (chapters.isNotEmpty()) appDb.bookChapterDao.insert(*chapters.toTypedArray())
                }
            } catch (e: Exception) {
                createdFiles.forEach { it.delete() }
                throw e
            }
        }.onSuccess {
            drafts.clear()
            context.toastOnUi("添加书架成功")
            onSuccess()
        }.onError {
            context.toastOnUi("导入失败：${it.localizedMessage}")
            onError()
        }.onFinally { saving = false }
    }

    fun saveDraftState(outState: android.os.Bundle) {
        val states = drafts.map { draft ->
            DraftState(draft.book, draft.media.map { it.uri.toString() }, draft.media.map { it.name }, draft.archiveEntry, draft.kind)
        }
        outState.putString("localImportDrafts", io.legado.app.utils.GSON.toJson(states))
        outState.putInt("localImportDraftIndex", draftIndex)
    }

    fun restoreDraftState(state: android.os.Bundle?) {
        val json = state?.getString("localImportDrafts") ?: return
        val restored = io.legado.app.utils.GSON.fromJsonArray<DraftState>(json).getOrNull() ?: return
        drafts.clear()
        drafts.addAll(restored.map { state ->
            Draft(state.book, state.mediaUris.mapIndexed { index, uri ->
                val doc = FileDoc.fromUri(android.net.Uri.parse(uri), false)
                doc.copy(name = state.mediaNames.getOrElse(index) { doc.name })
            }, state.archiveEntry).also { it.kind = state.kind }
        })
        draftIndex = state.getInt("localImportDraftIndex").coerceIn(0, (drafts.size - 1).coerceAtLeast(0))
    }

    fun pauseDraftForCoverSelection() {
        // Activity-result callbacks recreate the activity; persist the in-memory draft first.
        pendingCoverSelection = true
    }

    var pendingCoverSelection = false
    var rootDoc: FileDoc? = null
    val subDocs = arrayListOf<FileDoc>()
    var sort = context.getPrefInt(PreferKey.localBookImportSort)
    var dataCallback: DataCallback? = null
    var dataFlowStart: (() -> Unit)? = null
    var filterKey: String? = null
    val dataFlow = callbackFlow<List<ImportBook>> {

        val list = Collections.synchronizedList(ArrayList<ImportBook>())

        dataCallback = object : DataCallback {

            override fun setItems(fileDocs: List<FileDoc>) {
                list.clear()
                fileDocs.mapTo(list) {
                    ImportBook(it)
                }
                trySend(list)
            }

            override fun addItems(fileDocs: List<FileDoc>) {
                fileDocs.mapTo(list) {
                    ImportBook(it)
                }
                trySend(list)
            }

            override fun clear() {
                list.clear()
                trySend(emptyList())
            }

            override fun upAdapter() {
                trySend(list)
            }
        }

        withContext(Main) {
            dataFlowStart?.invoke()
        }

        awaitClose {
            dataCallback = null
        }

    }.map { docList ->
        val docList = docList.toList()
        val filterKey = filterKey
        val skipFilter = filterKey.isNullOrBlank()
        val comparator = when (sort) {
            2 -> compareBy<ImportBook>({ !it.isDir }, { -it.lastModified })
            1 -> compareBy({ !it.isDir }, { -it.size })
            else -> compareBy { !it.isDir }
        } then compareBy(AlphanumComparator) { it.name }
        docList.asSequence().filter {
            skipFilter || it.name.contains(filterKey)
        }.sortedWith(comparator).toList()
    }.flowOn(IO)

    fun deleteDoc(bookList: HashSet<ImportBook>, finally: () -> Unit) {
        execute {
            bookList.forEach {
                it.file.delete()
            }
        }.onFinally {
            finally.invoke()
        }
    }

    fun loadDoc(fileDoc: FileDoc) {
        execute {
            val docList = fileDoc.list { item ->
                when {
                    item.name.startsWith(".") -> false
                    item.isDir -> true
                    else -> item.name.matches(bookFileRegex) || item.name.matches(archiveFileRegex)
                }
            }
            dataCallback?.setItems(docList!!)
        }.onError {
            context.toastOnUi("获取文件列表出错\n${it.localizedMessage}")
        }
    }

    suspend fun scanDoc(fileDoc: FileDoc) {
        dataCallback?.clear()
        val channel = Channel<FileDoc>(UNLIMITED)
        var n = 1
        channel.trySend(fileDoc)
        val list = arrayListOf<FileDoc>()
        channel.consumeAsFlow()
            .mapParallel(16) { fileDoc ->
                fileDoc.list()!!
            }.onEach { fileDocs ->
                n--
                list.clear()
                fileDocs.forEach {
                    if (it.isDir) {
                        n++
                        channel.trySend(it)
                    } else if (it.name.matches(bookFileRegex)
                        || it.name.matches(archiveFileRegex)
                    ) {
                        list.add(it)
                    }
                }
                dataCallback?.addItems(list)
            }.takeWhile {
                n > 0
            }.catch {
                context.toastOnUi("扫描文件夹出错\n${it.localizedMessage}")
            }.collect()
    }

    fun updateCallBackFlow(filterKey: String?) {
        this.filterKey = filterKey
        dataCallback?.upAdapter()
    }

    interface DataCallback {

        fun setItems(fileDocs: List<FileDoc>)

        fun addItems(fileDocs: List<FileDoc>)

        fun clear()

        fun upAdapter()

    }

}
