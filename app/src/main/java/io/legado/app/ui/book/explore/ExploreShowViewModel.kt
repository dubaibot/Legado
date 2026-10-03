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
import io.legado.app.data.entities.rule.ExploreCatNode
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.data.entities.rule.ExploreKind.Type
import io.legado.app.help.book.addType
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.help.source.exploreKinds
import io.legado.app.model.ReadBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.main.explore.ExploreAdapter
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.utils.InfoMap
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import com.script.rhino.runScriptWithContext
import java.util.concurrent.ConcurrentHashMap

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

    /** 分类树(总览全量渲染信号) */
    val treeData = MutableLiveData<List<ExploreCatNode>>()

    /** 双视图模式:true=书单列表,false=分类总览(仅initData决定初值,后续由界面切换) */
    val showListLiveData = MutableLiveData<Boolean>()

    /** 节点失效提示与button执行完成提示 */
    val nodeInvalidLiveData = MutableLiveData<ExploreCatNode>()
    val nodeExecutedLiveData = MutableLiveData<ExploreCatNode>()

    /** 书源面板变化(reUiView),需刷新筛选页 */
    val panelRefreshLiveData = MutableLiveData<Boolean>()

    /** exploreKinds异步解析完成信号,用于补刷筛选菜单按钮可见性 */
    val kindsLoadedLiveData = MutableLiveData<Boolean>()

    private var bookSource: BookSource? = null
    private var sourceUrl: String? = null
    var exploreUrl: String? = null
        private set
    private var page = 1
    private var books = linkedSetOf<SearchBook>()

    /** 原始kinds,供预填/失效校验/筛选设置应用 */
    private var rawKinds: List<ExploreKind> = emptyList()

    /** 有效url分类 */
    private var allKinds: List<ExploreKind> = emptyList()

    /** 工作树(一级列表)。未编辑时=预填树(不持久化);编辑后=用户树(持久化) */
    private var tree = emptyList<ExploreCatNode>()

    /** 预填平铺二级(未编辑+无分段标题时展示于二级栏) */
    private var flatPreset = emptyList<ExploreCatNode>()

    /** 手动编辑标志,true后预填不再覆盖 */
    private var edited = false

    var currentL1: ExploreCatNode? = null
        private set
    var currentL2: ExploreCatNode? = null
        private set
    var currentL3: ExploreCatNode? = null
        private set

    /** 面板是否有可筛选内容,决定筛选按钮可见性 */
    val hasExploreKinds: Boolean
        get() = rawKinds.any { !it.title.startsWith("ERROR:") }

    /** 平铺预填形态:无一级,二级栏展示flatPreset */
    val isFlatPreset: Boolean
        get() = tree.isEmpty() && !edited && flatPreset.isNotEmpty()

    private val infoMap: InfoMap by lazy {
        val u = sourceUrl ?: ""
        ExploreAdapter.exploreInfoMapList[u] ?: InfoMap(u).also {
            ExploreAdapter.exploreInfoMapList.put(u, it)
        }
    }

    private val jsExtensions by lazy {
        SourceLoginJsExtensions(null, bookSource,
            callback = object : SourceLoginJsExtensions.Callback {
                override fun upUiData(data: Map<String, Any?>?) {
                }

                override fun reUiView(deltaUp: Boolean) {
                    onPanelChanged()
                }
            })
    }

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
            val intentSourceUrl = intent.getStringExtra("sourceUrl")
            val intentUrl = intent.getStringExtra("exploreUrl")
            if (bookSource == null && intentSourceUrl != null) {
                bookSource = appDb.bookSourceDao.getBookSource(intentSourceUrl)
            }
            sourceUrl = bookSource?.bookSourceUrl
            sourceData.postValue(bookSource)
            loadExploreKinds()
            loadTree()
            currentL1 = null
            currentL2 = null
            currentL3 = null
            if (intentUrl != null) {
                locateByUrl(intentUrl)
            }
            val located = hasCurrent()
            showListLiveData.postValue(located)
            upBars()
            if (located) {
                reloadCurrent()
            } else {
                execute {
                    exploreUrl = null
                    page = 1
                    books.clear()
                    booksData.postValue(emptyList())
                }
            }
        }
    }

    private suspend fun loadExploreKinds() {
        val source = bookSource ?: return
        rawKinds = source.exploreKinds()
        allKinds = rawKinds
            .filter { it.type == Type.url && !it.url.isNullOrBlank() }
            .filterNot { it.title.startsWith("ERROR:") }
        kindsLoadedLiveData.postValue(hasExploreKinds)
    }

    /**
     * 加载工作树:已编辑用存储树,未编辑用预填
     */
    private fun loadTree() {
        val u = sourceUrl ?: return
        edited = LocalConfig.isExploreCatsEdited(u)
        if (edited) {
            tree = LocalConfig.getExploreCats(u)
            flatPreset = emptyList()
        } else {
            val (ptree, pflat) = buildPreset()
            tree = ptree
            flatPreset = pflat
        }
    }

    /**
     * 预填:分段标题>=2生成两级骨架,否则一级空+全部url分类平铺
     */
    private fun buildPreset(): Pair<List<ExploreCatNode>, List<ExploreCatNode>> {
        if (allKinds.isEmpty()) {
            return emptyList<ExploreCatNode>() to emptyList()
        }
        val headers = rawKinds.filter { isHeaderKind(it) }
        if (headers.size >= 2) {
            val nodes = mutableListOf<ExploreCatNode>()
            val headSubs = mutableListOf<ExploreCatNode>()
            var header: ExploreKind? = null
            var subs = mutableListOf<ExploreCatNode>()
            for (kind in rawKinds) {
                if (isHeaderKind(kind)) {
                    val h = header
                    if (h != null && subs.isNotEmpty()) {
                        nodes.add(
                            ExploreCatNode(h.title, ExploreCatNode.TYPE_HEADER, h.title, children = subs)
                        )
                    }
                    header = kind
                    subs = mutableListOf()
                } else if (kind.type == Type.url
                    && !kind.url.isNullOrBlank()
                    && !kind.title.startsWith("ERROR:")
                ) {
                    val node = kind.toCatNode(null)
                    if (header == null) {
                        headSubs.add(node)
                    } else {
                        subs.add(node)
                    }
                }
            }
            val h = header
            if (h != null && subs.isNotEmpty()) {
                nodes.add(
                    ExploreCatNode(h.title, ExploreCatNode.TYPE_HEADER, h.title, children = subs)
                )
            }
            if (nodes.isNotEmpty() && headSubs.isNotEmpty()) {
                //首个分段标题之前的url分类前挂给第一个大分类
                val first = nodes.first()
                nodes[0] = first.copy(children = headSubs + first.children)
            }
            return nodes to emptyList()
        }
        return emptyList<ExploreCatNode>() to allKinds.map { it.toCatNode(null) }
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

    private fun ExploreKind.toCatNode(option: String?): ExploreCatNode {
        return when (type) {
            Type.select -> ExploreCatNode(
                name = option ?: title,
                type = Type.select,
                kindTitle = title,
                option = option,
                action = action
            )

            Type.button -> ExploreCatNode(
                name = title,
                type = Type.button,
                kindTitle = title,
                action = action
            )

            else -> ExploreCatNode(
                name = title,
                type = Type.url,
                kindTitle = title,
                url = url
            )
        }
    }

    /** 总览根节点:工作树,未编辑无树时为平铺预填 */
    fun overviewNodes(): List<ExploreCatNode> {
        return if (tree.isEmpty() && !edited) flatPreset else tree
    }

    /** 是否有当前定位(决定列表模式可用性) */
    fun hasCurrent(): Boolean {
        return currentL1 != null || currentL2 != null || currentL3 != null
    }

    /** 顶栏下拉分组:按控件标题聚合树中所有select选项实例(保遍历序) */
    data class SelectGroup(val kindTitle: String, val nodes: List<ExploreCatNode>)

    fun selectGroups(): List<SelectGroup> {
        val map = linkedMapOf<String, MutableList<ExploreCatNode>>()
        fun collect(nodes: List<ExploreCatNode>) {
            nodes.forEach { node ->
                if (node.type == Type.select) {
                    map.getOrPut(node.kindTitle) { mutableListOf() }.add(node)
                }
                collect(node.children)
            }
        }
        collect(overviewNodes())
        return map.map { SelectGroup(it.key, it.value) }
    }

    /**
     * 总览点击节点:树中定位(同步父级引用)并按语义拉书;
     * 已是当前定位返回false不重载
     */
    fun selectNode(node: ExploreCatNode): Boolean {
        val current = currentL3 ?: currentL2 ?: currentL1
        if (current != null && current.sameRefAs(node)) {
            return false
        }
        for (l1 in tree) {
            if (l1.sameRefAs(node)) {
                currentL1 = l1
                currentL2 = null
                currentL3 = null
                upBars()
                reloadCurrent()
                return true
            }
            for (l2 in l1.children) {
                if (l2.sameRefAs(node)) {
                    currentL1 = l1
                    currentL2 = l2
                    currentL3 = null
                    upBars()
                    reloadCurrent()
                    return true
                }
                for (l3 in l2.children) {
                    if (l3.sameRefAs(node)) {
                        currentL1 = l1
                        currentL2 = l2
                        currentL3 = l3
                        upBars()
                        reloadCurrent()
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun upBars() {
        treeData.postValue(tree)
    }

    /** intent url在树或平铺预填中定位,并同步父级引用 */
    private fun locateByUrl(url: String) {
        if (isFlatPreset) {
            currentL2 = flatPreset.firstOrNull { it.url == url }
            return
        }
        for (l1 in tree) {
            if (l1.url == url) {
                currentL1 = l1
                return
            }
            for (l2 in l1.children) {
                if (l2.url == url) {
                    currentL1 = l1
                    currentL2 = l2
                    return
                }
                for (l3 in l2.children) {
                    if (l3.url == url) {
                        currentL1 = l1
                        currentL2 = l2
                        currentL3 = l3
                        return
                    }
                }
            }
        }
    }

    /**
     * 按当前定位执行节点语义拉书;无url语义(button/select无默认列表)时清空列表
     */
    private fun reloadCurrent() {
        val node = currentL3 ?: currentL2 ?: currentL1
        execute {
            if (node == null) {
                exploreUrl = null
                books.clear()
                booksData.postValue(emptyList())
                return@execute
            }
            val url = executeNodeInternal(node)
            page = 1
            books.clear()
            if (url != null) {
                exploreUrl = url
                explore()
            } else {
                exploreUrl = null
                booksData.postValue(emptyList())
            }
        }
    }

    /**
     * 节点执行:先应用筛选设置,再按节点类型执行
     * url→返回其地址;select选项→切语境+找默认列表url;button→仅执行action返回null
     */
    private suspend fun executeNodeInternal(node: ExploreCatNode): String? {
        applyFilterSettings()
        val kind = findKind(node)
        return when (node.type) {
            ExploreCatNode.TYPE_HEADER -> {
                //分段标题:加载段首子级url分类
                node.children.firstOrNull { !it.url.isNullOrBlank() }?.url
            }

            Type.url -> {
                if (kind == null || node.url.isNullOrBlank()) {
                    nodeInvalidLiveData.postValue(node)
                    null
                } else {
                    node.url
                }
            }

            Type.select -> {
                if (kind == null || node.option == null) {
                    nodeInvalidLiveData.postValue(node)
                    return null
                }
                //执行选项action切换语境(写入源变量)
                infoMap[kind.title] = node.option
                infoMap.save()
                evalJs(kind.action, infoMap)
                findSelectDefaultUrl(kind)
            }

            Type.button -> {
                if (kind == null) {
                    nodeInvalidLiveData.postValue(node)
                    return null
                }
                evalJs(kind.action, infoMap)
                nodeExecutedLiveData.postValue(node)
                null
            }

            else -> null
        }
    }

    /**
     * 筛选设置自动应用:按面板顺序遍历select/text/toggle,
     * 按infoMap当前值执行各自action(无action跳过)
     */
    private suspend fun applyFilterSettings() {
        for (kind in rawKinds) {
            when (kind.type) {
                Type.select, Type.text, Type.toggle -> {
                    val v = infoMap[kind.title]
                    if (!v.isNullOrEmpty() && !kind.action.isNullOrBlank()) {
                        evalJs(kind.action, infoMap)
                    }
                }
            }
        }
    }

    /**
     * 失效校验:url比对地址串;select比对控件标题+选项在值域内;button比对标题
     */
    private fun findKind(node: ExploreCatNode): ExploreKind? {
        return when (node.type) {
            Type.url -> rawKinds.firstOrNull {
                it.type == Type.url && it.url == node.url && !it.title.startsWith("ERROR:")
            }

            Type.select -> rawKinds.firstOrNull {
                it.type == Type.select && it.title == node.kindTitle
                        && it.chars?.filterNotNull()?.contains(node.option) == true
            }

            Type.button -> rawKinds.firstOrNull {
                it.type == Type.button && it.title == node.kindTitle
            }

            else -> null
        }
    }

    /**
     * select默认列表:该select之后连续的url项优先title含"全部/所有",其次第一个;
     * 段内无则全面板找"全部",再退全面板第一个;都无返回null(仅刷新面板)
     */
    private fun findSelectDefaultUrl(selectKind: ExploreKind): String? {
        val idx = rawKinds.indexOfFirst { it === selectKind }
        val segUrls = mutableListOf<ExploreKind>()
        if (idx >= 0) {
            for (i in idx + 1 until rawKinds.size) {
                val k = rawKinds[i]
                if (k.type == Type.url) {
                    if (!k.url.isNullOrBlank() && !k.title.startsWith("ERROR:")) {
                        segUrls.add(k)
                    }
                } else {
                    break
                }
            }
        }
        val segPick = segUrls.firstOrNull {
            it.title.contains("全部") || it.title.contains("所有")
        } ?: segUrls.firstOrNull()
        if (segPick != null) {
            return segPick.url
        }
        val globalPick = allKinds.firstOrNull {
            it.title.contains("全部") || it.title.contains("所有")
        } ?: allKinds.firstOrNull()
        return globalPick?.url
    }

    /** 层级成员存在判断(引用级,含option) */
    fun refAtLevel(level: Int, kind: ExploreKind, option: String?): Boolean {
        return existsAtLevel(level, kind.toCatNode(option))
    }

    /** 层级成员存在判断(select控件任意选项) */
    fun refKindAtLevel(level: Int, kind: ExploreKind): Boolean {
        return when (level) {
            1 -> tree.any { sameRefIgnoreOption(it, kind) }
            2 -> tree.any { l1 -> l1.children.any { sameRefIgnoreOption(it, kind) } }
            else -> tree.any { l1 ->
                l1.children.any { l2 -> l2.children.any { sameRefIgnoreOption(it, kind) } }
            }
        }
    }

    /** 弹窗:可挂靠父级候选,二级页签列全部一级,三级页签列全部二级 */
    fun parentChips(level: Int): List<ExploreCatNode> = when (level) {
        2 -> tree
        3 -> tree.flatMap { it.children }
        else -> emptyList()
    }

    /** 弹窗:按kind+option定位层级中已存在的节点(引用信息/删除用) */
    fun nodeAtLevel(level: Int, kind: ExploreKind, option: String?): ExploreCatNode? {
        val probe = kind.toCatNode(option)
        return when (level) {
            1 -> tree.firstOrNull { probe.sameRefAs(it) }
            2 -> tree.asSequence().flatMap { it.children }.firstOrNull { probe.sameRefAs(it) }
            else -> tree.asSequence()
                .flatMap { it.children }
                .flatMap { it.children }
                .firstOrNull { probe.sameRefAs(it) }
        }
    }

    /** 弹窗:层级成员toggle,存在则移除,否则挂到parent下 */
    fun toggleAtLevel(level: Int, kind: ExploreKind, option: String?, parent: ExploreCatNode?) {
        val probe = kind.toCatNode(option)
        if (existsAtLevel(level, probe)) {
            removeFromLevel(level, probe)
        } else {
            addToLevel(level, probe, parent)
        }
    }

    /** 弹窗:移除层级成员,当前定位引用被删时同步清空 */
    fun removeFromLevel(level: Int, ref: ExploreCatNode) {
        ensureEdited()
        when (level) {
            1 -> {
                tree = tree.filterNot { ref.sameRefAs(it) }
                if (currentL1?.sameRefAs(ref) == true) {
                    currentL1 = null
                    currentL2 = null
                    currentL3 = null
                }
            }

            2 -> {
                tree = tree.map {
                    it.copy(children = it.children.filterNot { c -> ref.sameRefAs(c) })
                }
                if (currentL2?.sameRefAs(ref) == true) {
                    currentL2 = null
                    currentL3 = null
                }
            }

            3 -> {
                tree = tree.map { l1 ->
                    l1.copy(
                        children = l1.children.map { l2 ->
                            l2.copy(children = l2.children.filterNot { c -> ref.sameRefAs(c) })
                        }
                    )
                }
                if (currentL3?.sameRefAs(ref) == true) {
                    currentL3 = null
                }
            }
        }
        persist()
        upBars()
    }

    private fun existsAtLevel(level: Int, probe: ExploreCatNode): Boolean {
        return when (level) {
            1 -> tree.any { probe.sameRefAs(it) }
            2 -> tree.any { l1 -> l1.children.any { probe.sameRefAs(it) } }
            else -> tree.any { l1 ->
                l1.children.any { l2 -> l2.children.any { probe.sameRefAs(it) } }
            }
        }
    }

    /** select控件在层级内的引用比较:忽略option */
    private fun sameRefIgnoreOption(node: ExploreCatNode, kind: ExploreKind): Boolean {
        return node.type == kind.type
                && node.kindTitle == kind.title
                && node.url == kind.url
                && node.action == kind.action
    }

    /** 显式父级挂载:一级入树顶,二级挂指定一级,三级挂指定二级(其一级自动定位) */
    private fun addToLevel(level: Int, node: ExploreCatNode, parent: ExploreCatNode?) {
        ensureEdited()
        when (level) {
            1 -> {
                tree = tree + node
                if (currentL1 == null && currentL2 == null && currentL3 == null) {
                    currentL1 = node
                }
            }

            2 -> {
                val l1 = parent?.let { p -> tree.firstOrNull { it.sameRefAs(p) } } ?: return
                tree = tree.map {
                    if (it.sameRefAs(l1)) {
                        it.copy(children = it.children + node)
                    } else {
                        it
                    }
                }
            }

            3 -> {
                val l1 = parent?.let { p ->
                    tree.firstOrNull { top -> top.children.any { it.sameRefAs(p) } }
                } ?: return
                tree = tree.map { top ->
                    if (top.sameRefAs(l1)) {
                        top.copy(
                            children = top.children.map { l2 ->
                                if (l2.sameRefAs(parent)) {
                                    l2.copy(children = l2.children + node)
                                } else {
                                    l2
                                }
                            }
                        )
                    } else {
                        top
                    }
                }
            }
        }
        persist()
        upBars()
    }

    /** 首次编辑转正:预填树转为工作树并置编辑标志 */
    private fun ensureEdited() {
        if (!edited) {
            edited = true
            LocalConfig.putExploreCatsEdited(sourceUrl ?: return, true)
        }
    }

    private fun persist() {
        val u = sourceUrl ?: return
        LocalConfig.putExploreCats(u, tree)
    }

    /** 清空重建:删除现有树,重新按预填算法生成(回未编辑状态) */
    fun resetToPreset() {
        val u = sourceUrl ?: return
        edited = false
        LocalConfig.putExploreCatsEdited(u, false)
        LocalConfig.putExploreCats(u, emptyList())
        val (ptree, pflat) = buildPreset()
        tree = ptree
        flatPreset = pflat
        currentL1 = null
        currentL2 = null
        currentL3 = null
        upBars()
        showListLiveData.postValue(false)
    }

    /** 清空:全部置空,置编辑标志不再预填 */
    fun clearAll() {
        val u = sourceUrl ?: return
        edited = true
        tree = emptyList()
        flatPreset = emptyList()
        LocalConfig.putExploreCatsEdited(u, true)
        persist()
        currentL1 = null
        currentL2 = null
        currentL3 = null
        upBars()
        showListLiveData.postValue(false)
        execute {
            exploreUrl = null
            page = 1
            books.clear()
            booksData.postValue(emptyList())
        }
    }

    /** 面板变化(js执行reUiView):清缓存重解析,通知筛选页刷新 */
    fun onPanelChanged() {
        execute {
            bookSource?.clearExploreKindsCache()
            loadExploreKinds()
            panelRefreshLiveData.postValue(true)
        }
    }

    /** 筛选设置kinds:select/toggle/button(text搜索框不渲染) */
    fun filterSettingKinds(): List<ExploreKind> {
        return rawKinds.filter {
            it.type == Type.select || it.type == Type.toggle || it.type == Type.button
        }
    }

    /** 弹窗分类网格kinds:url分类+select控件(点选项作分类) */
    fun gridKinds(): List<ExploreKind> = rawKinds.filter {
        (it.type == Type.url && !it.url.isNullOrBlank() && !it.title.startsWith("ERROR:"))
                || it.type == Type.select
    }

    /** 定位被删除(筛选关闭/弹窗删除):回到分类总览模式 */
    fun onFilterClosed() {
        if (!hasCurrent()) {
            showListLiveData.postValue(false)
        }
    }

    private suspend fun evalJs(action: String?, infoMap: InfoMap) {
        val source = bookSource ?: return
        val jsStr = action?.takeIf { it.isNotBlank() } ?: return
        try {
            runScriptWithContext {
                source.evalJS(jsStr) {
                    put("java", jsExtensions)
                    put("infoMap", infoMap)
                }
            }
        } catch (e: Exception) {
            AppLog.put("ExploreNode action error", e)
        }
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
