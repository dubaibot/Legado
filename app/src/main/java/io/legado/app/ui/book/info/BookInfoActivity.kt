package io.legado.app.ui.book.info

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.widget.PopupMenu
import android.view.textclassifier.TextClassifier
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Theme
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.repository.ReadRecordRepository
import io.legado.app.databinding.ActivityBookInfoBinding
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.GlideImageGetter
import io.legado.app.help.TextViewTagHandler
import io.legado.app.help.WebCacheManager
import io.legado.app.help.book.addType
import io.legado.app.help.book.getRemoteUrl
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.isWebFile
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.SilentSslWebViewClient
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.help.webView.WebJsExtensions.Companion.getInjectionString
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache
import io.legado.app.help.webView.WebJsExtensions.Companion.nameJava
import io.legado.app.help.webView.WebJsExtensions.Companion.nameSource
import io.legado.app.help.webView.WebViewPool
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.model.BookCover
import io.legado.app.model.remote.RemoteBookWebDav
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.audio.AudioPlayActivity
import io.legado.app.ui.book.changecover.ChangeCoverDialog
import io.legado.app.ui.book.changesource.ChangeBookSourceDialog
import io.legado.app.ui.book.group.GroupSelectDialog
import io.legado.app.ui.book.info.edit.BookInfoEditActivity
import io.legado.app.ui.book.manga.ReadMangaActivity
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.ReadBookActivity.Companion.RESULT_DELETED
import io.legado.app.ui.book.readRecord.BookReadRecordActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.search.SearchAdapter
import io.legado.app.model.SourceCallBack
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.video.VideoPlayerActivity
import io.legado.app.help.video.VideoPlaylistHolder
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.ui.widget.refreshTopBarContentColor
import io.legado.app.ui.widget.dialog.VariableDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.ui.widget.TitleBar
import io.legado.app.ui.widget.text.ScrollTextView
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.GSON
import io.legado.app.help.book.BookTagHelper
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.applyStatusBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.longToastOnUi
import io.legado.app.utils.observeEvent
import io.legado.app.utils.openFileUri
import io.legado.app.utils.openUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setHtml
import io.legado.app.utils.setMarkdown
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.glide.GlideImagesPlugin
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookInfoActivity :
    VMBaseActivity<ActivityBookInfoBinding, BookInfoViewModel>(toolBarTheme = Theme.Dark, showOpenMenuIcon = false),
    GroupSelectDialog.CallBack,
    ChangeBookSourceDialog.CallBack,
    ChangeCoverDialog.CallBack,
    VariableDialog.Callback {

    private companion object {
        // 分享携带书籍 JSON 的安全阈值（Binder 事务上限约 1MB，chooser 包装再翻倍，300KB 保守安全）
        private const val MAX_SHARE_BOOK_JSON = 300_000
    }

    private val tocActivityResult = registerForActivityResult(TocActivityResult()) {
        it?.let {
            viewModel.getBook(false)?.let { book ->
                lifecycleScope.launch {
                    withContext(IO) {
                        val durChapterIndex = it[0] as Int
                        val durChapterPos = it[1] as Int
                        val durVolumeIndex = it[3] as Int
                        val chapterInVolumeIndex = it[4] as Int
                        book.durChapterIndex = durChapterIndex
                        book.durChapterPos = durChapterPos
                        chapterChanged = it[2] as Boolean
                        book.durVolumeIndex = durVolumeIndex
                        book.chapterInVolumeIndex = chapterInVolumeIndex
                        appDb.bookDao.update(book)
                    }
                    startReadActivity(book)
                }
            }
        } ?: let {
            if (!viewModel.inBookshelf) {
                viewModel.delBook() //进目录会保存book，此时退出目录触发的book删除，不通知书源回调
            }
        }
    }
    private val localBookTreeSelect = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { treeUri ->
            AppConfig.defaultBookTreeUri = treeUri.toString()
        }
    }
    private val readBookResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.upBook(intent)
        when (it.resultCode) {
            RESULT_OK -> {
                viewModel.inBookshelf = true
                upTvBookshelf()
            }

            RESULT_DELETED -> {
                setResult(RESULT_OK)
                finish()
            }
        }
    }
    private val infoEditResult = registerForActivityResult(
        StartActivityContract(BookInfoEditActivity::class.java)
    ) {
        if (it.resultCode == RESULT_OK) {
            viewModel.upEditBook()
        }
    }
    private val editSourceResult = registerForActivityResult(
        StartActivityContract(BookSourceEditActivity::class.java)
    ) {
        if (it.resultCode == RESULT_CANCELED) {
            return@registerForActivityResult
        }
        book?.let { book ->
            lifecycleScope.launch(IO) {
                val source = appDb.bookSourceDao.getBookSource(book.origin)
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    viewModel.bookSource = source?.also {
                        viewModel.hasCustomBtn = it.customButton
                    }
                    viewModel.refreshBook(book)
                }
            }
        }
    }
    private var chapterChanged = false
    private val waitDialog by lazy { WaitDialog(this) }
    private val book get() = viewModel.getBook(false)

    override val binding by viewBinding(ActivityBookInfoBinding::inflate)
    override val viewModel by viewModels<BookInfoViewModel>()
    private var initIntroView = false
    private var readRecordJob: Job? = null
    private val readRecordRepository by lazy { ReadRecordRepository(appDb.readRecordDao) }
    private val infoMenu by lazy {
        PopupMenu(this, binding.ivInfoMenu, Gravity.END).apply {
            menuInflater.inflate(R.menu.book_info, menu)
            setOnMenuItemClickListener { onCompatOptionsItemSelected(it) }
        }
    }
    private val authorOtherWorksAdapter by lazy { SearchAdapter(this, authorOtherWorksCallback) }
    private val authorOtherWorksCallback = object : SearchAdapter.CallBack {
        override fun isInBookshelf(book: SearchBook): Boolean =
            appDb.bookDao.getBook(book.name, book.author) != null

        override fun showBookInfo(book: SearchBook) {
            startActivity<BookInfoActivity> {
                putExtra("name", book.name)
                putExtra("author", book.author)
                putExtra("bookUrl", book.bookUrl)
            }
        }
    }
    private val introTextView by lazy {
        initIntroView = true
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.view_book_intro, binding.tvIntroContainer, false) as ScrollTextView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            view.revealOnFocusHint = false
        }
        view
    }

    private var pooledWebView: PooledWebView? = null

    private val imgAvailableWidth by lazy {
        val textView = introTextView
        textView.width - textView.paddingLeft - textView.paddingRight - 8.dpToPx()  //8是为了文字对齐额外的右边距
    }
    private var initGetter = false
    private val glideImageGetter by lazy {
        initGetter = true
        GlideImageGetter(
            this,
            introTextView,
            lifecycle,
            imgAvailableWidth,
            viewModel.bookSource?.bookSourceUrl
        )
    }

    private val textViewTagHandler by lazy {
        TextViewTagHandler(object : TextViewTagHandler.OnButtonClickListener {
            override fun onButtonClick(name: String, click: String) {
                viewModel.onButtonClick(this@BookInfoActivity, "info button $name" , click)
            }
        })
    }

    @SuppressLint("PrivateResource")
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.topBarRow.applyStatusBarPadding(withInitialPadding = true)
        binding.titleBar.setBackgroundResource(R.color.transparent)
        binding.titleBar.refreshTopBarContentColor()
        binding.ivInfoMenu?.setOnClickListener { infoMenu.show() }
        binding.ivInfoShare?.setOnClickListener { shareBook() }
        binding.flAction.applyNavigationBarPadding(withInitialPadding = true)
        binding.refreshLayout?.setColorSchemeColors(accentColor)
        binding.arcView?.setBgColor(backgroundColor)
        binding.llInfo.setBackgroundColor(backgroundColor)
        binding.ivCoverC.setCardBackgroundColor(backgroundColor)
        binding.vwBg.applyNavigationBarPadding()
        binding.tvToc.text = getString(R.string.toc_s, getString(R.string.loading))
        binding.rvAuthorOtherWorks?.apply {
            layoutManager = LinearLayoutManager(this@BookInfoActivity)
            adapter = authorOtherWorksAdapter
            itemAnimator = null
            isNestedScrollingEnabled = false
        }
        viewModel.bookData.observe(this) { showBook(it) }
        viewModel.authorOtherWorksData.observe(this) { showAuthorOtherWorks(it) }
        viewModel.chapterListData.observe(this) { upLoading(false, it) }
        viewModel.waitDialogData.observe(this) { upWaitDialogStatus(it) }
        viewModel.initData(intent)
        initViewEvent()
        initBottomBar()
    }

    private fun initBottomBar() = binding.run {
        tvShelf.setOnClickListener {
            viewModel.getBook()?.let { currentBook ->
                if (viewModel.inBookshelf) {
                    deleteBook()
                } else if (currentBook.isWebFile) {
                    showWebFileDownloadAlert()
                } else {
                    viewModel.addToBookshelf { upTvBookshelf() }
                }
            }
        }
        tvRead.setOnClickListener {
            viewModel.getBook()?.let { currentBook ->
                if (currentBook.isWebFile) {
                    showWebFileDownloadAlert { readBook(it) }
                } else {
                    readBook(currentBook)
                }
            }
        }
    }

    override fun onCompatCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.book_info, menu)
        return true
    }

    override fun onMenuOpened(featureId: Int, menu: android.view.Menu): Boolean {
        updateInfoMenu()
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: android.view.MenuItem): Boolean {
        val source = viewModel.bookSource
        when (item.itemId) {
            R.id.menu_custom_btn -> viewModel.getBook()?.let { currentBook ->
                source?.let {
                    SourceCallBack.callBackBtn(this, SourceCallBack.CLICK_CUSTOM_BUTTON, it, currentBook, null)
                }
            }
            R.id.menu_edit -> viewModel.getBook()?.let { currentBook ->
                infoEditResult.launch { putExtra("bookUrl", currentBook.bookUrl) }
            }
            R.id.menu_share_it -> shareBook()
            R.id.menu_upload -> viewModel.getBook()?.let { currentBook ->
                currentBook.getRemoteUrl()?.let {
                    showComposeConfirmDialog(
                        title = getString(R.string.draw),
                        message = getString(R.string.sure_upload),
                        positiveText = getString(R.string.ok),
                        negativeText = getString(R.string.cancel),
                        onPositive = { upLoadBook(currentBook) }
                    )
                } ?: upLoadBook(currentBook)
            }
            R.id.menu_refresh -> refreshBook()
            R.id.menu_login -> source?.let {
                startActivity<SourceLoginActivity> {
                    putExtra("type", "bookSource")
                    putExtra("key", it.bookSourceUrl)
                    putExtra("bookUrl", book?.bookUrl)
                }
            }
            R.id.menu_top -> viewModel.topBook()
            R.id.menu_set_source_variable -> setSourceVariable()
            R.id.menu_set_book_variable -> setBookVariable()
            R.id.menu_copy_book_url -> copyBookUrl(false)
            R.id.menu_copy_toc_url -> copyBookUrl(true)
            R.id.menu_can_update -> viewModel.getBook()?.let { currentBook ->
                currentBook.canUpdate = !item.isChecked
                if (viewModel.inBookshelf) viewModel.saveBook(currentBook)
                item.isChecked = currentBook.canUpdate
            }
            R.id.menu_split_long_chapter -> viewModel.getBook()?.let { currentBook ->
                val splitLongChapter = !item.isChecked
                upLoading(true)
                currentBook.setSplitLongChapter(splitLongChapter)
                viewModel.loadBookInfo(currentBook, false)
                item.isChecked = splitLongChapter
                if (!splitLongChapter) longToastOnUi(R.string.need_more_time_load_content)
            }
            R.id.menu_delete_alert -> {
                LocalConfig.bookInfoDeleteAlert = !item.isChecked
                item.isChecked = LocalConfig.bookInfoDeleteAlert
            }
            R.id.menu_show_read_record -> {
                AppConfig.bookInfoShowReadRecord = !item.isChecked
                book?.let { upReadRecord(it) }
                item.isChecked = AppConfig.bookInfoShowReadRecord
            }
            R.id.menu_show_author_other_works -> {
                AppConfig.bookInfoShowAuthorOtherWorks = !item.isChecked
                book?.let { upAuthorOtherWorksVisibility(it) }
                item.isChecked = AppConfig.bookInfoShowAuthorOtherWorks
            }
            R.id.menu_show_book_tag -> {
                AppConfig.bookInfoShowBookTag = !item.isChecked
                book?.let { upTag(it) }
                item.isChecked = AppConfig.bookInfoShowBookTag
            }
            R.id.menu_clear_cache -> viewModel.getBook()?.let { currentBook ->
                SourceCallBack.callBackBtn(this, SourceCallBack.CLICK_CLEAR_CACHE, source, currentBook, null) {
                    viewModel.clearCache(currentBook)
                }
            }
            R.id.menu_log -> showDialogFragment<AppLogDialog>()
            else -> return super.onCompatOptionsItemSelected(item)
        }
        return true
    }

    private fun updateInfoMenu() {
        val menu = infoMenu.menu
        menu.findItem(R.id.menu_custom_btn)?.isVisible = viewModel.hasCustomBtn
        menu.findItem(R.id.menu_edit)?.isVisible = viewModel.inBookshelf
        menu.findItem(R.id.menu_login)?.isVisible = !viewModel.bookSource?.loginUrl.isNullOrBlank()
        menu.findItem(R.id.menu_set_source_variable)?.isVisible = viewModel.bookSource != null
        menu.findItem(R.id.menu_set_book_variable)?.isVisible = viewModel.bookSource != null
        menu.findItem(R.id.menu_can_update)?.apply {
            isVisible = viewModel.bookSource != null
            isChecked = viewModel.bookData.value?.canUpdate ?: true
        }
        menu.findItem(R.id.menu_split_long_chapter)?.apply {
            isVisible = viewModel.bookData.value?.isLocalTxt == true
            isChecked = viewModel.bookData.value?.getSplitLongChapter() ?: true
        }
        menu.findItem(R.id.menu_upload)?.isVisible = viewModel.bookData.value?.isLocal == true
        menu.findItem(R.id.menu_delete_alert)?.isChecked = LocalConfig.bookInfoDeleteAlert
        menu.findItem(R.id.menu_show_read_record)?.isChecked = AppConfig.bookInfoShowReadRecord
        menu.findItem(R.id.menu_show_author_other_works)?.isChecked = AppConfig.bookInfoShowAuthorOtherWorks
        menu.findItem(R.id.menu_show_book_tag)?.isChecked = AppConfig.bookInfoShowBookTag
    }

    override fun onBackPressed() {
        supportFinishAfterTransition()
    }

    private fun shareBook() {
        viewModel.getBook()?.let { currentBook ->
            val bookJson = GSON.toJson(currentBook)
            val shareText = if (bookJson.length > MAX_SHARE_BOOK_JSON) {
                currentBook.bookUrl
            } else {
                "${currentBook.bookUrl}#$bookJson"
            }
            SourceCallBack.callBackBtn(
                this,
                SourceCallBack.CLICK_SHARE_BOOK,
                viewModel.bookSource,
                currentBook,
                null,
                result = shareText
            ) {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(Intent.EXTRA_TEXT, shareText)
                    type = "text/plain"
                }, currentBook.name))
            }
        }
    }

    private fun copyBookUrl(tocUrl: Boolean) {
        viewModel.getBook(false)?.let { currentBook ->
            val url = if (tocUrl) currentBook.tocUrl else currentBook.bookUrl
            val action = if (tocUrl) SourceCallBack.CLICK_COPY_TOC_URL else SourceCallBack.CLICK_COPY_BOOK_URL
            SourceCallBack.callBackBtn(this, action, viewModel.bookSource, currentBook, null, result = url) {
                sendToClip(url)
            }
        }
    }

    private fun upAuthorOtherWorksVisibility(book: Book) {
        binding.llAuthorOtherWorks?.visibility = if (
            AppConfig.bookInfoShowAuthorOtherWorks && !book.isLocal && book.getRealAuthor().isNotBlank()
        ) {
            viewModel.searchAuthorOtherWorksIfIdle()
            android.view.View.VISIBLE
        } else android.view.View.GONE
    }

    private fun showAuthorOtherWorks(state: BookInfoViewModel.AuthorOtherWorksState) = binding.run {
        ivAuthorOtherWorksRefresh?.isEnabled = state !is BookInfoViewModel.AuthorOtherWorksState.Loading
        when (state) {
            BookInfoViewModel.AuthorOtherWorksState.Idle -> showAuthorOtherWorksState(R.string.book_author_other_works_idle)
            BookInfoViewModel.AuthorOtherWorksState.Loading -> showAuthorOtherWorksState(R.string.book_author_other_works_loading)
            BookInfoViewModel.AuthorOtherWorksState.Empty -> showAuthorOtherWorksState(R.string.book_author_other_works_empty)
            is BookInfoViewModel.AuthorOtherWorksState.Success -> {
                tvAuthorOtherWorksState?.gone()
                rvAuthorOtherWorks?.visible()
                authorOtherWorksAdapter.setItems(state.books)
            }
            is BookInfoViewModel.AuthorOtherWorksState.Error -> {
                showAuthorOtherWorksState(R.string.book_author_other_works_error, state.message)
            }
        }
    }

    private fun showAuthorOtherWorksState(message: Int, detail: String? = null) = binding.run {
        rvAuthorOtherWorks?.gone()
        tvAuthorOtherWorksState?.visible()
        tvAuthorOtherWorksState?.text = if (detail == null) getString(message) else getString(message, detail)
        authorOtherWorksAdapter.setItems(emptyList())
    }

    private fun upTag(book: Book) {
        val show = AppConfig.bookInfoShowBookTag && viewModel.inBookshelf
        binding.llBookTag?.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
        if (show) {
            val tags = BookTagHelper.parse(book.customTag)
            val value = tags.takeIf { it.isNotEmpty() }?.joinToString(", ")
                ?: getString(R.string.bookshelf_tag_none)
            binding.tvTag?.text = getString(R.string.book_tag_s, value)
        }
    }

    private fun upReadRecord(book: Book) {
        if (!AppConfig.enableReadRecord || !AppConfig.bookInfoShowReadRecord) {
            binding.llReadRecord?.gone()
            return
        }
        binding.llReadRecord?.visible()
        readRecordJob?.cancel()
        readRecordJob = lifecycleScope.launch {
            readRecordRepository.getBookReadTime(book.name, book.author).collect { totalReadTime ->
                val summary = if (totalReadTime <= 0L) {
                    getString(R.string.no_read_record)
                } else {
                    getString(
                        R.string.book_read_time_label,
                        io.legado.app.utils.formatReadDuration(this@BookInfoActivity, totalReadTime)
                    )
                }
                binding.tvReadRecord?.text = summary
            }
        }
    }

    private fun openBookReadRecord() {
        viewModel.getBook(false)?.let { currentBook ->
            startActivity<BookReadRecordActivity> {
                putExtra(BookReadRecordActivity.EXTRA_BOOK_NAME, currentBook.name)
                putExtra(BookReadRecordActivity.EXTRA_BOOK_AUTHOR, currentBook.author)
            }
        }
    }

    private fun setTags(tags: List<String>) {
        viewModel.getBook()?.let { currentBook ->
            currentBook.customTag = BookTagHelper.join(tags)
            upTag(currentBook)
            if (viewModel.inBookshelf) viewModel.saveBook(currentBook)
        }
    }

    override fun observeLiveBus() {
        viewModel.actionLive.observe(this) {
            when (it) {
                "selectBooksDir" -> localBookTreeSelect.launch {
                    title = getString(R.string.select_book_folder)
                }
            }
        }

        observeEvent<Boolean>(EventBus.REFRESH_BOOK_INFO) { //书源js函数触发刷新
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                refreshBook()
            }
        }

        observeEvent<Boolean>(EventBus.REFRESH_BOOK_TOC) { //书源js函数触发刷新
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                refreshToc()
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (initIntroView && ev.action == MotionEvent.ACTION_DOWN) {
            currentFocus?.let {
                if (it === introTextView && introTextView.hasSelection()) {
                    it.clearFocus()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun refreshBook() {
        upLoading(true)
        viewModel.getBook()?.let {
            viewModel.refreshBook(it)
        }
    }

    private fun refreshToc() {
        upLoading(true)
        viewModel.getBook()?.let {
            viewModel.loadChapter(it, true, isFromBookInfo = true)
        }
    }

    private fun upLoadBook(
        book: Book,
        bookWebDav: RemoteBookWebDav? = AppWebDav.defaultBookWebDav,
    ) {
        lifecycleScope.launch {
            waitDialog.setText(getString(R.string.uploading))
            waitDialog.show()
            try {
                bookWebDav
                    ?.upload(book)
                    ?: throw NoStackTraceException(getString(R.string.webdav_not_configured))
                //更新书籍最后更新时间,使之比远程书籍的时间新
                book.lastCheckTime = System.currentTimeMillis()
                viewModel.saveBook(book)
            } catch (e: Exception) {
                toastOnUi(e.localizedMessage)
            } finally {
                waitDialog.dismiss()
            }
        }
    }

    private fun showBook(book: Book) = binding.run {
        showCover(book)
        tvName.text = book.name
        tvAuthor.text = getString(R.string.author_show, book.getRealAuthor())
        tvOrigin.text = getString(R.string.origin_show, book.originName)
        tvLasted.text = getString(R.string.lasted_show, book.latestChapterTitle)
        showBookIntro(book)
        if (book.isWebFile) {
            llToc.gone()
            tvLasted.text = getString(R.string.lasted_show, getString(R.string.downloading_dots))
        } else {
            llToc.visible()
        }
        upTvBookshelf()
        upKinds(book)
        upGroup(book.group)
        upTag(book)
        upReadRecord(book)
        upAuthorOtherWorksVisibility(book)
        viewModel.authorOtherWorksData.value?.let { showAuthorOtherWorks(it) }
    }

    inner class CustomWebViewClient : SilentSslWebViewClient() {
        private val jsStr = getInjectionString
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            request?.let {
                val uri = it.url
                return when (uri.scheme) {
                    "http", "https" -> false
                    "legado", "yuedu" -> {
                        startActivity<OnLineImportActivity> {
                            data = uri
                        }
                        true
                    }

                    else -> {
                        binding.root.longSnackbar(R.string.jump_to_another_app, R.string.confirm) {
                            openUrl(uri)
                        }
                        true
                    }
                }
            }
            return true
        }
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            view?.evaluateJavascript(jsStr, null)
        }
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            view?.post {
                binding.tvIntroContainer.requestLayout()
            }
        }
    }

    private fun showBookIntro(book: Book) {
        val intro = book.getDisplayIntro()
        if (intro?.startsWith("<useweb>") == true) {
            val lastIndex = intro.lastIndexOf("<")
            if (lastIndex < 8) {
                introTextView.text = intro
                return
            }
            val html = intro.substring(8, lastIndex)
            val pooledWebView = this.pooledWebView ?: let{
                val pooledWebView = WebViewPool.acquire(this)
                val webView = pooledWebView.realWebView
                webView.onResume()
                webView.webViewClient = CustomWebViewClient()
                webView.addJavascriptInterface(WebCacheManager, nameCache)
                viewModel.bookSource?.let {
                    webView.addJavascriptInterface(it as BaseSource, nameSource)
                    val webJsExtensions = WebJsExtensions(it, null, webView)
                    webView.addJavascriptInterface(webJsExtensions, nameJava)
                }
                pooledWebView
            }
            val webView = pooledWebView.realWebView
            if (initIntroView || this.pooledWebView == null) {
                initIntroView = false
                this.pooledWebView = pooledWebView
                binding.tvIntroContainer.removeAllViews()
                binding.tvIntroContainer.addView(webView)
            }
            val bookUrl = viewModel.getBook()?.bookUrl
                ?.takeIf { it.startsWith("http", true) }
                ?.substringBefore(",")
            webView.loadDataWithBaseURL(bookUrl, html, "text/html", "utf-8", bookUrl)
            return
        }
        if (!initIntroView || pooledWebView != null) {
            destroyWeb()
            binding.tvIntroContainer.removeAllViews()
            binding.tvIntroContainer.addView(introTextView)
        }
        if (intro.isNullOrBlank()) {
            return
        }
        val tvIntro = introTextView
        if (intro.startsWith("<usehtml>")) {
            val lastIndex = intro.lastIndexOf("<")
            if (lastIndex < 9) {
                tvIntro.text = intro
                return
            }
            val html = intro.substring(9, lastIndex)
            tvIntro.setHtml(
                html,
                glideImageGetter,
                textViewTagHandler,
                imgOnLongClickListener = {
                    showDialogFragment(PhotoDialog(it, viewModel.bookSource?.bookSourceUrl))
                },
                imgOnClickListener = {
                    viewModel.onButtonClick(this@BookInfoActivity, "info image" , it)
                }
            )
        } else if (intro.startsWith("<md>")) {
            val lastIndex = intro.lastIndexOf("<")
            if (lastIndex < 4) {
                tvIntro.text = intro
                return
            }
            val mark = intro.substring(4, lastIndex)
            lifecycleScope.launch {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    tvIntro.setTextClassifier(TextClassifier.NO_OP)
                }
                val context = this@BookInfoActivity
                val markwon: Markwon
                val markdown = withContext(IO) {
                    markwon = Markwon.builder(context)
                        .usePlugin(
                            GlideImagesPlugin.create(
                                Glide.with(context)
                                    .applyDefaultRequestOptions(
                                        RequestOptions()
                                            .override(imgAvailableWidth)
                                            .encodeQuality(88)
                                    )
                            )
                        )
                        .usePlugin(HtmlPlugin.create())
                        .usePlugin(TablePlugin.create(context))
                        .build()
                    markwon.toMarkdown(mark)
                }
                tvIntro.setMarkdown(
                    markwon,
                    markdown,
                    imgOnLongClickListener = { source ->
                        showDialogFragment(PhotoDialog(source, viewModel.bookSource?.bookSourceUrl))
                    }
                )
            }
        } else {
            tvIntro.text = intro
        }
    }

    private fun upKinds(book: Book) = binding.run {
        lifecycleScope.launch {
            var kinds = book.getKindList()
            if (book.isLocal) {
                withContext(IO) {
                    val size = FileDoc.fromFile(book.bookUrl).size
                    if (size > 0) {
                        kinds = kinds.toMutableList()
                        kinds.add(ConvertUtils.formatFileSize(size))
                    }
                }
            }
            if (kinds.isEmpty()) {
                lbKind.gone()
            } else {
                lbKind.visible()
                val source = viewModel.bookSource
                if (source == null) {
                    lbKind.setLabels(kinds)
                    return@launch
                }
                lbKind.setLabels(
                    kinds,
                    { kind ->
                        SourceCallBack.callBackBtn(
                            this@BookInfoActivity,
                            SourceCallBack.CLICK_BOOK_LABEL,
                            source,
                            book,
                            null,
                            result = kind
                        ) {
                            SearchActivity.start(this@BookInfoActivity, source, kind)
                        }
                    },
                    { kind ->
                        SourceCallBack.callBackBtn(
                            this@BookInfoActivity,
                            SourceCallBack.LONG_CLICK_BOOK_LABEL,
                            source,
                            book,
                            null,
                            result = kind
                        )
                        true
                    }
                )
            }
        }
    }

    private fun showCover(book: Book) {
        binding.ivCover.load(book, false) {
            if (!AppConfig.isEInkMode) {
                BookCover.loadBlur(this, book.getDisplayCover(), false, book.origin)
                    .into(binding.bgBook)
            }
        }
    }

    private fun upLoading(isLoading: Boolean, chapterList: List<BookChapter>? = null) {
        when {
            isLoading -> {
                binding.tvToc.text = getString(R.string.toc_s, getString(R.string.loading))
            }

            chapterList.isNullOrEmpty() -> {
                binding.tvToc.text = getString(
                    R.string.toc_s,
                    getString(R.string.error_load_toc)
                )
                binding.tvLasted.text = getString(R.string.lasted_show, book?.latestChapterTitle)
            }

            else -> {
                book?.let {
                    binding.tvToc.text = getString(R.string.toc_s, it.durChapterTitle)
                    binding.tvLasted.text = getString(R.string.lasted_show, it.latestChapterTitle)
                }
            }
        }
    }

    private fun upTvBookshelf() {
        binding.tvShelf.text = if (viewModel.inBookshelf) {
            getString(R.string.remove_from_bookshelf)
        } else {
            getString(R.string.add_to_bookshelf)
        }
    }

    private fun upGroup(groupId: Long) {
        viewModel.loadGroup(groupId) {
            if (it.isNullOrEmpty()) {
                binding.tvGroup.text = if (book?.isLocal == true) {
                    getString(R.string.group_s, getString(R.string.local_no_group))
                } else {
                    getString(R.string.group_s, getString(R.string.no_group))
                }
            } else {
                binding.tvGroup.text = getString(R.string.group_s, it)
            }
        }
    }

    private fun initViewEvent() = binding.run {
        ivCover.setOnClickListener {
            viewModel.getBook()?.let {
                showDialogFragment(
                    ChangeCoverDialog(it.name, it.author)
                )
            }
        }
        ivCover.setOnLongClickListener {
            viewModel.getBook()?.getDisplayCover()?.let { path ->
                showDialogFragment(PhotoDialog(path, isBook = true))
            }
            true
        }
        tvOrigin.setOnClickListener {
            viewModel.getBook()?.let { book ->
                if (book.isLocal) return@let
                lifecycleScope.launch(IO) {
                    val hasSource = appDb.bookSourceDao.has(book.origin)
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (!hasSource) {
                            toastOnUi(R.string.error_no_source)
                            return@withContext
                        }
                        editSourceResult.launch {
                            putExtra("sourceUrl", book.origin)
                        }
                    }
                }
            }
        }
        tvChangeSource.setOnClickListener {
            viewModel.getBook()?.let { book ->
                showDialogFragment(ChangeBookSourceDialog(book.name, book.author))
            }
        }
        tvTocView.setOnClickListener {
            // 章节列表空检查已移除：目录由 TocActivity 独立加载（bookUrl），
            // 不阻塞详情页章节数据未就绪时的入口（bug② 点目录出不来）
            viewModel.getBook()?.let { book ->
                if (!viewModel.inBookshelf) {
                    viewModel.saveBook(book) { //点击目录会保存book
                        viewModel.saveChapterList {
                            openChapterList()
                        }
                    }
                } else {
                    openChapterList()
                }
            }
        }
        tvChangeGroup.setOnClickListener {
            viewModel.getBook()?.let {
                showDialogFragment(
                    GroupSelectDialog(it.group)
                )
            }
        }
        tvAuthor.setOnClickListener {
            viewModel.getBook(false)?.let { book ->
                SourceCallBack.callBackBtn(
                    this@BookInfoActivity,
                    SourceCallBack.CLICK_AUTHOR,
                    viewModel.bookSource,
                    book,
                    null,
                    result = book.author
                ) {
                    SearchActivity.start(this@BookInfoActivity, book.author)
                }
            }
        }
        tvAuthor.setOnLongClickListener {
            viewModel.getBook(false)?.let { book ->
                SourceCallBack.callBackBtn(
                    this@BookInfoActivity,
                    SourceCallBack.LONG_CLICK_AUTHOR,
                    viewModel.bookSource,
                    book,
                    null,
                    result = book.author
                ) {
                    SearchActivity.start(this@BookInfoActivity, book.author)
                }
            }
            true
        }
        tvName.setOnClickListener {
            viewModel.getBook(false)?.let { book ->
                SourceCallBack.callBackBtn(
                    this@BookInfoActivity,
                    SourceCallBack.CLICK_BOOK_NAME,
                    viewModel.bookSource,
                    book,
                    null,
                    result = book.name
                ) {
                    SearchActivity.start(this@BookInfoActivity, book.name)
                }
            }
        }
        tvName.setOnLongClickListener {
            viewModel.getBook(false)?.let { book ->
                SourceCallBack.callBackBtn(
                    this@BookInfoActivity,
                    SourceCallBack.LONG_CLICK_BOOK_NAME,
                    viewModel.bookSource,
                    book,
                    null,
                    result = book.name
                ) {
                    SearchActivity.start(this@BookInfoActivity, book.name)
                }
            }
            true
        }
        refreshLayout?.setOnRefreshListener {
            refreshLayout.isRefreshing = false
            refreshBook()
        }
        tvSetTag?.setOnClickListener {
            viewModel.getBook()?.let { currentBook ->
                val tags = BookTagHelper.parse(currentBook.customTag)
                showComposeChoiceListDialog(
                    title = getString(R.string.set_book_tag),
                    labels = tags + getString(R.string.add)
                ) { index ->
                    if (index < tags.size) {
                        setTags(tags.filterNot { it == tags[index] })
                    } else {
                        val editText = EditText(this@BookInfoActivity).apply {
                            inputType = InputType.TYPE_CLASS_TEXT
                            setText(currentBook.customTag)
                        }
                        alert(getString(R.string.set_book_tag)) {
                            customView { editText }
                            positiveButton(R.string.ok) {
                                setTags(BookTagHelper.parse(editText.text.toString()))
                            }
                            negativeButton(R.string.cancel)
                        }
                    }
                }
            }
        }
        llReadRecord?.setOnClickListener { openBookReadRecord() }
        tvReadRecordView?.setOnClickListener { openBookReadRecord() }
    }

    private fun setSourceVariable() {
        lifecycleScope.launch {
            val source = viewModel.bookSource
            if (source == null) {
                toastOnUi(R.string.book_source_not_found)
                return@launch
            }
            val comment =
                source.getDisplayVariableComment(getString(R.string.source_variable_comment))
            val variable = withContext(IO) { source.getVariable() }
            showDialogFragment(
                VariableDialog(
                    getString(R.string.set_source_variable),
                    source.getKey(),
                    variable,
                    comment
                )
            )
        }
    }

    private fun setBookVariable() {
        lifecycleScope.launch {
            val source = viewModel.bookSource
            if (source == null) {
                toastOnUi(R.string.book_source_not_found)
                return@launch
            }
            val book = viewModel.getBook() ?: return@launch
            val variable = withContext(IO) { book.getCustomVariable() }
            val comment = source.getDisplayVariableComment(
                getString(R.string.book_variable_comment)
            )
            showDialogFragment(
                VariableDialog(
                    getString(R.string.set_book_variable),
                    book.bookUrl,
                    variable,
                    comment
                )
            )
        }
    }

    override fun setVariable(key: String, variable: String?) {
        when (key) {
            viewModel.bookSource?.getKey() -> viewModel.bookSource?.setVariable(variable)
            viewModel.bookData.value?.bookUrl -> viewModel.bookData.value?.let {
                it.putCustomVariable(variable)
                if (viewModel.inBookshelf) {
                    viewModel.saveBook(it)
                }
            }
        }
    }

    @SuppressLint("InflateParams")
    private fun deleteBook() {
        viewModel.getBook()?.let { book ->
            if (LocalConfig.bookInfoDeleteAlert) {
                alert(
                    titleResource = R.string.draw,
                    messageResource = R.string.sure_del
                ) {
                    var checkBox: CheckBox? = null
                    if (book.isLocal) {
                        checkBox = CheckBox(this@BookInfoActivity).apply {
                            setText(R.string.delete_book_file)
                            isChecked = LocalConfig.deleteBookOriginal
                        }
                        val view = LinearLayout(this@BookInfoActivity).apply {
                            setPadding(16.dpToPx(), 0, 16.dpToPx(), 0)
                            addView(checkBox)
                        }
                        customView { view }
                    }
                    yesButton {
                        if (checkBox != null) {
                            LocalConfig.deleteBookOriginal = checkBox.isChecked
                        }
                        // 删书联动（§3.7.4 红队 R5-3）：取消该书预合成任务+清空名单，防幽灵 bookKey 空转
                        io.legado.app.help.readaloud.prebuild.TtsPrebuildManager.cancelByBook(book.bookUrl)
                        SourceCallBack.callBackBook(SourceCallBack.DEL_BOOK_SHELF, viewModel.bookSource, book) //确认后删除书架
                        viewModel.delBook(LocalConfig.deleteBookOriginal) {
                            setResult(RESULT_OK)
                            finish()
                        }
                    }
                    noButton()
                }
            } else {
                SourceCallBack.callBackBook(SourceCallBack.DEL_BOOK_SHELF, viewModel.bookSource, book) //点按钮直接删除书架
                viewModel.delBook(LocalConfig.deleteBookOriginal) {
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }
    }

    private fun openChapterList() {
        viewModel.getBook()?.let {
            tocActivityResult.launch(it.bookUrl)
        }
    }

    private fun showWebFileDownloadAlert(
        onClick: ((Book) -> Unit)? = null,
    ) {
        val webFiles = viewModel.webFiles
        if (webFiles.isEmpty()) {
            toastOnUi(R.string.unexpected_web_file_data)
            return
        }
        showComposeChoiceListDialog(
            title = getString(R.string.download_and_import_file),
            labels = webFiles.map { it.name }
        ) { index ->
            val webFile = webFiles[index]
            when {
                webFile.isSupported -> {
                    /* import */
                    viewModel.importOrDownloadWebFile<Book>(webFile) {
                        onClick?.invoke(it)
                    }
                }

                webFile.isSupportDecompress -> {
                    /* 解压筛选后再选择导入项 */
                    viewModel.importOrDownloadWebFile<Uri>(webFile) { uri ->
                        viewModel.getArchiveFilesName(uri) { fileNames ->
                            if (fileNames.size == 1) {
                                viewModel.importArchiveBook(uri, fileNames[0]) {
                                    onClick?.invoke(it)
                                }
                            } else {
                                showDecompressFileImportAlert(uri, fileNames, onClick)
                            }
                        }
                    }
                }

                else -> {
                    showComposeConfirmDialog(
                        title = getString(R.string.draw),
                        message = getString(R.string.file_not_supported, webFile.name),
                        positiveText = getString(R.string.open_fun),
                        negativeText = getString(R.string.no),
                        onPositive = {
                            /* download only */
                            viewModel.importOrDownloadWebFile<Uri>(webFile) {
                                openFileUri(it, "*/*")
                            }
                        }
                    )
                }
            }
        }
    }

    private fun showDecompressFileImportAlert(
        archiveFileUri: Uri,
        fileNames: List<String>,
        success: ((Book) -> Unit)? = null,
    ) {
        if (fileNames.isEmpty()) {
            toastOnUi(R.string.unsupport_archivefile_entry)
            return
        }
        showComposeChoiceListDialog(
            title = getString(R.string.import_select_book),
            labels = fileNames
        ) { index ->
            val name = fileNames[index]
            viewModel.importArchiveBook(archiveFileUri, name) {
                success?.invoke(it)
            }
        }
    }

    private fun readBook(book: Book) {
        if (!viewModel.inBookshelf) {
            book.addType(BookType.notShelf)
            viewModel.saveBook(book) {
                viewModel.saveChapterList {
                    startReadActivity(book)
                }
            }
        } else {
            viewModel.saveBook(book) {
                startReadActivity(book)
            }
        }
    }

    private fun startReadActivity(book: Book) {
        when {
            book.isAudio -> readBookResult.launch(
                Intent(this, AudioPlayActivity::class.java)
                    .putExtra("bookUrl", book.bookUrl)
                    .putExtra("inBookshelf", viewModel.inBookshelf)
            )
            book.isVideo -> {
                // video-regression-fix-0906 AD-04 修订（用户反馈：发现列表进详情后上下部/上滑失效）：
                // 详情页直进仅在当前影片不在已注入队列中时注入单元素兜底；列表页（发现/搜索/书架）已注入的
                // 完整队列（含当前影片时）必须保留（跨影片邻居上下文），严禁单元素覆盖
                if (!VideoPlaylistHolder.containsBookUrl(book.bookUrl)) {
                    VideoPlaylistHolder.set(listOf(book.toSearchBook()), 0)
                }
                readBookResult.launch(
                    Intent(this, VideoPlayerActivity::class.java)
                        .putExtra("bookUrl", book.bookUrl)
                        .putExtra("inBookshelf", viewModel.inBookshelf)
                        // video-merged-detail-play：详情页内播放统一合并布局
                        .putExtra(PreferKey.layoutModeOverride, 1)
                )
            }

            else -> readBookResult.launch(
                Intent(
                    this,
                    if (!book.isLocal && book.isImage && AppConfig.showMangaUi) ReadMangaActivity::class.java
                    else ReadBookActivity::class.java
                )
                    .putExtra("bookUrl", book.bookUrl)
                    .putExtra("inBookshelf", viewModel.inBookshelf)
                    .putExtra("chapterChanged", chapterChanged)
            )
        }
    }

    override val oldBook: Book?
        get() = viewModel.bookData.value

    override fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>) {
        viewModel.changeTo(source, book, toc)
    }

    override fun coverChangeTo(coverUrl: String) {
        viewModel.bookData.value?.let { book ->
            book.customCoverUrl = coverUrl
            showCover(book)
            // 无条件持久化：换封面即保存到数据库，避免书不在书架时返回到书架仍是默认封面（bug⑬）
            viewModel.saveBook(book)
        }
    }

    override fun upGroup(requestCode: Int, groupId: Long) {
        upGroup(groupId)
        viewModel.getBook()?.let { book ->
            book.group = groupId
            if (viewModel.inBookshelf) {
                viewModel.saveBook(book)
            } else if (groupId > 0) {
                viewModel.addToBookshelf {
                    upTvBookshelf()
                }
            }
        }
    }

    private fun upWaitDialogStatus(isShow: Boolean) {
        val showText = getString(R.string.loading_dots)
        if (isShow) {
            waitDialog.run {
                setText(showText)
                show()
            }
        } else {
            waitDialog.dismiss()
        }
    }

     override fun onStart() {
         super.onStart()
         if (initGetter) {
             glideImageGetter.start()
         }
     }

     override fun onStop() {
         super.onStop()
         if (initGetter) {
             glideImageGetter.stop()
         }
     }

    override fun onDestroy() {
        readRecordJob?.cancel()
        destroyWeb()
        super.onDestroy()
        if (initGetter) {
            glideImageGetter.clear()
        }
    }

    private fun destroyWeb() {
        pooledWebView?.let { WebViewPool.release(it) }
        pooledWebView = null
    }

}
