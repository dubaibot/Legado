# 阅读 D WebView 阅读页重构 - 技术设计文档

> 分支：261105-feat-browser-read-env
> 日期：2026-11-05

## 1. 架构总览

WebView 阅读页作为阅读页的可切换渲染层，与现有 Canvas `ReadView` 并存：

- 原生层负责书源、正文获取、净化、进度、听书文本、书签、搜索、目录。
- WebView 层负责正文 HTML/CSS 渲染、九宫格点击、滚动进度。
- `ReadMenu`、`SearchMenu`、AI 面板、听书面板继续作为覆盖层复用。
- `TextChapter` 与 `ChapterProvider` 继续提供听书与搜索所需的文本与分页数据。

## 2. 组件

### 2.1 WebReadHtmlBuilder（纯 Kotlin）

`io.legado.app.ui.book.read.web.WebReadHtmlBuilder`

- `escape(text: String): String` — HTML 转义 `& < > " '`。
- `unwrapUseHtml(text: String): String` — 解包 `<usehtml>...</usehtml>` 为原始 HTML。
- `buildBody(title: String, paragraphs: List<String>, style: WebReadStyle): String` — 生成 `<body>` 内容。
- `buildDocument(title: String, paragraphs: List<String>, style: WebReadStyle): String` — 生成完整 HTML 文档（含 `<head>`、CSS、`<body>`）。

样式数据类 `WebReadStyle`（纯数据）：

- `textColor: String`（#AARRGGBB 或 #RRGGBB）
- `bgColor: String`
- `textSize: Int`（px）
- `lineHeight: Float`（无单位）
- `letterSpacing: Float`（em）
- `paragraphSpacing: Int`（px）
- `paddingTop/Right/Bottom/Left: Int`（px）
- `textAlign: String`（left/justify）
- `textIndent: String`（段落缩进 CSS 值）
- `fontFamily: String`（CSS font-family 值）
- `textWeight: Int`（100-900）
- `titleWeight: Int`（100-900）
- `titleSize: Int`（px）
- `titleMode: Int`（0 左 / 1 中 / 2 隐藏 / 3 右）
- `hideTitle: Boolean`

### 2.2 WebReadView（FrameLayout）

`io.legado.app.ui.book.read.web.WebReadView`

- 继承 `FrameLayout`。
- 内部使用 `WebViewPool.acquire` / `WebViewPool.release`。
- `attach()`：从池获取 WebView 并 `addView`。
- `detach()`：从父布局移除并释放回池。
- `upContent(relativePosition, resetPageOffset)`：相对位置为 0 且内容变化时重新加载章节 HTML；否则仅按 `resetPageOffset` 滚动。
- `upStyle()`：重建 CSS 并注入当前 WebView。
- `pageNext()` / `pagePrev()`：按视口高度滚动；到边界时调用 `ReadBook.moveToNextChapter(true)` / `moveToPrevChapter(true, toLast = false)`。
- `getReadPos(): Int`：根据滚动比例估算章节字符位置。
- `onDestroy()`：释放 WebView 回池。

JS 桥接 `WebReadJsInterface`：

- `onScrollRatio(ratio: Double)` — WebView 滚动比例回调，用于进度保存。
- `onClickZone(zone: Int)` — 九宫格点击区域回调，由原生计算 zone。
- `onPageLoaded()` — 章节 HTML 加载完成回调，触发进度恢复。

### 2.3 WebReadHtmlBuilderTest（单元测试）

`app/src/test/java/io/legado/app/ui/book/read/web/WebReadHtmlBuilderTest.kt`

- 测试 HTML 转义。
- 测试 `usehtml` 解包。
- 测试标题隐藏。
- 测试样式映射。

## 3. 接入点

### 3.1 布局

`activity_book_read.xml` 新增：

```xml
<io.legado.app.ui.book.read.web.WebReadView
    android:id="@+id/web_read_view"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:visibility="gone" />
```

默认隐藏，由 `applyReadEngine()` 切换显隐。

### 3.2 ReadBookActivity

- `applyReadEngine()`：根据 `AppConfig.webViewRead` 切换 `readView` 与 `webReadView` 可见性。
- `onActivityCreated`：调用 `applyReadEngine()`。
- `observeLiveBus` 的 `UP_CONFIG` 监听：加入 `webViewRead` 分支，调用 `applyReadEngine()`。
- `keyPage(direction)`：`webViewRead` 为 true 时调用 `webReadView.pageNext()` / `pagePrev()`，否则走原 `readView.pageDelegate?.keyTurnPage(direction)`。
- `upContent`：`webViewRead` 为 true 时路由到 `webReadView.upContent(...)`。
- `onDestroy`：调用 `webReadView.onDestroy()`。

### 3.3 PreferKey

- `const val webViewRead = "webViewRead"`

### 3.4 AppConfig

- `var webViewRead = appCtx.getPrefBoolean(PreferKey.webViewRead, true)`
- `onSharedPreferenceChanged` 增加 `PreferKey.webViewRead -> webViewRead = appCtx.getPrefBoolean(PreferKey.webViewRead, true)`。

### 3.5 偏好界面

- `pref_config_read.xml` 新增 `SwitchPreference`，key=`webViewRead`，title=`@string/web_view_read`，defaultValue=true。
- `strings.xml` 新增 `<string name="web_view_read">WebView 阅读页</string>`。
- `values-zh/strings.xml` 新增同 key 中文字符串。

### 3.6 MoreConfigDialog

- `onSharedPreferenceChanged` 增加 `PreferKey.webViewRead -> postEvent(EventBus.UP_CONFIG, arrayListOf(5))`。

## 4. 数据流

```
ReadBook.loadContent(index)
  -> BookHelp.getContent(book, chapter)
  -> ContentProcessor.getContent(book, chapter, content, includeTitle = false)
  -> ChapterProvider.getTextChapterAsync(book, chapter, displayTitle, contents, simulatedChapterSize)
  -> TextChapter (Canvas 布局 + 听书文本 + 搜索)
  -> ReadBook.curTextChapter = textChapter
  -> callBack.upContent(offset, resetPageOffset)
    -> ReadBookActivity.upContent
      -> 若 webViewRead:
           webReadView.upContent(...)
             -> 从 curTextChapter 获取标题与正文
             -> WebReadHtmlBuilder.buildDocument(...)
             -> WebView.loadDataWithBaseURL(...)
           否则:
           readView.upContent(...)
```

## 5. 进度映射

- 章节内进度：WebView `scrollY / (contentHeight - viewportHeight)` 映射到 `TextChapter` 字符位置。
- 章节切换时调用 `ReadBook.moveToNextChapter(true)`，WebView 重新加载新章节。
- 恢复阅读位置：按 `durChapterPos` 定位到对应段落并 `scrollIntoView`。

## 6. 风险与回退

- 风险：WebView 阅读页可能影响现有 Canvas 阅读功能（选择文本、搜索高亮、自动翻页）。
- 回退：通过 `webViewRead` 偏好切换回 Canvas `ReadView`。
- 风险：WebView 内存占用。
- 缓解：使用 `WebViewPool` 复用 WebView 实例，避免重复创建。

## 7. 测试策略

- 单元测试：`WebReadHtmlBuilderTest` 覆盖 HTML 构建核心逻辑。
- 手动验证：WebView 阅读页打开书籍、翻页、翻章、进度保存、听书。
- 回退验证：切换 `webViewRead` 偏好回到 Canvas 阅读页。
