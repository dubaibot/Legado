package io.legado.app.ui.main.explore

import android.annotation.SuppressLint
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatSpinner
import androidx.core.view.children
import androidx.core.view.isVisible
import com.google.android.flexbox.FlexboxLayout
import com.script.rhino.runScriptWithContext
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.data.entities.rule.ExploreKind.Type
import io.legado.app.databinding.ItemFilletCompleteTextBinding
import io.legado.app.databinding.ItemFilletSelectorSingleBinding
import io.legado.app.databinding.ItemFilletTextBinding
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.ui.widget.text.AccentTextView
import io.legado.app.utils.InfoMap
import io.legado.app.utils.activity
import io.legado.app.utils.dpToPx
import io.legado.app.utils.removeLastElement
import io.legado.app.utils.setSelectionSafely
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.visible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 发现面板控件渲染器,从ExploreAdapter抽取,供传统面板与筛选设置区复用
 * TRADITIONAL:传统面板,url点击回调,其余控件完整交互执行action
 * PANEL_ADD:面板项点击仅回调,select点开自绘选项列表回调具体选项,text/toggle只读
 * FILTER_SETTINGS:控件完整可交互,select/text/toggle改动写infoMap并执行action(改动即存)
 */
class ExploreKindRenderer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val callback: Callback
) {
    interface Callback {
        fun onKindClick(view: View, sourceUrl: String, kind: ExploreKind)
        fun onSelectOptionClick(view: View, sourceUrl: String, kind: ExploreKind, option: String)
        fun onRequestRefresh(sourceUrl: String)
    }

    enum class Mode { TRADITIONAL, PANEL_ADD, FILTER_SETTINGS }

    companion object {
        fun saveInfoMaps() {
            ExploreAdapter.exploreInfoMapList.snapshot()
                .filter { (_, infoMap) -> infoMap.needSave }
                .forEach { (_, infoMap) ->
                    infoMap.saveNow()
                }
        }
    }

    private val recycler = arrayListOf<TextView>()
    private val textRecycler = arrayListOf<AutoCompleteTextView>()
    private val selectRecycler = arrayListOf<LinearLayout>()
    private var lastClickTime: Long = 0

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    fun render(
        flexbox: FlexboxLayout,
        kinds: List<ExploreKind>,
        sourceUrl: String,
        mode: Mode
    ) {
        if (kinds.isEmpty()) {
            return
        }
        val source by lazy { appDb.bookSourceDao.getBookSource(sourceUrl) }
        val infoMap by lazy {
            ExploreAdapter.exploreInfoMapList[sourceUrl] ?: InfoMap(sourceUrl).also {
                ExploreAdapter.exploreInfoMapList.put(sourceUrl, it)
            }
        }
        val sourceJsExtensions by lazy {
            SourceLoginJsExtensions(context as? AppCompatActivity, source,
                callback = object : SourceLoginJsExtensions.Callback {
                    override fun upUiData(data: Map<String, Any?>?) {
                    }

                    override fun reUiView(deltaUp: Boolean) {
                        callback.onRequestRefresh(sourceUrl)
                    }
                })
        }

        /** viewName显示名:'xxx'字面量直接用,js异步求值后更新 */
        fun applyViewName(
            textView: TextView,
            kind: ExploreKind,
            update: (String) -> Unit,
            setDefault: () -> Unit
        ) {
            val viewName = kind.viewName
            if (viewName == null) {
                setDefault()
            } else if (viewName.length in 3..19 && viewName.first() == '\'' && viewName.last() == '\'') {
                update(viewName.substring(1, viewName.length - 1))
            } else {
                setDefault()
                Coroutine.async(scope, IO) {
                    evalUiJs(viewName, source, infoMap)
                }.onSuccess { n ->
                    update(n ?: "null")
                }.onError { _ ->
                    update("err")
                }
            }
        }

        fun evalKindAction(kind: ExploreKind, title: String) {
            val action = kind.action?.takeIf { it.isNotBlank() } ?: return
            scope.launch(IO) {
                evalButtonClick(action, source, infoMap, title, sourceJsExtensions)
            }
        }

        /** chip触控:按下高亮,抬起触发,防抖200ms */
        @SuppressLint("ClickableViewAccessibility")
        fun setChipTouchListener(view: View, onFire: () -> Unit) {
            view.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isSelected = true
                    }

                    MotionEvent.ACTION_UP -> {
                        v.isSelected = false
                        val upTime = System.currentTimeMillis()
                        if (upTime - lastClickTime < 200) {
                            return@setOnTouchListener true
                        }
                        lastClickTime = upTime
                        onFire()
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        v.isSelected = false
                    }
                }
                return@setOnTouchListener true
            }
        }

        kotlin.runCatching {
            recycle(flexbox)
            flexbox.visible()
        }.onFailure {
            AppLog.put("ExplorePanel recycle error:${it.localizedMessage}", it)
        }
        kinds.forEach { kind ->
            val type = kind.type
            val title = kind.title
            //单项渲染失败不中断其余项,异常写入日志便于排查
            runCatching {
                when (type) {
                    Type.url -> {
                        val tv = getFlexboxChild(flexbox)
                        flexbox.addView(tv)
                        applyChipStyle(tv, kind)
                        applyViewName(tv, kind, { n -> tv.text = n }, { tv.text = title })
                        if (mode == Mode.FILTER_SETTINGS) {
                            tv.isEnabled = false
                            tv.alpha = 0.4f
                            return@forEach
                        }
                        tv.setOnClickListener {//辅助触发无障碍功能正常
                            val url = kind.url ?: return@setOnClickListener
                            if (kind.title.startsWith("ERROR:")) {
                                it.activity?.showDialogFragment(TextDialog("ERROR", url))
                            } else {
                                callback.onKindClick(it, sourceUrl, kind)
                            }
                        }
                        setChipTouchListener(tv) {
                            val url = kind.url?.takeIf { it.isNotBlank() }
                                ?: return@setChipTouchListener
                            if (kind.title.startsWith("ERROR:")) {
                                tv.activity?.showDialogFragment(TextDialog("ERROR", url))
                            } else {
                                callback.onKindClick(tv, sourceUrl, kind)
                            }
                        }
                    }

                    Type.button -> {
                        val tv = getFlexboxChild(flexbox)
                        flexbox.addView(tv)
                        applyChipStyle(tv, kind)
                        applyViewName(tv, kind, { n -> tv.text = n }, { tv.text = title })
                        tv.setOnClickListener {
                            evalKindAction(kind, title)
                            if (mode == Mode.PANEL_ADD) {
                                callback.onKindClick(it, sourceUrl, kind)
                            }
                        }
                        setChipTouchListener(tv) {
                            evalKindAction(kind, title)
                            if (mode == Mode.PANEL_ADD) {
                                callback.onKindClick(tv, sourceUrl, kind)
                            }
                        }
                    }

                    Type.text -> {
                        val ti = getFlexboxChildText(flexbox)
                        flexbox.addView(ti)
                        kind.style().apply {
                            when (this.layout_justifySelf) {
                                "center" -> ti.gravity = Gravity.CENTER
                                "flex_end" -> ti.gravity = Gravity.END
                                else -> ti.gravity = Gravity.START
                            }
                            apply(ti)
                        }
                        applyViewName(ti, kind, { n -> ti.hint = n }, { ti.hint = title })
                        ti.setText(infoMap[title])
                        if (mode == Mode.PANEL_ADD) {
                            ti.isEnabled = false
                            return@forEach
                        }
                        var actionJob: Job? = null
                        val watcher = object : TextWatcher {
                            var content: String? = null
                            override fun beforeTextChanged(
                                s: CharSequence?, start: Int, count: Int, after: Int
                            ) {
                                content = s.toString()
                            }

                            override fun onTextChanged(
                                s: CharSequence?, start: Int, before: Int, count: Int
                            ) {
                            }

                            override fun afterTextChanged(s: Editable?) {
                                val reContent = s.toString()
                                infoMap[title] = reContent
                                infoMap.save()
                                if (kind.action != null && reContent != content) {
                                    actionJob?.cancel()
                                    actionJob = scope.launch(IO) {
                                        delay(600) //防抖
                                        evalButtonClick(
                                            kind.action, source, infoMap, title, sourceJsExtensions
                                        )
                                        content = reContent
                                    }
                                }
                            }
                        }
                        ti.setTag(R.id.text_watcher, watcher)
                        ti.addTextChangedListener(watcher)
                    }

                    Type.toggle -> {
                        val left = kind.style().layout_justifySelf != "right"
                        val tv = getFlexboxChild(flexbox)
                        flexbox.addView(tv)
                        applyChipStyle(tv, kind)
                        val chars = kind.chars?.filterNotNull() ?: listOf("chars", "is null")
                        val infoV = infoMap[title]
                        var char = if (infoV.isNullOrEmpty()) {
                            (kind.default ?: chars[0]).also {
                                infoMap[title] = it
                            }
                        } else {
                            infoV
                        }
                        var newName = title
                        applyViewName(tv, kind, { n ->
                            newName = n
                            tv.text = if (left) char + n else n + char
                        }, {
                            tv.text = if (left) char + title else title + char
                        })
                        if (mode == Mode.PANEL_ADD) {
                            tv.isEnabled = false
                            return@forEach
                        }
                        fun toggleAndFire() {
                            val currentIndex = chars.indexOf(char)
                            val nextIndex = (currentIndex + 1) % chars.size
                            char = chars.getOrNull(nextIndex) ?: ""
                            infoMap[title] = char
                            infoMap.save()
                            tv.text = if (left) char + newName else newName + char
                            evalKindAction(kind, title)
                        }
                        tv.setOnClickListener { toggleAndFire() }
                        setChipTouchListener(tv) { toggleAndFire() }
                    }

                    Type.select -> {
                        val sl = getFlexboxChildSelect(flexbox)
                        flexbox.addView(sl)
                        kind.style().apply {
                            when (this.layout_justifySelf) {
                                "flex_start" -> sl.gravity = Gravity.START
                                "flex_end" -> sl.gravity = Gravity.END
                                else -> sl.gravity = Gravity.CENTER
                            }
                            apply(sl)
                        }
                        val spName = sl.findViewById<AccentTextView>(R.id.sp_name)
                        applyViewName(spName, kind, { n -> spName.text = n }, {
                            spName.text = title
                        })
                        val chars = kind.chars?.filterNotNull() ?: listOf("chars", "is null")
                        val infoV = infoMap[title]
                        val char = if (infoV.isNullOrEmpty()) {
                            (kind.default ?: chars[0]).also {
                                infoMap[title] = it
                            }
                        } else {
                            infoV
                        }
                        val selector = sl.findViewById<AppCompatSpinner>(R.id.sp_type)
                        if (mode == Mode.PANEL_ADD) {
                            //面板模式:点开自绘选项列表,点选项仅回调,不改状态不执行action
                            selector.isVisible = false
                            sl.setOnClickListener {
                                showOptionPopup(sl, chars, sourceUrl, kind)
                            }
                            return@forEach
                        }
                        val adapter = ArrayAdapter(
                            context,
                            R.layout.item_text_common,
                            chars
                        )
                        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
                        selector.adapter = adapter
                        selector.setSelectionSafely(chars.indexOf(char))
                        selector.onItemSelectedListener =
                            object : AdapterView.OnItemSelectedListener {
                                var isInitializing = true
                                override fun onItemSelected(
                                    parent: AdapterView<*>?, view: View?, position: Int, id: Long
                                ) {
                                    if (isInitializing) { //忽略初始化选择
                                        isInitializing = false
                                        return
                                    }
                                    infoMap[title] = chars[position]
                                    infoMap.save()
                                    if (kind.action != null) {
                                        scope.launch(IO) {
                                            evalButtonClick(
                                                kind.action, source, infoMap, title,
                                                sourceJsExtensions
                                            )
                                        }
                                    }
                                }

                                override fun onNothingSelected(parent: AdapterView<*>?) {
                                }
                            }
                    }
                }
            }.onFailure { e ->
                AppLog.put("ExplorePanel render $title error:${e.localizedMessage}", e)
            }
        }
    }

    /** 面板模式select选项弹出列表,点选项回调onSelectOptionClick */
    private fun showOptionPopup(
        anchor: View,
        chars: List<String>,
        sourceUrl: String,
        kind: ExploreKind
    ) {
        val popup = ListPopupWindow(context)
        popup.setAdapter(ArrayAdapter(context, R.layout.item_text_common, chars))
        popup.setAnchorView(anchor)
        val paint = TextView(context).paint
        val maxTextWidth = chars.maxOfOrNull {
            (paint.measureText(it) + 32.dpToPx()).toInt()
        } ?: 0
        popup.width = max(maxTextWidth, anchor.width)
        popup.setOnItemClickListener { _, view, position, _ ->
            chars.getOrNull(position)?.let {
                callback.onSelectOptionClick(view, sourceUrl, kind, it)
            }
            popup.dismiss()
        }
        popup.show()
    }

    private fun applyChipStyle(tv: TextView, kind: ExploreKind) {
        kind.style().apply {
            when (this.layout_justifySelf) {
                "flex_start" -> tv.gravity = Gravity.START
                "flex_end" -> tv.gravity = Gravity.END
                else -> tv.gravity = Gravity.CENTER
            }
            apply(tv)
        }
    }

    private suspend fun evalUiJs(jsStr: String, source: BookSource?, infoMap: InfoMap): String? {
        val source = source ?: return null
        return try {
            runScriptWithContext {
                source.evalJS(jsStr) {
                    put("infoMap", infoMap)
                }.toString()
            }
        } catch (e: Exception) {
            AppLog.put(source.getTag() + " exploreUi err:" + (e.localizedMessage ?: e.toString()), e)
            null
        }
    }

    private suspend fun evalButtonClick(
        jsStr: String,
        source: BaseSource?,
        infoMap: InfoMap,
        name: String,
        java: SourceLoginJsExtensions
    ) {
        val source = source ?: return
        try {
            runScriptWithContext {
                source.evalJS(jsStr) {
                    put("java", java)
                    put("infoMap", infoMap)
                }
            }
        } catch (e: Exception) {
            AppLog.put("ExploreUI Button $name JavaScript error", e)
        }
    }

    private val inflater: LayoutInflater
        get() = (context as? AppCompatActivity)?.layoutInflater
            ?: LayoutInflater.from(context)

    @Synchronized
    private fun getFlexboxChild(flexbox: FlexboxLayout): TextView {
        return if (recycler.isEmpty()) {
            ItemFilletTextBinding.inflate(inflater, flexbox, false).root
        } else {
            recycler.removeLastElement()
        }
    }

    @Synchronized
    private fun getFlexboxChildText(flexbox: FlexboxLayout): AutoCompleteTextView {
        return if (textRecycler.isEmpty()) {
            ItemFilletCompleteTextBinding.inflate(inflater, flexbox, false).root
        } else {
            textRecycler.removeLastElement()
        }
    }

    @Synchronized
    private fun getFlexboxChildSelect(flexbox: FlexboxLayout): LinearLayout {
        return if (selectRecycler.isEmpty()) {
            ItemFilletSelectorSingleBinding.inflate(inflater, flexbox, false).root
        } else {
            selectRecycler.removeLastElement()
        }
    }

    @Synchronized
    fun recycle(flexbox: FlexboxLayout) {
        val children = flexbox.children.toList()
        if (children.isEmpty()) return
        flexbox.removeAllViews()
        scope.launch {
            for (child in children) {
                when (child) {
                    is AutoCompleteTextView -> {
                        val watcher = child.getTag(R.id.text_watcher) as? TextWatcher
                        if (watcher != null) {
                            child.removeTextChangedListener(watcher)
                        }
                        textRecycler.add(child)
                    }

                    is TextView -> {
                        child.setOnTouchListener(null)
                        child.setOnClickListener(null)
                        recycler.add(child)
                    }

                    is LinearLayout -> {
                        child.findViewById<AppCompatSpinner>(R.id.sp_type)
                            ?.onItemSelectedListener = null
                        selectRecycler.add(child)
                    }
                }
            }
        }
    }

}
