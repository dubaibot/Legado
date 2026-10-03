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
 * 大分类:分段标题(自动模式,细分为其辖区)或用户勾选的url分类(管理模式,无辖区);
 * 单层平铺书源无大分类,细分栏直接展示全部url分类
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

    /** 原始kinds(未过滤类型),供分段标题检测与树顺序扫描 */
    private var rawKinds: List<ExploreKind> = emptyList()

    /** 解析出的有效url分类,按书源原顺序 */
    private var allKinds: List<ExploreKind> = emptyList()

    /** 自动划分:分段标题树,空列表表示单层平铺(大分类栏隐藏) */
    private var bigTree: List<BigKind> = emptyList()

    /** 大分类栏展示内容:白名单模式为用户勾选的url分类,自动模式为分段标题树 */
    private var displayBigKinds: List<BigKind> = emptyList()

    /** 是否存在有效url分类,决定三横与管理入口可见性 */
    val hasExploreKinds: Boolean
        get() = allKinds.isNotEmpty()

    /** 细分栏内容:当前大分类辖区;无大分类(单层平铺)时为全部url分类 */
    val subBarKinds: List<ExploreKind>
        get() {
            currentBig?.let { return it.subKinds }
            return if (displayBigKinds.isEmpty()) allKinds else emptyList()
        }

    /** 当前大分类,细分栏高亮项为currentSub,null表示停在大分类本级 */
    var currentBig: BigKind? = null
        private set
    var currentSub: ExploreKind? = null
        private set

    /** 管理页可勾选候选:全部有效url分类 */
    val bigCandidates: List<ExploreKind>
        get() = allKinds

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
            if (currentBig == null && currentSub == null) {
                if (displayBigKinds.isNotEmpty()) {
                    currentBig = displayBigKinds.first()
                } else {
                    //单层平铺:直接定位第一个url分类
                    currentSub = allKinds.firstOrNull()
                }
            }
            exploreUrl = currentSub?.url
                ?: currentBig?.kind?.url?.takeIf { it.isNotBlank() }
                ?: currentBig?.subKinds?.firstOrNull()?.url
                ?: intentUrl
                ?: fallbackUrl()
            page = 1
            books.clear()
            bigKindsData.postValue(displayBigKinds)
            explore()
        }
    }

    /**
     * 解析kinds:rawKinds保留原样供分段标题检测,allKinds为有效url分类
     */
    private suspend fun loadExploreKinds() {
        val source = bookSource ?: return
        rawKinds = source.exploreKinds()
        allKinds = rawKinds
            .filter { it.type == ExploreKind.Type.url && !it.url.isNullOrBlank() }
            .filterNot { it.title.startsWith("ERROR:") }
    }

    private fun fallbackUrl(): String? {
        return bookSource?.exploreUrl
    }

    /**
     * 占满一行判定:flexBasisPercent>=1独占一行;
     * flexGrow>=1按连续段判定,段长为1独占一行,连续多项共享一行互相分摊
     */
    private fun isFullRowStyle(kind: ExploreKind): Boolean {
        val style = kind.style()
        if (style.layout_flexBasisPercent >= 1) {
            return true
        }
        if (style.layout_flexGrow >= 1) {
            val index = rawKinds.indexOfFirst { it === kind }
            val prevGrow = index > 0 && rawKinds[index - 1].style().layout_flexGrow >= 1
            val nextGrow =
                index in 0 until rawKinds.size - 1 && rawKinds[index + 1].style().layout_flexGrow >= 1
            return !prevGrow && !nextGrow
        }
        return false
    }

    /** 分段标题:无url、占满一行、title非空 */
    private fun isHeaderKind(kind: ExploreKind): Boolean {
        if (!kind.url.isNullOrBlank() || kind.title.isBlank()) {
            return false
        }
        return isFullRowStyle(kind)
    }

    /**
     * 自动划分(真实书源校准):
     * 分段标题数量>=2时,大分类=分段标题,细分=该标题之后、下一标题之前的全部url分类,
     * 点大分类加载段首url分类;
     * 无分段标题时返回空列表(单层平铺,大分类栏隐藏,全部url分类进细分栏);
     * 占满一行的url项按普通细分处理,不特殊化;select只作语境不进栏
     */
    private fun buildBigTree(): List<BigKind> {
        if (allKinds.isEmpty()) {
            return emptyList()
        }
        val headers = rawKinds.filter { isHeaderKind(it) }
        if (headers.size < 2) {
            return emptyList()
        }
        val tree = mutableListOf<BigKind>()
        val headSubs = mutableListOf<ExploreKind>()
        var header: ExploreKind? = null
        var subs = mutableListOf<ExploreKind>()
        for (kind in rawKinds) {
            if (isHeaderKind(kind)) {
                val h = header
                if (h != null && subs.isNotEmpty()) {
                    tree.add(BigKind(h, subs))
                }
                header = kind
                subs = mutableListOf()
            } else if (kind.type == ExploreKind.Type.url
                && !kind.url.isNullOrBlank()
                && !kind.title.startsWith("ERROR:")
            ) {
                if (header == null) {
                    headSubs.add(kind)
                } else {
                    subs.add(kind)
                }
            }
        }
        val h = header
        if (h != null && subs.isNotEmpty()) {
            tree.add(BigKind(h, subs))
        }
        if (tree.isNotEmpty() && headSubs.isNotEmpty()) {
            //首个分段标题之前的url分类前挂给第一个大分类
            val first = tree.first()
            tree[0] = first.copy(subKinds = headSubs + first.subKinds)
        }
        return tree
    }

    /**
     * 展示模式:白名单未写/空串走自动划分(分段标题树或单层平铺);
     * 非空为用户勾选的url分类作为大分类(点击加载自身,无辖区细分),
     * 交集保持书源原顺序,交集为空(源改版失效)回落自动划分
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
            val filtered = allKinds.filter { it.title in names }
                .map { BigKind(it, emptyList()) }
            if (filtered.isEmpty()) bigTree else filtered
        }
    }

    /** intent url在展示树或平铺列表中匹配定位 */
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
        if (displayBigKinds.isEmpty()) {
            //单层平铺:直接命中细分
            currentSub = allKinds.firstOrNull { it.url == url }
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
     * 侧边页使用模式点击分类,定位后返回true(需拉书);
     * 传统发现面板行为不受管理模式影响,逐项正常分发
     */
    fun selectFromUrl(title: String, url: String): Boolean {
        for (big in displayBigKinds) {
            if (big.kind.title == title && big.kind.url == url) {
                currentBig = big
                currentSub = null
                resetLoad()
                bigKindsData.postValue(displayBigKinds)
                return true
            }
            val sub = big.subKinds.firstOrNull { it.title == title && it.url == url }
            if (sub != null) {
                currentBig = big
                currentSub = sub
                resetLoad()
                bigKindsData.postValue(displayBigKinds)
                return true
            }
        }
        if (displayBigKinds.isEmpty()) {
            //单层平铺:直接切细分
            val sub = allKinds.firstOrNull { it.title == title && it.url == url }
            if (sub != null) {
                currentSub = sub
                resetLoad()
                bigKindsData.postValue(displayBigKinds)
                return true
            }
        }
        return false
    }

    /**
     * 管理页提交:保存白名单并做当前分类维护,返回是否切换了分类(需拉书)
     */
    fun applyManageSelection(selected: List<String>): Boolean {
        val sourceUrl = bookSource?.bookSourceUrl ?: return false
        if (selected.isEmpty()) {
            //全取消回落:保存第一个有效分类标题,使刻意清空与从未管理可区分
            LocalConfig.putExploreAdjust(sourceUrl, allKinds.firstOrNull()?.title ?: "")
        } else {
            LocalConfig.putExploreAdjust(sourceUrl, selected.joinToString(","))
        }
        val oldBig = currentBig
        val oldSub = currentSub
        upDisplayBigKinds()
        val stillThere = displayBigKinds.firstOrNull { it.kind == oldBig?.kind }
        return if (stillThere != null) {
            currentBig = stillThere
            if (oldSub != null && stillThere.subKinds.none { it == oldSub }) {
                currentSub = null
            }
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
        exploreUrl = currentSub?.url
            ?: currentBig?.kind?.url?.takeIf { it.isNotBlank() }
            ?: currentBig?.subKinds?.firstOrNull()?.url
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
