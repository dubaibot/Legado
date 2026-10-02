package io.legado.app.ui.book.explore

import android.app.Application
import android.content.Intent
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.BuildConfig
import io.legado.app.R
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
import io.legado.app.utils.GSON
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.mapLatest
import java.util.concurrent.ConcurrentHashMap

/**
 * 分类引用,以 title+url 唯一标识
 */
data class CatRef(val t: String, val u: String)

/**
 * 用户添加的分类集合,big为大分类顺序数组,sub键为"t::u"
 */
class ExploreCats {
    var big: MutableList<CatRef>? = null
    var sub: MutableMap<String, MutableList<CatRef>>? = null
}


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
    val catsData = MutableLiveData<List<CatRef>>()
    private var bookSource: BookSource? = null
    var exploreUrl: String? = null
        private set
    private var page = 1
    private var books = linkedSetOf<SearchBook>()

    /** 解析出的有效url分类,供默认分类推导 */
    private var allKinds: List<ExploreKind> = emptyList()

    /** 整段exploreUrl,无有效分类时兜底 */
    private var fallbackUrl: String? = null
    private var cats = ExploreCats()
    private var currentCat: CatRef? = null

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
            loadExploreKinds(intentUrl)
            val url = bookSource?.bookSourceUrl
            if (url != null) {
                cats = loadCats(url)
            }
            //当前分类:已添加用栏内第一项,否则用默认分类,不持久化
            val first = cats.big?.firstOrNull() ?: defaultCat()
            currentCat = first
            exploreUrl = first.u.ifBlank { intentUrl }
            catsData.postValue(barCats())
            explore()
        }
    }

    /**
     * 解析有效url分类,记录整段exploreUrl兜底
     */
    private suspend fun loadExploreKinds(fallback: String?) {
        val source = bookSource ?: return
        allKinds = source.exploreKinds()
            .filter { it.type == ExploreKind.Type.url && !it.url.isNullOrBlank() }
            .filterNot { it.title.startsWith("ERROR:") }
        fallbackUrl = fallback ?: source.exploreUrl
    }

    /**
     * 未添加分类时的默认分类:第一个有效分类,否则为单个全部
     */
    private fun defaultCat(): CatRef {
        val kind = allKinds.firstOrNull()
        return if (kind != null && kind.url != null) {
            CatRef(kind.title, kind.url)
        } else {
            CatRef(context.getString(R.string.explore_all), fallbackUrl ?: "")
        }
    }

    /**
     * 大分类栏内容:仅展示用户添加过的,未添加时显示默认分类
     */
    fun barCats(): List<CatRef> {
        val saved = cats.big
        return if (!saved.isNullOrEmpty()) saved else listOfNotNull(currentCat ?: defaultCat())
    }

    fun currentIs(cat: CatRef): Boolean = currentCat == cat

    /**
     * 切换大分类,重置分页与数据
     */
    fun switchCategory(cat: CatRef) {
        if (cat.u.isBlank()) return
        currentCat = cat
        exploreUrl = cat.u
        page = 1
        books.clear()
    }

    /**
     * 加载细分类,当前大分类保持不变
     */
    fun loadKind(cat: CatRef) {
        if (cat.u.isBlank()) return
        exploreUrl = cat.u
        page = 1
        books.clear()
    }

    /**
     * 添加大分类,已存在返回false
     */
    fun addBigCategory(t: String, u: String): Boolean {
        val big = cats.big ?: mutableListOf<CatRef>().also { cats.big = it }
        if (big.any { it.t == t && it.u == u }) {
            return false
        }
        big.add(CatRef(t, u))
        saveCats()
        catsData.postValue(barCats())
        return true
    }

    /**
     * 给大分类添加细分类,已存在返回false
     */
    fun addSubCategory(big: CatRef, t: String, u: String): Boolean {
        val sub = cats.sub ?: mutableMapOf<String, MutableList<CatRef>>().also { cats.sub = it }
        val key = "${big.t}::${big.u}"
        val list = sub[key] ?: mutableListOf<CatRef>().also { sub[key] = it }
        if (list.any { it.t == t && it.u == u }) {
            return false
        }
        list.add(CatRef(t, u))
        saveCats()
        return true
    }

    /**
     * 大分类的细分类列表
     */
    fun subCategoriesOf(big: CatRef): List<CatRef> {
        return cats.sub?.get("${big.t}::${big.u}") ?: emptyList()
    }

    private fun loadCats(sourceUrl: String): ExploreCats {
        val json = LocalConfig.getExploreCats(sourceUrl)
        if (json.isBlank()) {
            return ExploreCats()
        }
        return runCatching {
            GSON.fromJson(json, ExploreCats::class.java)
        }.getOrNull() ?: ExploreCats()
    }

    private fun saveCats() {
        val sourceUrl = bookSource?.bookSourceUrl ?: return
        LocalConfig.putExploreCats(sourceUrl, GSON.toJson(cats))
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
