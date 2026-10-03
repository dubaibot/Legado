package io.legado.app.ui.book.explore

import android.app.Application
import android.content.Intent
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.BuildConfig
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.help.book.addType
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.source.exploreKinds
import io.legado.app.model.ReadBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.mapLatest
import java.util.concurrent.ConcurrentHashMap

/**
 * 大分类:占满一栏的url分类,与其辖区内的细分(不占满一栏的url分类)
 */
data class BigKind(
    val kind: ExploreKind,
    val subKinds: List<ExploreKind>
)


@OptIn(ExperimentalCoroutinesApi::class)
class ExploreShowViewModel(application: Application) : BaseViewModel(application) {
    val bookshelf: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val upAdapterLiveData = MutableLiveData<String>()
    val booksData = MutableLiveData<List<SearchBook>>()
    val addBooksData = MutableLiveData<List<SearchBook>>()
    val errorLiveData = MutableLiveData<String>()
    val errorTopLiveData = MutableLiveData<String>()
    val pageLiveData = MutableLiveData<Int>()
    val sourceData = MutableLiveData<BookSource?>()

    /** 大分类栏内容(白名单过滤后),两栏联动均由该数据与currentBig/currentSub驱动 */
    val bigKindsData = MutableLiveData<List<BigKind>>()
    private var bookSource: BookSource? = null
    var exploreUrl: String? = null
        private set
    private var page = 1
    private var books = linkedSetOf<SearchBook>()

    /** 解析出的有效url分类,按书源原顺序 */
    private var allKinds: List<ExploreKind> = emptyList()

    /** 按占满一栏规则自动划分的大分类树 */
    private var bigTree: List<BigKind> = emptyList()

    /** 用户勾选白名单过滤后的展示列表,空存储时等于bigTree */
    private var displayBigKinds: List<BigKind> = emptyList()

    /** 当前大分类,细分栏高亮项为currentSub,null表示停在大分类本级 */
    var currentBig: BigKind? = null
        private set
    var currentSub: ExploreKind? = null
        private set

    /** 管理页可勾选候选:自动划分的大分类 */
    val bigCandidates: List<ExploreKind>
        get() = bigTree.map { it.kind }

    init {
        execute {
            appDb.bookDao.flowAll().mapLatest { books ->
                val keys = arrayListOf<String>()
                books.filterNot { it.isNotShelf }
                    .forEach {
                        keys.add("${it.name}-${it.author}")
                        keys.add(it.name)
                        keys.add(it.bookUrl)
                    }
                keys
            }.catch {
                AppLog.put("发现列表界面获取书籍数据失败\n${it.localizedMessage}", it)
            }.collect {
                bookshelf.clear()
                bookshelf.addAll(it)
                upAdapterLiveData.postValue("isInBookshelf")
            }
        }.onError {
            AppLog.put("加载书架数据失败", it)
        }
    }

    fun initData(intent: Intent) {
        execute {
            val sourceUrl = intent.getStringExtra("sourceUrl")
            val intentUrl = intent.getStringExtra("exploreUrl")
            if (bookSource == null && sourceUrl != null) {
                bookSource = appDb.bookSourceDao.getBookSource(sourceUrl)
            }
            sourceData.postValue(bookSource)
            loadExploreKinds()
            bigTree = buildBigTree()
            upDisplayBigKinds()
            //当前分类:intent url树中匹配,失配取展示列表第一项,不持久化
            currentBig = null
            currentSub = null
            if (intentUrl != null) {
                matchIntentUrl(intentUrl)
            }
            if (currentBig == null) {
                currentBig = displayBigKinds.firstOrNull()
            }
            exploreUrl = currentSub?.url
                ?: currentBig?.kind?.url
                ?: intentUrl
                ?: fallbackUrl()
            page = 1
            books.clear()
            bigKindsData.postValue(displayBigKinds)
            explore()
        }
    }

    /** 解析有效url分类,排除ERROR分类 */
    private suspend fun loadExploreKinds() {
        val source = bookSource ?: return
        allKinds = source.exploreKinds()
            .filter { it.type == ExploreKind.Type.url && !it.url.isNullOrBlank() }
            .filterNot { it.title.startsWith("ERROR:") }
    }

    private fun fallbackUrl(): String? {
        return bookSource?.exploreUrl
    }

    /**
     * 占满一栏判定:flexBasisPercent>=1独占一行;
     * flexGrow>=1按连续段判定,段长为1独占一行,连续多项共享一行互相分摊按细分
     */
    private fun isFullRow(kind: ExploreKind, segLen: IntArray, index: Int): Boolean {
        val style = kind.style()
        if (style.layout_flexBasisPercent >= 1) {
            return true
        }
        if (style.layout_flexGrow >= 1) {
            return segLen[index] == 1
        }
        return false
    }

    /**
     * 划分大分类/细分树:按原顺序扫描,fullRow项开启新大分类,
     * 非fullRow归入当前大分类,首个大分类之前的非fullRow前挂给第一个大分类;
     * 无fullRow项时取第一个有效分类为大分类,其余全部为其细分
     */
    private fun buildBigTree(): List<BigKind> {
        val kinds = allKinds
        if (kinds.isEmpty()) {
            return emptyList()
        }
        //预计算每项所在flexGrow连续段的长度
        val segLen = IntArray(kinds.size) { 1 }
        var i = 0
        while (i < kinds.size) {
            val style = kinds[i].style()
            if (style.layout_flexBasisPercent < 1 && style.layout_flexGrow >= 1) {
                var j = i
                while (j < kinds.size) {
                    val s = kinds[j].style()
                    if (s.layout_flexBasisPercent < 1 && s.layout_flexGrow >= 1) {
                        j++
                    } else {
                        break
                    }
                }
                for (k in i until j) {
                    segLen[k] = j - i
                }
                i = j
            } else {
                i++
            }
        }
        val tree = mutableListOf<Pair<ExploreKind, MutableList<ExploreKind>>>()
        val headSubs = mutableListOf<ExploreKind>()
        kinds.forEachIndexed { index, kind ->
            if (isFullRow(kind, segLen, index)) {
                tree.add(kind to mutableListOf())
            } else if (tree.isEmpty()) {
                headSubs.add(kind)
            } else {
                tree.last().second.add(kind)
            }
        }
        if (tree.isEmpty()) {
            return listOf(BigKind(kinds.first(), kinds.drop(1)))
        }
        tree.first().second.addAll(0, headSubs)
        return tree.map { BigKind(it.first, it.second) }
    }

    /**
     * 白名单过滤:未写/空串展示全部候选;非空取交集保持原顺序;交集为空回落全部
     */
    private fun upDisplayBigKinds() {
        val sourceUrl = bookSource?.bookSourceUrl
        val saved = sourceUrl?.let { LocalConfig.getExploreAdjust(it) } ?: ""
        displayBigKinds = if (saved.isBlank()) {
            bigTree
        } else {
            val names = saved.split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet()
            val filtered = bigTree.filter { it.kind.title in names }
            if (filtered.isEmpty()) bigTree else filtered
        }
    }

    /** intent url在展示树中匹配,命中大分类或细分并定位 */
    private fun matchIntentUrl(url: String) {
        for (big in displayBigKinds) {
            if (big.kind.url == url) {
                currentBig = big
                currentSub = null
                return
            }
            for (sub in big.subKinds) {
                if (sub.url == url) {
                    currentBig = big
                    currentSub = sub
                    return
                }
            }
        }
    }

    /**
     * 选中大分类,currentSub复位为本级,调用方负责清列表触发拉书
     */
    fun selectBig(big: BigKind) {
        currentBig = big
        currentSub = null
        resetLoad()
        bigKindsData.postValue(displayBigKinds)
    }

    /**
     * 选中细分,父级大分类保持高亮,调用方负责清列表触发拉书
     */
    fun selectSub(sub: ExploreKind) {
        currentSub = sub
        resetLoad()
        bigKindsData.postValue(displayBigKinds)
    }

    /**
     * 侧边页使用模式点击分类,在自动划分树中定位,命中返回true
     */
    fun selectFromUrl(title: String, url: String): Boolean {
        val big = bigTree.firstOrNull { it.kind.title == title && it.kind.url == url }
        if (big != null) {
            currentBig = big
            currentSub = null
            resetLoad()
            bigKindsData.postValue(displayBigKinds)
            return true
        }
        val parent = bigTree.firstOrNull { b ->
            b.subKinds.any { it.title == title && it.url == url }
        }
        if (parent != null) {
            currentBig = parent
            currentSub = parent.subKinds.first { it.title == title && it.url == url }
            resetLoad()
            bigKindsData.postValue(displayBigKinds)
            return true
        }
        return false
    }

    /**
     * 管理页提交:保存白名单并做当前分类维护,返回是否切换了分类(需拉书)
     */
    fun applyManageSelection(selected: List<String>): Boolean {
        val sourceUrl = bookSource?.bookSourceUrl ?: return false
        if (selected.isEmpty()) {
            //全取消回落:保存第一项标题,使刻意清空与从未管理可区分
            LocalConfig.putExploreAdjust(sourceUrl, bigTree.firstOrNull()?.kind?.title ?: "")
        } else {
            LocalConfig.putExploreAdjust(sourceUrl, selected.joinToString(","))
        }
        upDisplayBigKinds()
        val stillThere = displayBigKinds.firstOrNull { it.kind == currentBig?.kind }
        return if (stillThere != null) {
            currentBig = stillThere
            bigKindsData.postValue(displayBigKinds)
            false
        } else {
            currentBig = displayBigKinds.firstOrNull()
            currentSub = null
            resetLoad()
            bigKindsData.postValue(displayBigKinds)
            true
        }
    }

    private fun resetLoad() {
        exploreUrl = currentSub?.url ?: currentBig?.kind?.url
        page = 1
        books.clear()
    }

    /**
     * 上滑触发的增量更新
     */
    fun explore(page: Int) {
        val source = bookSource
        val url = exploreUrl
        if (source == null || url == null) return
        WebBook.exploreBook(viewModelScope, source, url, page)
            .timeout(if (BuildConfig.DEBUG) 0L else 60000L)
            .onSuccess(IO) { searchBooks ->
                if (url != exploreUrl) return@onSuccess //已切换分类,丢弃过期数据
                val newBooks = linkedSetOf<SearchBook>()
                newBooks.addAll(searchBooks)
                newBooks.addAll(books)
                books = newBooks
                addBooksData.postValue(searchBooks)
                appDb.searchBookDao.insert(*searchBooks.toTypedArray())
                pageLiveData.postValue(page)
            }.onError {
                if (url != exploreUrl) return@onError
                it.printOnDebug()
                errorTopLiveData.postValue(it.stackTraceStr)
            }
    }

    fun skipPage(page: Int) {
        if (page > 0) {
            books.clear()
            this.page = page
        }
    }

    fun explore() {
        val source = bookSource
        val url = exploreUrl
        if (source == null || url == null) return
        WebBook.exploreBook(viewModelScope, source, url, page)
            .timeout(if (BuildConfig.DEBUG) 0L else 60000L)
            .onSuccess(IO) { searchBooks ->
                if (url != exploreUrl) return@onSuccess //已切换分类,丢弃过期数据
                books.addAll(searchBooks)
                booksData.postValue(books.toList())
                appDb.searchBookDao.insert(*searchBooks.toTypedArray())
                pageLiveData.postValue(page)
                page++
            }.onError {
                if (url != exploreUrl) return@onError
                it.printOnDebug()
                errorLiveData.postValue(it.stackTraceStr)
            }
    }

    /**
     * 直读,优先书架内同名书籍,否则临时保存为不入架书籍再读
     */
    fun readNow(searchBook: SearchBook, success: (String) -> Unit) {
        execute {
            val book = appDb.bookDao.getBook(searchBook.bookUrl)
                ?: appDb.bookDao.getBook(searchBook.name, searchBook.author)
                ?: searchBook.toBook().apply {
                    addType(BookType.notShelf)
                }
            if (book.order == 0) {
                book.order = appDb.bookDao.minOrder - 1
            }
            book.save()
            ReadBook.book = book
            book.bookUrl
        }.onSuccess {
            success(it)
        }.onError {
            AppLog.put("发现直读打开书籍失败\n${it.localizedMessage}", it)
            context.toastOnUi("打开失败:${it.localizedMessage}")
        }
    }

    fun isInBookShelf(book: SearchBook): Boolean {
        val name = book.name
        val author = book.author
        val bookUrl = book.bookUrl
        val key = if (author.isNotBlank()) "$name-$author" else name
        return bookshelf.contains(key) || bookshelf.contains(bookUrl)
    }

}
