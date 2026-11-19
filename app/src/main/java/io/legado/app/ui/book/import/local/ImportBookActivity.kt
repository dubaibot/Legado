package io.legado.app.ui.book.import.local

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.addTextChangedListener
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.book.import.BaseImportBookActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.book.group.GroupSelectDialog
import io.legado.app.model.localBook.LocalMediaNames
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.gone
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isUri
import io.legado.app.utils.launch
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.visible
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 导入本地书籍界面
 */
class ImportBookActivity : BaseImportBookActivity<ImportBookViewModel>(),
    PopupMenu.OnMenuItemClickListener,
    ImportBookAdapter.CallBack,
    SelectActionBar.CallBack,
    GroupSelectDialog.CallBack {

    override val viewModel by viewModels<ImportBookViewModel>()
    private val adapter by lazy { ImportBookAdapter(this, this) }
    private var scanDocJob: Job? = null
    private var confirmation: AlertDialog? = null
    private var coverInput: EditText? = null
    private var groupButton: Button? = null
    private var draftIndex = 0

    private val mediaFolder = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            try {
                if (uri.isContentScheme()) {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                viewModel.prepareImport(listOf(FileDoc.fromDir(uri))) { showConfirmation(0) }
            } catch (e: Exception) {
                toastOnUi("读取目录失败：${e.localizedMessage}")
            }
        }
    }

    private val selectCover = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            if (uri.isContentScheme()) runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.drafts.getOrNull(draftIndex)?.book?.customCoverUrl = uri.toString()
            coverInput?.setText(uri.toString())
        }
        viewModel.pendingCoverSelection = false
        if (viewModel.drafts.isNotEmpty()) showConfirmation(draftIndex)
    }

    private val selectFolder = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            AppConfig.importBookPath = uri.toString()
            initRootDoc(true)
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        if (viewModel.drafts.isEmpty()) viewModel.restoreDraftState(savedInstanceState)
        searchView.queryHint = getString(R.string.screen) + " • " + getString(R.string.local_book)
        onBackPressedDispatcher.addCallback(this) {
            if (!goBackDir()) {
                finish()
            }
        }
        lifecycleScope.launch {
            initView()
            initEvent()
            if (viewModel.drafts.isEmpty() && setBookStorage() && AppConfig.importBookPath.isNullOrBlank()) {
                AppConfig.importBookPath = AppConfig.defaultBookTreeUri
            }
            initData()
            if (viewModel.drafts.isNotEmpty() && !viewModel.saving && !viewModel.pendingCoverSelection) {
                showConfirmation(viewModel.draftIndex)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        viewModel.saveDraftState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.import_book, menu)
        menu.add(0, MENU_MEDIA_FOLDER, 0, "添加图片/视频文件夹")
        menu.add(0, MENU_CURRENT_FOLDER, 0, "将当前目录作为媒体条目导入")
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_sort_name)?.isChecked = viewModel.sort == 0
        menu.findItem(R.id.menu_sort_size)?.isChecked = viewModel.sort == 1
        menu.findItem(R.id.menu_sort_time)?.isChecked = viewModel.sort == 2
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_MEDIA_FOLDER -> mediaFolder.launch {
                mode = HandleFileContract.DIR_SYS
                title = "选择图片/视频文件夹"
            }
            MENU_CURRENT_FOLDER -> (viewModel.subDocs.lastOrNull() ?: viewModel.rootDoc)?.let {
                viewModel.prepareImport(listOf(it)) { showConfirmation(0) }
            }
            R.id.menu_select_folder -> selectFolder.launch()
            R.id.menu_scan_folder -> scanFolder()
            R.id.menu_import_file_name -> alertImportFileName()
            R.id.menu_sort_name -> upSort(0)
            R.id.menu_sort_size -> upSort(1)
            R.id.menu_sort_time -> upSort(2)
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_del_selection -> viewModel.deleteDoc(adapter.selected) {
                adapter.removeSelection()
            }
        }
        return false
    }

    override fun selectAll(selectAll: Boolean) {
        adapter.selectAll(selectAll)
    }

    override fun revertSelection() {
        adapter.revertSelection()
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onClickSelectBarMainAction() {
        viewModel.prepareImport(adapter.selected.map { it.file }) { showConfirmation(0) }
    }

    private fun showConfirmation(index: Int) {
        val draft = viewModel.drafts.getOrNull(index) ?: return
        confirmation?.dismiss()
        confirmation = null
        draftIndex = index
        viewModel.draftIndex = index
        val book = draft.book
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, padding / 2)
        }
        fun input(label: String, value: String?, changed: (String) -> Unit): EditText {
            layout.addView(TextView(this).apply { text = label })
            return EditText(this).apply {
                setText(value)
                setSingleLine()
                addTextChangedListener { changed(it.toString()) }
                layout.addView(this)
            }
        }
        layout.addView(TextView(this).apply {
            text = if (draft.media.isEmpty()) "小说 · ${book.originName}"
            else "仅导入当前目录匹配媒体，每个文件为一章，按文件名自然排序。混合目录请选择类型。"
        })
        input("名称", book.name) { book.name = it.trim() }
        input("作者", book.author) { book.author = it.trim() }
        val kinds = draft.media.mapNotNull { LocalMediaNames.kind(it.name) }.distinct()
        val typeSelect = if (kinds.size > 1) Spinner(this).apply {
            adapter = ArrayAdapter(this@ImportBookActivity, android.R.layout.simple_spinner_dropdown_item,
                kinds.map { kind ->
                    val count = draft.media.count { LocalMediaNames.kind(it.name) == kind }
                    "${if (kind == LocalMediaNames.Kind.IMAGE) "图集" else "视频"} ($count)"
                })
            setSelection(kinds.indexOf(draft.kind).coerceAtLeast(0))
            layout.addView(this)
        } else null
        groupButton = Button(this).apply {
            text = "选择分组 (${book.group})"
            setOnClickListener {
                showDialogFragment(GroupSelectDialog(book.group, IMPORT_GROUP_REQUEST))
            }
            layout.addView(this)
        }
        coverInput = input("封面（HTTP/HTTPS 链接或本地图片）", book.customCoverUrl) {
            book.customCoverUrl = it.trim().takeIf(String::isNotEmpty)
        }
        layout.addView(Button(this).apply {
            text = "选择本地封面"
            setOnClickListener {
                viewModel.pauseDraftForCoverSelection()
                confirmation?.dismiss()
                confirmation = null
                selectCover.launch { mode = HandleFileContract.IMAGE }
            }
        })
        layout.addView(TextView(this).apply {
            text = "同 URI 已导入项自动跳过；名称与作者相同时请重命名。取消将放弃本批全部草稿，最后确认后统一写入书架。"
        })
        val dialog = AlertDialog.Builder(this)
            .setTitle("确认导入 ${index + 1}/${viewModel.drafts.size}")
            .setView(ScrollView(this).apply { addView(layout) })
            .setNegativeButton(R.string.cancel) { _, _ -> viewModel.drafts.clear() }
            .setNeutralButton(if (index > 0) "上一项" else "放弃本批", null)
            .setPositiveButton(if (index == viewModel.drafts.lastIndex) "确认导入全部" else "下一项", null)
            .create()
        dialog.setOnCancelListener { viewModel.drafts.clear() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (typeSelect != null) draft.kind = kinds[typeSelect.selectedItemPosition]
                if (index > 0) {
                    dialog.dismiss()
                    showConfirmation(index - 1)
                } else {
                    viewModel.drafts.clear()
                    dialog.dismiss()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (book.name.isBlank()) {
                    toastOnUi("名称不能为空")
                    return@setOnClickListener
                }
                if (typeSelect != null) draft.kind = kinds[typeSelect.selectedItemPosition]
                val cover = book.customCoverUrl
                if (!cover.isNullOrBlank() && cover.toUri().scheme !in listOf("http", "https", "content", "file") && !cover.startsWith('/')) {
                    toastOnUi("封面请使用 HTTP/HTTPS 链接或选择本地图片")
                    return@setOnClickListener
                }
                if (index < viewModel.drafts.lastIndex) {
                    dialog.dismiss()
                    showConfirmation(index + 1)
                } else {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = false
                    dialog.setCancelable(false)
                    viewModel.confirmImport(onSuccess = {
                        dialog.dismiss()
                        adapter.selected.clear()
                        upPath()
                    }, onError = {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = true
                        dialog.setCancelable(true)
                    })
                }
            }
        }
        confirmation = dialog
        dialog.show()
    }

    override fun upGroup(requestCode: Int, groupId: Long) {
        if (requestCode == IMPORT_GROUP_REQUEST) {
            viewModel.drafts.getOrNull(draftIndex)?.book?.group = groupId
            val names = appDb.bookGroupDao.all.filter { it.groupId > 0 && groupId and it.groupId != 0L }
            groupButton?.text = names.joinToString { it.groupName }.ifBlank { "小说" }.let { "分组：$it" }
        }
    }

    private fun initView() {
        binding.layTop.setBackgroundColor(backgroundColor)
        binding.tvEmptyMsg.setText(R.string.empty_msg_import_book)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.recycledViewPool.setMaxRecycledViews(0, 15)
        binding.selectActionBar.setMainActionText(R.string.add_to_bookshelf)
        binding.selectActionBar.inflateMenu(R.menu.import_book_sel)
        binding.selectActionBar.setOnMenuItemClickListener(this)
        binding.selectActionBar.setCallBack(this)
    }

    private fun initEvent() {
        binding.tvGoBack.setOnClickListener {
            goBackDir()
        }
    }

    private fun initData() {
        viewModel.dataFlowStart = {
            initRootDoc()
        }
        lifecycleScope.launch {
            viewModel.dataFlow.conflate().collect { docs ->
                adapter.setItems(docs)
            }
        }
    }

    private fun initRootDoc(changedFolder: Boolean = false) {
        if (viewModel.rootDoc != null && !changedFolder) {
            upPath()
        } else {
            val lastPath = AppConfig.importBookPath
            if (lastPath.isNullOrBlank()) {
                binding.tvEmptyMsg.visible()
                selectFolder.launch()
            } else {
                val rootUri = if (lastPath.isUri()) {
                    lastPath.toUri()
                } else {
                    Uri.fromFile(File(lastPath))
                }
                when {
                    rootUri.isContentScheme() -> initRootPath(rootUri)
                    else -> initRootPath(rootUri.path!!)
                }
            }
        }
    }

    private fun initRootPath(rootUri: Uri) {
        kotlin.runCatching {
            val doc = DocumentFile.fromTreeUri(this, rootUri)
            if (doc == null || doc.name.isNullOrEmpty() || !doc.isDirectory) {
                binding.tvEmptyMsg.visible()
                selectFolder.launch()
            } else {
                viewModel.subDocs.clear()
                viewModel.rootDoc = FileDoc.fromDocumentFile(doc)
                upPath()
            }
        }.onFailure {
            binding.tvEmptyMsg.visible()
            selectFolder.launch()
        }
    }

    private fun initRootPath(path: String) {
        binding.tvEmptyMsg.visible()
        PermissionsCompat.Builder()
            .addPermissions(*Permissions.Group.STORAGE)
            .rationale(R.string.tip_perm_request_storage)
            .onGranted {
                kotlin.runCatching {
                    val file = File(path)
                    if (!file.isDirectory) {
                        binding.tvEmptyMsg.visible()
                        selectFolder.launch()
                    } else {
                        viewModel.subDocs.clear()
                        viewModel.rootDoc = FileDoc.fromFile(file)
                        upPath()
                    }
                }.onFailure {
                    binding.tvEmptyMsg.visible()
                    selectFolder.launch()
                }
            }
            .request()
    }

    private fun upSort(sort: Int) {
        viewModel.sort = sort
        putPrefInt(PreferKey.localBookImportSort, sort)
        if (scanDocJob?.isActive != true) {
            viewModel.dataCallback?.upAdapter()
        }
    }

    @Synchronized
    private fun upPath() {
        binding.tvGoBack.isEnabled = viewModel.subDocs.isNotEmpty()
        viewModel.rootDoc?.let {
            scanDocJob?.cancel()
            upDocs(it)
        }
    }

    private fun upDocs(rootDoc: FileDoc) {
        binding.tvEmptyMsg.gone()
        var path = rootDoc.name + File.separator
        var lastDoc = rootDoc
        for (doc in viewModel.subDocs) {
            lastDoc = doc
            path = path + doc.name + File.separator
        }
        binding.tvPath.text = path
        adapter.selected.clear()
        adapter.clearItems()
        viewModel.loadDoc(lastDoc)
    }

    /**
     * 扫描当前文件夹及所有子文件夹
     */
    private fun scanFolder() {
        viewModel.rootDoc?.let { doc ->
            adapter.clearItems()
            val lastDoc = viewModel.subDocs.lastOrNull() ?: doc
            binding.refreshProgressBar.isAutoLoading = true
            scanDocJob?.cancel()
            scanDocJob = lifecycleScope.launch(IO) {
                viewModel.scanDoc(lastDoc)
                withContext(Main) {
                    binding.refreshProgressBar.isAutoLoading = false
                }
            }
        }
    }

    private fun alertImportFileName() {
        alert(R.string.import_file_name) {
            setMessage("""使用js处理文件名变量src，将书名作者分别赋值到变量name author""")
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "js"
                editView.setText(AppConfig.bookImportFileName)
            }
            customView { alertBinding.root }
            okButton {
                AppConfig.bookImportFileName = alertBinding.editView.text?.toString()
            }
            cancelButton()
        }
    }

    @Synchronized
    override fun nextDoc(fileDoc: FileDoc) {
        viewModel.subDocs.add(fileDoc)
        upPath()
    }

    @Synchronized
    private fun goBackDir(): Boolean {
        return if (viewModel.subDocs.isNotEmpty()) {
            viewModel.subDocs.removeAt(viewModel.subDocs.lastIndex)
            upPath()
            true
        } else {
            false
        }
    }

    override fun onSearchTextChange(newText: String?) {
        viewModel.updateCallBackFlow(newText)
    }

    override fun upCountView() {
        binding.selectActionBar.upCountView(adapter.selected.size, adapter.checkableCount)
    }

    override fun startRead(fileDoc: FileDoc) {
        if (!ArchiveUtils.isArchive(fileDoc.name)) {
            appDb.bookDao.getBook(fileDoc.toString())?.let {
                startReadBook(it)
            }
        } else {
            onArchiveFileClick(fileDoc)
        }
    }

    companion object {
        private const val MENU_MEDIA_FOLDER = 0x4c0101
        private const val MENU_CURRENT_FOLDER = 0x4c0102
        private const val IMPORT_GROUP_REQUEST = 0x4c0103
    }

}
