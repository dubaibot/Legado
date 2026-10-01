package io.legado.app.ui.rss.read

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.content.res.Configuration
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.SystemClock
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.size
import com.script.rhino.runScriptWithContext
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppConst.imagePathKey
import io.legado.app.constant.AppLog
import io.legado.app.databinding.ActivityRssReadBinding
import io.legado.app.help.WebCacheManager
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.CookieManager
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.rss.favorites.RssFavoritesDialog
import io.legado.app.utils.ACache
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.isTrue
import io.legado.app.utils.keepScreenOn
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.openUrl
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.setTintMutate
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.startActivity
import io.legado.app.utils.textArray
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.toggleSystemBar
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import org.apache.commons.text.StringEscapeUtils
import org.jsoup.Jsoup
import org.json.JSONArray
import splitties.views.bottomPadding
import java.io.ByteArrayInputStream
import java.util.regex.PatternSyntaxException
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.rss.article.ReadRecordDialog
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.utils.StartActivityContract
import kotlinx.coroutines.runBlocking
import androidx.core.net.toUri
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.webView.WebJsExtensions.Companion.basicJs
import io.legado.app.help.webView.WebJsExtensions.Companion.nameBasic
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache
import io.legado.app.help.webView.WebJsExtensions.Companion.nameJava
import io.legado.app.help.webView.WebJsExtensions.Companion.nameSource
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebJsExtensions.Companion.JS_INJECTION
import io.legado.app.help.webView.WebJsExtensions.Companion.JS_URL
import io.legado.app.help.webView.WebJsExtensions.Companion.nameUrl
import io.legado.app.help.webView.WebViewPool
import io.legado.app.help.webView.WebViewPool.BLANK_HTML
import io.legado.app.help.webView.WebViewPool.DATA_HTML
import io.legado.app.model.Download
import kotlinx.coroutines.Dispatchers.IO
import java.lang.ref.WeakReference
import splitties.systemservices.powerManager
import java.net.URLDecoder
import androidx.core.graphics.createBitmap
import io.legado.app.help.webView.LocalFileBridge

/**
 * rss阅读界面
 */
class ReadRssActivity : VMBaseActivity<ActivityRssReadBinding, ReadRssViewModel>(),
    RssFavoritesDialog.Callback {

    override val binding by viewBinding(ActivityRssReadBinding::inflate)
    override val viewModel by viewModels<ReadRssViewModel>()

    private lateinit var pooledWebView: PooledWebView
    private lateinit var currentWebView: WebView

    private var starMenuItem: MenuItem? = null
    private var ttsMenuItem: MenuItem? = null
    private var isFullscreen = false
    private var wasScreenOff = false
    private var customWebViewCallback: WebChromeClient.CustomViewCallback? = null
    private var interfaceInjected: String? = null
    private var needClearHistory = true

    // 模块②：网页 <input type="file"> 文件选择回调
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val openFileChooser = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        fileChooserCallback?.onReceiveValue(uris.toTypedArray())
        fileChooserCallback = null
    }

    // 模块⑥：图片批量选择状态
    private var isSelectMode = false
    private val selectedImages = LinkedHashSet<String>()
    private var totalImgCount = 0
    private var pendingSaveImages: List<String>? = null

    private val selectImageDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ACache.get().put(imagePathKey, uri.toString())
            val pending = pendingSaveImages
            if (pending != null) {
                pendingSaveImages = null
                viewModel.saveImages(pending, uri)
            } else {
                viewModel.saveImage(it.value, uri)
            }
        }
    }

    private val rssJsExtensions by lazy { RssJsExtensions(this, viewModel.rssSource) }

    private val refreshNameList: MutableList<String> by lazy { mutableListOf() }
    private fun refresh() {
        if (viewModel.rssSource?.singleUrl == true) {
            currentWebView.reload()
            return
        }
        currentWebView.title?.let {
            refreshNameList.add(it)
        }
        viewModel.rssArticle?.let {
            start(this@ReadRssActivity,true, it.origin, it.title, it.link)
        } ?: run {
            viewModel.initData(intent)
        }
    }
    private val editSourceResult = registerForActivityResult(
        StartActivityContract(RssSourceEditActivity::class.java)
    ) {
        if (it.resultCode == RESULT_OK) {
            refresh()
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        pooledWebView = WebViewPool.acquire(this)
        currentWebView = pooledWebView.realWebView
        binding.webViewContainer.addView(currentWebView)
        viewModel.upStarMenuData.observe(this) { upStarMenu() }
        viewModel.upTtsMenuData.observe(this) { upTtsMenu(it) }
        viewModel.upTitleData.observe(this) { binding.titleBar.title = it }
        initView()
        initWebView()
        initLiveData()
        initSelectActionBar()
        viewModel.initData(intent)
        currentWebView.clearHistory()
        onBackPressedDispatcher.addCallback(this) {
            // 模块⑥：选择模式下先退出选择模式
            if (isSelectMode) {
                exitSelectMode()
                return@addCallback
            }
            if (binding.customWebView.size > 0) { //关闭全屏
                customWebViewCallback?.onCustomViewHidden()
                return@addCallback
            }
            if (currentWebView.canGoBack()) {
                val list = currentWebView.copyBackForwardList() //获取历史列表
                val size = list.size
                if (size == 1) {
                    finish()
                    return@addCallback
                }
                val currentIndex = list.currentIndex
                val currentItem = list.currentItem
                val currentUrl = currentItem?.originalUrl ?: BLANK_HTML
                val currentTitle = currentItem?.title
                //从后往前找，找到第一个不同链接的页面，计算需要回退多少步 避免刷新后导致返回不灵
                var steps = 1
                for (i in currentIndex - 1 downTo 0) {
                    val item = list.getItemAtIndex(i)
                    val itemTitle = item.title
                    val index = refreshNameList.indexOf(itemTitle)
                    if (index != -1) {
                        refreshNameList.removeAt(index)
                        steps++
                        continue
                    }
                    val itemUrl = item.originalUrl
                    if (itemUrl == BLANK_HTML) {
                        finish()
                        return@addCallback
                    }
                    if (itemUrl != currentUrl || itemTitle != currentTitle) {
                        break
                    }
                    if (currentUrl == DATA_HTML) {
                        break
                    }
                    steps++
                }
                if (steps == size) {
                    finish()
                    return@addCallback
                }
                currentWebView.goBackOrForward(-steps)
                return@addCallback
            }
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        binding.progressBar.visible()
        binding.progressBar.setDurProgress(30)
        setIntent(intent)
        viewModel.initData(intent)
    }

    @Suppress("DEPRECATION")
    @SuppressLint("SwitchIntDef")
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        when (newConfig.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> {
                window.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }

            Configuration.ORIENTATION_PORTRAIT -> {
                window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                window.addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
            }
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.rss_read, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        starMenuItem = menu.findItem(R.id.menu_rss_star)
        ttsMenuItem = menu.findItem(R.id.menu_aloud)
        upStarMenu()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_login)?.isVisible = !viewModel.rssSource?.loginUrl.isNullOrBlank()
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_rss_refresh -> refresh()

            R.id.menu_rss_star -> {
                viewModel.addFavorite()
                viewModel.rssArticle?.let {
                    showDialogFragment(RssFavoritesDialog(it))
                }
            }

            R.id.menu_share_it -> {
                currentWebView.url?.let {
                    share(it)
                } ?: viewModel.rssArticle?.let {
                    share(it.link)
                } ?: toastOnUi(R.string.null_url)
            }

            R.id.menu_aloud -> readAloud()
            R.id.menu_login -> startActivity<SourceLoginActivity> {
                putExtra("type", "rssSource")
                putExtra("key", viewModel.rssSource?.sourceUrl)
            }

            R.id.menu_browser_open -> currentWebView.url?.let {
                openUrl(it)
            } ?: toastOnUi("url null")
            R.id.menu_edit_source -> viewModel.rssSource?.sourceUrl?.let {
                editSourceResult.launch {
                    putExtra("sourceUrl", it)
                }
            }
            R.id.menu_log -> showDialogFragment<AppLogDialog>()
            R.id.menu_read_record -> showDialogFragment(ReadRecordDialog(viewModel.rssSource?.sourceUrl))
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun updateFavorite(title: String?, group: String?) {
        viewModel.rssArticle?.let {
            if (title != null) {
                it.title = title
            }
            if (group != null) {
                it.group = group
            }
        }
        viewModel.updateFavorite()
    }

    override fun deleteFavorite() {
        viewModel.delFavorite()
    }

    private fun initView() {
        binding.root.setOnApplyWindowInsetsListenerCompat { view, windowInsets ->
            val typeMask = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            val insets = windowInsets.getInsets(typeMask)
            view.bottomPadding = insets.bottom
            windowInsets
        }
    }

    // 模块⑥：初始化选择操作条
    private fun initSelectActionBar() {
        binding.selectActionBar.setMainActionText("保存所选")
        binding.selectActionBar.inflateMenu(R.menu.rss_read_select)
        binding.selectActionBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_save_selected -> saveSelectedImages()
            }
            true
        }
        binding.selectActionBar.setCallBack(object : SelectActionBar.CallBack {
            override fun selectAll(selectAll: Boolean) {
                if (selectAll) {
                    currentWebView.evaluateJavascript(SELECT_ALL_JS, null)
                } else {
                    currentWebView.evaluateJavascript(CLEAR_SELECT_JS, null)
                }
            }

            override fun revertSelection() {
                currentWebView.evaluateJavascript(REVERT_SELECT_JS, null)
            }

            override fun onClickSelectBarMainAction() {
                saveSelectedImages()
            }
        })
    }

    // 模块⑥：进入批量选择模式
    private fun enterSelectMode() {
        isSelectMode = true
        selectedImages.clear()
        binding.selectActionBar.visible()
        currentWebView.evaluateJavascript("document.querySelectorAll('img').length") { result ->
            totalImgCount = result.trim().toIntOrNull() ?: 0
            upSelectCount()
        }
        currentWebView.evaluateJavascript(SELECT_JS, null)
    }

    // 模块⑥：退出批量选择模式
    private fun exitSelectMode() {
        isSelectMode = false
        selectedImages.clear()
        totalImgCount = 0
        binding.selectActionBar.gone()
        currentWebView.evaluateJavascript(CLEAR_SELECT_JS, null)
    }

    // 模块⑥：更新选择计数
    private fun upSelectCount() {
        binding.selectActionBar.upCountView(selectedImages.size, totalImgCount)
    }

    // 模块⑥：保存所选图片
    private fun saveSelectedImages() {
        if (selectedImages.isEmpty()) {
            toastOnUi("未选择图片")
            return
        }
        pendingSaveImages = selectedImages.toList()
        val default = arrayListOf<SelectItem<Int>>()
        val path = ACache.get().getAsString(imagePathKey)
        if (!path.isNullOrEmpty()) {
            default.add(SelectItem(path, -1))
        }
        selectImageDir.launch {
            otherActions = default
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        binding.progressBar.fontColor = accentColor
        currentWebView.webChromeClient = CustomWebChromeClient()
        //添加屏幕方向控制，网页关闭，openUI
        currentWebView.addJavascriptInterface(JSInterface(this), nameBasic)
        //模块①：注入本地文件桥接
        currentWebView.addJavascriptInterface(
            LocalFileBridge(this) { currentWebView }, LocalFileBridge.NAME
        )
        //模块⑥：注入图片选择桥接
        currentWebView.addJavascriptInterface(ImageSelectBridge(), "lycImgSelect")
        currentWebView.webViewClient = CustomWebViewClient()
        currentWebView.setOnLongClickListener {
            // 模块⑥：选择模式下屏蔽长按菜单
            if (isSelectMode) return@setOnLongClickListener true
            val hitTestResult = currentWebView.hitTestResult
            if (hitTestResult.type == WebView.HitTestResult.IMAGE_TYPE ||
                hitTestResult.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                hitTestResult.extra?.let { webPic ->
                    selector(
                        arrayListOf(
                            SelectItem(getString(R.string.action_save), "save"),
                            SelectItem(getString(R.string.select_folder), "selectFolder"),
                            SelectItem("批量选择", "selectBatch")
                        )
                    ) { _, charSequence, _ ->
                        when (charSequence.value) {
                            "save" -> saveImage(webPic)
                            "selectFolder" -> selectSaveFolder(null)
                            "selectBatch" -> enterSelectMode()
                        }
                    }
                    return@setOnLongClickListener true
                }
            }
            return@setOnLongClickListener false
        }
        currentWebView.setDownloadListener { url, _, contentDisposition, _, _ ->
            var fileName = URLUtil.guessFileName(url, contentDisposition, null)
            fileName = URLDecoder.decode(fileName, "UTF-8")
            currentWebView.longSnackbar(fileName, getString(R.string.action_download)) {
                Download.start(this, url, fileName)
            }
        }
    }

    private fun saveImage(webPic: String) {
        val path = ACache.get().getAsString(imagePathKey)
        if (path.isNullOrEmpty()) {
            selectSaveFolder(webPic)
        } else {
            viewModel.saveImage(webPic, path.toUri())
        }
    }

    private fun selectSaveFolder(webPic: String?) {
        val default = arrayListOf<SelectItem<Int>>()
        val path = ACache.get().getAsString(imagePathKey)
        if (!path.isNullOrEmpty()) {
            default.add(SelectItem(path, -1))
        }
        selectImageDir.launch {
            otherActions = default
            value = webPic
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initLiveData() {
        viewModel.contentLiveData.observe(this) { content ->
            viewModel.rssArticle?.let {
                upWebviewSettings()
                initJavascriptInterface()
                val rssSource = viewModel.rssSource
                val html = viewModel.clHtml(content, rssSource?.style)
                val url = NetworkUtils.getAbsoluteURL(it.origin, it.link).substringBefore("@js")
                val baseUrl = if (rssSource?.loadWithBaseUrl == false) null else url
                currentWebView.loadDataWithBaseURL(
                    baseUrl, html, "text/html", "utf-8", url
                )
            }
        }
        viewModel.urlLiveData.observe(this) { urlState ->
            upWebviewSettings(urlState.getUserAgent())
            initJavascriptInterface()
            CookieManager.applyToWebView(urlState.url)
            currentWebView.loadUrl(urlState.url, urlState.headerMap)
        }
        viewModel.htmlLiveData.observe(this) { html ->
            viewModel.rssSource?.let {
                upWebviewSettings()
                initJavascriptInterface()
                val baseUrl = if (it.loadWithBaseUrl) it.sourceUrl else null
                currentWebView.loadDataWithBaseURL(
                    baseUrl, html, "text/html", "utf-8", it.sourceUrl
                )
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun upWebviewSettings(userAgent: String? = null) {
        viewModel.rssSource?.let { s ->
            currentWebView.settings.run {
                userAgentString = userAgent ?: viewModel.headerMap[AppConst.UA_NAME] ?: AppConfig.userAgent
                javaScriptEnabled = s.enableJs
                cacheMode = if (s.cacheFirst) WebSettings.LOAD_CACHE_ELSE_NETWORK else WebSettings.LOAD_DEFAULT
            }
        }
    }

    private fun initJavascriptInterface() {
        viewModel.rssSource?.let {
            if (interfaceInjected != it.sourceUrl) {
                interfaceInjected = it.sourceUrl
                if (!viewModel.hasPreloadJs) return
                val webJsExtensions = WebJsExtensions(it, this, currentWebView)
                currentWebView.addJavascriptInterface(webJsExtensions, nameJava)
                currentWebView.addJavascriptInterface(it, nameSource)
                currentWebView.addJavascriptInterface(WebCacheManager, nameCache)
            }
        }
    }

    private fun upStarMenu() {
        starMenuItem?.isVisible = viewModel.rssArticle != null
        if (viewModel.rssStar != null) {
            starMenuItem?.setIcon(R.drawable.ic_star)
            starMenuItem?.setTitle(R.string.in_favorites)
        } else {
            starMenuItem?.setIcon(R.drawable.ic_star_border)
            starMenuItem?.setTitle(R.string.out_favorites)
        }
        starMenuItem?.icon?.setTintMutate(primaryTextColor)
    }

    private fun upTtsMenu(isPlaying: Boolean) {
        lifecycleScope.launch {
            if (isPlaying) {
                ttsMenuItem?.setIcon(R.drawable.ic_stop_black_24dp)
                ttsMenuItem?.setTitle(R.string.aloud_stop)
            } else {
                ttsMenuItem?.setIcon(R.drawable.ic_volume_up)
                ttsMenuItem?.setTitle(R.string.read_aloud)
            }
            ttsMenuItem?.icon?.setTintMutate(primaryTextColor)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun readAloud() {
        if (viewModel.tts?.isSpeaking == true) {
            viewModel.tts?.stop()
            upTtsMenu(false)
        } else {
            currentWebView.settings.javaScriptEnabled = true
            currentWebView.evaluateJavascript("document.documentElement.outerHTML") {
                val html = StringEscapeUtils.unescapeJson(it).replace("^\"|\"$".toRegex(), "")
                viewModel.readAloud(
                    Jsoup.parse(html).textArray().joinToString("\n")
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (powerManager.isInteractive) {
            wasScreenOff = false
            currentWebView.onPause()
        } else {
            wasScreenOff = true
        }
    }

    override fun onResume() {
        super.onResume()
        if (!wasScreenOff) {
            currentWebView.onResume()
        }
    }

    override fun onDestroy() {
        WebViewPool.release(pooledWebView)
        super.onDestroy()
    }

    // 模块⑥：图片选择桥接（JS 点击图片后回调）
    inner class ImageSelectBridge {
        @JavascriptInterface
        fun onUpdate(json: String?) {
            runOnUiThread {
                selectedImages.clear()
                json?.let {
                    runCatching {
                        val arr = JSONArray(it)
                        for (i in 0 until arr.length()) {
                            selectedImages.add(arr.getString(i))
                        }
                    }
                }
                upSelectCount()
            }
        }
    }

    @Suppress("unused")
    private class JSInterface(activity: ReadRssActivity) {
        private val activityRef: WeakReference<ReadRssActivity> = WeakReference(activity)
        @JavascriptInterface
        fun lockOrientation(orientation: String) {
            val ctx = activityRef.get()
            if (ctx != null && ctx.isFullscreen && !ctx.isFinishing && !ctx.isDestroyed) {
                ctx.runOnUiThread {
                    ctx.requestedOrientation = when (orientation) {
                        "portrait", "portrait-primary" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        "portrait-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                        "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE //横屏且受重力控制正反
                        "landscape-primary" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE //正向横屏
                        "landscape-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE //反向横屏
                        "any", "unspecified" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
                        else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }
            }
        }

        @JavascriptInterface
        fun onCloseRequested() {
            val ctx = activityRef.get()
            if (ctx != null && !ctx.isFinishing && !ctx.isDestroyed) {
                ctx.runOnUiThread {
                    ctx.finish()
                }
            }
        }
    }

    inner class CustomWebChromeClient : WebChromeClient() {
        override fun getDefaultVideoPoster(): Bitmap {
            return super.getDefaultVideoPoster() ?: createBitmap(100, 100)
        }

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            binding.progressBar.setDurProgress(newProgress)
            binding.progressBar.gone(newProgress == 100)
        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            isFullscreen = true
            binding.llView.invisible()
            binding.customWebView.addView(view)
            customWebViewCallback = callback
            keepScreenOn(true)
            toggleSystemBar(false)
            if (viewModel.rssSource?.enableJs == false) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
            }
        }

        override fun onHideCustomView() {
            isFullscreen = false
            binding.customWebView.removeAllViews()
            binding.llView.visible()
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            keepScreenOn(false)
            toggleSystemBar(true)
        }

        /* 覆盖window.close() */
        override fun onCloseWindow(window: WebView?) {
            finish()
        }

        /* 监听网页日志 */
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            viewModel.rssSource?.let { source ->
                if (source.showWebLog) {
                    val messageLevel = consoleMessage.messageLevel().name
                    val message = consoleMessage.message()
                    AppLog.put("${source.getTag()}${messageLevel}: $message",
                        NoStackTraceException("\n${message}\n- Line ${consoleMessage.lineNumber()} of ${consoleMessage.sourceId()}"))
                    return true
                }
            }
            return false
        }

        //模块②：网页表单 <input type="file"> 支持
        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: WebChromeClient.FileChooserParams?
        ): Boolean {
            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = filePathCallback
            val types = fileChooserParams?.acceptTypes?.takeIf { it.isNotEmpty() } ?: arrayOf("*/*")
            openFileChooser.launch(types)
            return true
        }
    }

    inner class CustomWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView, request: WebResourceRequest
        ): Boolean {
            return shouldOverrideUrlLoading(request.url)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "KotlinRedundantDiagnosticSuppress")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            return shouldOverrideUrlLoading(url.toUri())
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (needClearHistory) {
                needClearHistory = false
                currentWebView.clearHistory() //清除历史
            }
            super.onPageStarted(view, url, favicon)
            currentWebView.evaluateJavascript(basicJs, null)
        }

        private var jsInjected = false
        /**
         * 如果有黑名单,黑名单匹配返回空白,
         * 没有黑名单再判断白名单,在白名单中的才通过,
         * 都没有不做处理
         */
        override fun shouldInterceptRequest(
            view: WebView, request: WebResourceRequest
        ): WebResourceResponse? {
            val url = request.url.toString()
            val source = viewModel.rssSource ?: return super.shouldInterceptRequest(view, request)
            if (request.isForMainFrame) {
                if (viewModel.hasPreloadJs) {
                    jsInjected = false
                    if (url.startsWith("data:text/html;") || request.method == "POST") {
                        return super.shouldInterceptRequest(view, request)
                    }
                    return runBlocking(IO) {
                        getModifiedContentWithJs(url, request) ?: super.shouldInterceptRequest(view, request)
                    }
                }
            } else if (!jsInjected && url == nameUrl) {
                jsInjected = true
                val preloadJs = source.preloadJs ?: ""
                return WebResourceResponse(
                    "text/javascript",
                    "utf-8",
                    ByteArrayInputStream("(() => {$JS_INJECTION\n$preloadJs\n})();".toByteArray())
                )
            }
            val blacklist = source.contentBlacklist?.splitNotBlank(",")
            if (!blacklist.isNullOrEmpty()) {
                blacklist.forEach {
                    try {
                        if (url.startsWith(it) || url.matches(it.toRegex())) {
                            return createEmptyResource()
                        }
                    } catch (e: PatternSyntaxException) {
                        AppLog.put("黑名单规则正则语法错误 源名称:${source.sourceName} 正则:$it", e)
                    }
                }
            } else {
                val whitelist = source.contentWhitelist?.splitNotBlank(",")
                if (!whitelist.isNullOrEmpty()) {
                    whitelist.forEach {
                        try {
                            if (url.startsWith(it) || url.matches(it.toRegex())) {
                                return super.shouldInterceptRequest(view, request)
                            }
                        } catch (e: PatternSyntaxException) {
                            val msg = "白名单规则正则语法错误 源名称:${source.sourceName} 正则:$it"
                            AppLog.put(msg, e)
                        }
                    }
                    return createEmptyResource()
                }
            }
            return super.shouldInterceptRequest(view, request)
        }

        private suspend fun getModifiedContentWithJs(url: String, request: WebResourceRequest): WebResourceResponse? {
            try {
                val cookie = webCookieManager.getCookie(url)
                val res = okHttpClient.newCallResponse {
                    url(url)
                    method(request.method, null)
                    if (!cookie.isNullOrEmpty()) {
                        addHeader("Cookie", cookie)
                    }
                    request.requestHeaders?.forEach { (key, value) ->
                        addHeader(key, value)
                    }
                }
                res.headers("Set-Cookie").forEach { setCookie ->
                    webCookieManager.setCookie(url, setCookie)
                }
                val body = res.body
                val contentType = body.contentType()
                val mimeType = contentType?.toString()?.substringBefore(";") ?: "text/html"
                val charset = contentType?.charset() ?: Charsets.UTF_8
                val charsetSre = charset.name()
                val bodyText = body.text().let { originalText ->
                    val headIndex = originalText.indexOf("<head", ignoreCase = true)
                    if (headIndex >= 0) {
                        val closingHeadIndex = originalText.indexOf('>', startIndex = headIndex)
                        if (closingHeadIndex >= 0) {
                            val insertPos = closingHeadIndex + 1
                            StringBuilder(originalText).insert(insertPos, JS_URL).toString()
                        } else {
                            originalText
                        }
                    } else {
                        originalText
                    }
                }
                return WebResourceResponse(
                    mimeType,
                    charsetSre,
                    ByteArrayInputStream(bodyText.toByteArray(charset))
                )
            } catch (_: Exception) {
                return null
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            view.title?.let { title ->
                if (title != url
                    && title != view.url
                    && title.isNotBlank()
                    && url != BLANK_HTML
                    && !url.contains(title)) {
                    binding.titleBar.title = title
                } else {
                    binding.titleBar.title = viewModel.upTitleData.value
                }
            }
            viewModel.rssSource?.injectJs?.let {
                if (it.isNotBlank()) {
                    view.evaluateJavascript(it, null)
                }
            }
            // 模块⑥：页面加载完成后若在选择模式，重新注入选择 JS
            if (isSelectMode) {
                currentWebView.evaluateJavascript(SELECT_JS, null)
            }
        }

        private fun createEmptyResource(): WebResourceResponse {
            return WebResourceResponse(
                "text/plain", "utf-8", ByteArrayInputStream("".toByteArray())
            )
        }

        private fun shouldOverrideUrlLoading(url: Uri): Boolean {
            viewModel.rssSource?.let { source ->
                source.shouldOverrideUrlLoading?.takeUnless(String::isNullOrBlank)?.let { js ->
                    val startTime = SystemClock.uptimeMillis()
                    val result = runCatching {
                        runScriptWithContext(lifecycleScope.coroutineContext) {
                            source.evalJS(js) {
                                put("java", rssJsExtensions)
                                put("url", url.toString())
                            }.toString()
                        }
                    }.onFailure {
                        AppLog.put("${source.getTag()}: url跳转拦截js出错", it)
                    }.getOrNull()
                    if (SystemClock.uptimeMillis() - startTime > 99) {
                        AppLog.put("${source.getTag()}: url跳转拦截js执行耗时过长")
                    }
                    if (result.isTrue()) return true
                }
            }
            return handleCommonSchemes(url)
        }

        private fun handleCommonSchemes(url: Uri): Boolean {
            return when (url.scheme) {
                "http", "https" -> false
                "legado", "yuedu" -> {
                    startActivity<OnLineImportActivity> { data = url }
                    true
                }

                else -> {
                    binding.root.longSnackbar(R.string.jump_to_another_app, R.string.confirm) {
                        openUrl(url)
                    }
                    true
                }
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(
            view: WebView?, handler: SslErrorHandler?, error: SslError?
        ) {
            handler?.proceed()
        }

    }

    companion object {
        // 模块⑥：选择模式 JS
        private const val SELECT_JS =
            "(function(){" +
                "if(window.__lycSelectInit)return;" +
                "window.__lycSelectInit=true;" +
                "window.__lycSelectMap={};" +
                "window.__lycSelectUpdate=function(){" +
                "var arr=[];for(var k in window.__lycSelectMap){if(window.__lycSelectMap[k])arr.push(k);}" +
                "window.lycImgSelect.onUpdate(JSON.stringify(arr));" +
                "};" +
                "document.querySelectorAll('img').forEach(function(img){" +
                "img.style.cursor='pointer';" +
                "img.addEventListener('click',function(e){" +
                "e.preventDefault();e.stopPropagation();" +
                "var u=img.src;" +
                "if(window.__lycSelectMap[u]){window.__lycSelectMap[u]=false;img.style.outline='none';}" +
                "else{window.__lycSelectMap[u]=true;img.style.outline='3px solid #FF5722';}" +
                "window.__lycSelectUpdate();" +
                "});" +
                "});" +
                "})();"

        private const val SELECT_ALL_JS =
            "(function(){" +
                "document.querySelectorAll('img').forEach(function(img){" +
                "window.__lycSelectMap[img.src]=true;" +
                "img.style.outline='3px solid #FF5722';" +
                "});" +
                "window.__lycSelectUpdate();" +
                "})();"

        private const val CLEAR_SELECT_JS =
            "(function(){" +
                "document.querySelectorAll('img').forEach(function(img){" +
                "window.__lycSelectMap[img.src]=false;" +
                "img.style.outline='none';" +
                "});" +
                "window.__lycSelectUpdate();" +
                "})();"

        private const val REVERT_SELECT_JS =
            "(function(){" +
                "document.querySelectorAll('img').forEach(function(img){" +
                "var u=img.src;" +
                "if(window.__lycSelectMap[u]){window.__lycSelectMap[u]=false;img.style.outline='none';}" +
                "else{window.__lycSelectMap[u]=true;img.style.outline='3px solid #FF5722';}" +
                "});" +
                "window.__lycSelectUpdate();" +
                "})();"

        fun start(context: Context, singleTop: Boolean, origin: String, title: String? = null, url: String? = null, startHtml: String? = null) {
            context.startActivity<ReadRssActivity> {
                putExtra("origin", origin)
                putExtra("title", title)
                putExtra("openUrl", url)
                putExtra("startHtml", startHtml)
                if (singleTop) {
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            }
        }

        /**
         * 知晓rssArticle的打开
         */
        fun start(context: Context, origin: String, title: String?, link: String, sort: String) {
            context.startActivity<ReadRssActivity> {
                putExtra("origin", origin)
                putExtra("title", title)
                putExtra("link", link)
                putExtra("sort", sort)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) //栈顶复用
            }
        }

        private val webCookieManager by lazy { android.webkit.CookieManager.getInstance() }
    }

}
