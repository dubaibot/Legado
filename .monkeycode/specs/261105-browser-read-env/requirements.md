# 阅读 D WebView 阅读页重构 - 需求规格说明书

> 分支：261105-feat-browser-read-env
> 日期：2026-11-05
> 状态：草稿

## 背景

阅读 D 是 Via 式浏览器应用，当前阅读页使用 Canvas 自绘（`ReadView`）排版正文，仅"订阅源"与"内置浏览器"走 WebView。目标将阅读页切换为 WebView 渲染，形成"浏览器 + 阅读器"的一体化体验，同时保留书架、搜索、发现、书源、正文、净化、进度、书签、本地书、听书等全部阅读功能。

## 目标

1. 阅读页正文用 WebView 渲染，原生负责书源、正文获取、净化、进度与听书文本。
2. 兼容现有 `ReadMenu`、九宫格点击、音量键翻页、进度保存等全部交互，不破坏 Canvas 作为回退。
3. 通过偏好开关 `webViewRead` 切换 WebView 与 Canvas 阅读页，默认开启。
4. 保留 Canvas `ReadView` 布局与 `TextChapter` 文本来源，TTS 与搜索继续可用。
5. 前端可复用现有反向代理规则对接后端（本需求只覆盖阅读页）。

## 用户故事

- US1 作为用户，我希望打开书籍后正文以 WebView 渲染呈现，以便获得浏览器一致的排版与字体效果。
- US2 作为用户，我希望点击屏幕中部弹出菜单、左右翻章、上下翻页，行为与原 Canvas 阅读页一致。
- US3 作为用户，我希望阅读进度按 WebView 滚动位置保存，返回书籍时恢复到上次阅读位置。
- US4 作为用户，我希望听书功能继续可用，不因切换到 WebView 阅读页而失效。
- US5 作为用户，我可以通过设置开关回到 Canvas 阅读页作为回退。

## 功能需求（EARS 模式）

### FR-1 WebView 阅读页渲染
- 当 `webViewRead` 偏好为 true 时，阅读页使用 WebView 渲染当前章节正文。
- WebView 的 HTML 与 CSS 由原生构建，样式取自 `ReadBookConfig` 的当前配置（字号、行距、段间距、内边距、颜色、对齐、字重、字间距）。
- 当 `webViewRead` 为 false 时，继续使用 Canvas `ReadView` 渲染。

### FR-2 章节与内容来源
- WebView 章节正文取自 `ReadBook.curTextChapter.getContent()` 拼接文本与章节标题。
- 进度与听书文本继续使用 `TextChapter` 与 `ChapterProvider` 布局数据。

### FR-3 九宫格点击
- WebView 阅读页支持九宫格点击区域（与 `ReadView.setRect9x` 一致的三分法）。
- 点击中心区域弹出 `ReadMenu`，其他区域按默认动作映射。

### FR-4 翻页与翻章
- WebView 阅读页支持纵向滚动分页：`pageNext`/`pagePrev` 按视口高度滚动。
- 滚动到章节末尾时触发 `ReadBook.moveToNextChapter(true)`。
- 音量键在 WebView 模式下按 `NEXT`/`PREV` 调用 `pageNext`/`pagePrev`。

### FR-5 进度保存与恢复
- WebView 阅读页在 `onScrollChanged` 时按滚动比例映射到章节字符位置，写入 `ReadBook.durChapterPos` 并调用 `saveRead()`。
- 章节切换后 WebView 加载新内容，按 `durChapterPos` 滚动定位到对应段落。

### FR-6 样式更新
- 样式变化（字号、颜色、内边距、对齐等）通过注入 CSS 变量或重建 HTML 应用，无需重建 Activity。

### FR-7 偏好开关
- 新增偏好键 `webViewRead`（`PreferKey.webViewRead`），默认值为 true。
- `AppConfig.webViewRead` 缓存偏好值并监听变化。
- 阅读页 `onActivityCreated` 与 `UP_CONFIG` 事件根据 `webViewRead` 切换 `readView` 与 `webReadView` 的可见性。

### FR-8 文字与链接处理
- 正文中的 `<usehtml>` 标签块作为原始 HTML 注入，其余文本进行 HTML 转义防 XSS。
- 正文中的 `<img>` 标签保留，网络图片可正常加载。

### FR-9 回退到 Canvas
- 偏好切换为 false 或切换书籍时，WebView 阅读页显示 Canvas `ReadView`，WebView 实例回收到池。

## 非功能需求

- 性能：章节切换时重建 HTML 只发生一次，避免布局阶段重复注入导致闪烁。
- 安全：所有非 `usehtml` 文本必须转义，防止 XSS。
- 兼容：不破坏 `ReadView`、`TextChapter`、`ChapterProvider`、`ReadMenu` 的现有调用。

## 验收标准

- 通过偏好 `webViewRead` 切换 WebView 与 Canvas 阅读页。
- WebView 阅读页能显示章节标题与正文，样式与配置一致。
- 九宫格点击、音量键翻页、滚动进度保存均正常工作。
- 听书、搜索、书签、目录等原生功能继续可用。
- 单元测试覆盖 HTML 构建器核心逻辑。
