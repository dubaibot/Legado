# D17 埋点落点分析表

> 日期：2026-10-24。来源：max fork（Suml-1/Legado_Max）vs 工作区逐文件 diff。
> 用途：D18 织入工作的安全网，逐函数定位 RuleExecutionTracker 嵌入点。

## 1. 已织入文件（D1-D15 期间完成）

以下文件在 D1-D15 合并中已与 max 对齐，tracker 织入点确认到位：

| 文件 | 织入点 | 状态 |
|------|--------|------|
| `model/analyzeRule/AnalyzeRule.kt` | 第 317 行 `RuleExecutionTracker(source, ruleStr)` 创建；第 357/373/377/379 行 `startStep`/`endStep`；第 392-395/410-411 行 `buildTree` 输出 | ✅ 与 max 一致 |
| `model/analyzeRule/AnalyzeByJSonPath.kt` | 无独立 tracker（由 AnalyzeRule 统一管理） | ✅ 与 max 一致 |
| `model/analyzeRule/AnalyzeByJSoup.kt` | 同上 | ✅ 与 max 一致 |
| `model/analyzeRule/AnalyzeByRegex.kt` | 同上 | ✅ 与 max 一致 |
| `model/analyzeRule/AnalyzeByXPath.kt` | 同上 | ✅ 与 max 一致 |
| `model/analyzeRule/AnalyzeUrl.kt` | 有 tracker 引用 | ✅ 已织入 |
| `model/webBook/BookContent.kt` | 有 tracker 引用 | ✅ 已织入 |
| `model/webBook/BookInfo.kt` | 有 tracker 引用 | ✅ 已织入 |
| `model/webBook/BookList.kt` | 有 tracker 引用 | ✅ 已织入 |
| `model/webBook/BookChapterList.kt` | 有 tracker 引用 | ✅ 已织入 |
| `model/webBook/WebBook.kt` | 有 tracker 引用；import 路径差异：工作区用 `ui.main.explore.ExploreAdapter.Companion.exploreInfoMapList`，max 用 `help.source.exploreInfoMapList` | ⚠️ 功能已织入，import 路径待统一 |
| `data/entities/BaseSource.kt` | 有 tracker 引用 | ✅ 已织入 |
| `help/JsCacheManager.kt` | 有 tracker 引用 | ✅ 已织入 |
| `help/RegexJsExtensions.kt` | 有 tracker 引用 | ✅ 已织入 |
| `data/repository/debug/FlowLogRecorder.kt` | 已创建 | ✅ 已织入 |
| `constant/AppLog.kt` | 有 tracker 引用（工作区独有，max 无） | ✅ 已织入 |
| `model/VideoPlay.kt` | 有 tracker 引用（工作区独有） | ✅ 已织入 |
| `ui/debuglog/DebugFloatingBallManager.kt` | 悬浮球管理 | ✅ 已织入 |
| `ui/debuglog/viewmodel/DebugLogViewModel.kt` | 日志屏 ViewModel | ✅ 已织入 |

## 2. 待织入文件（D18 范围）

### 2.1 `model/rss/RssParserByRule.kt`

**差异概述**：max 版引入 `RssExecutionRecorder`、`RuleExecutionTracker`、`RssExecutionStep`、`DebugCategory`、`RuleType`；工作区版无这些引用。

**落点清单**：
- 第 5 行：删除 `import io.legado.app.constant.AppLog`（max 不保留）
- 第 7 行：`import io.legado.app.help.source.autoNextPageEnabled` → max 用 `RssExecutionRecorder`
- 第 10 行：删除 `import io.legado.app.help.config.AppConfig`
- 第 15-17 行：追加 `DebugCategory`、`RssExecutionStep`、`RuleExecutionTracker`、`RuleType` import
- 第 17-24 行：删除 `isAbsUrl`、`Dispatchers`、`async`、`awaitAll`、`coroutineScope` import（max 不保留）
- 函数内：在 RSS 解析主流程创建 `RuleExecutionTracker`，逐步骤 `startStep`/`endStep`，异常时 `failStep`

### 2.2 `model/rss/Rss.kt`

**差异概述**：max 版引入 `RssExecutionRecorder`、`FlowLogRecorder`、`DebugCategory`、`RssExecutionStep`、`AnalyzeRule.Companion.setToastRuleType`；工作区版无这些引用。同时 max 版重构了网络请求入口。

**落点清单**：
- 第 3 行：删除 `import io.legado.app.constant.AppLog`
- 第 5 行：删除 `import io.legado.app.data.entities.RssEpisode`（max 不保留）
- 第 6 行：追加 `RssExecutionRecorder`、`FlowLogRecorder` import
- 第 9-14 行：删除 `warmUpConnection`、`SourceLastHostHelper`、`MacCmsNormalizer`、`SourceNetworkClient`、`SourcePreconnectHelper`、`VideoUrlExtractor` import（max 不保留这些路径）
- 第 17 行：追加 `setToastRuleType` import
- 第 19 行：追加 `DebugCategory`、`RssExecutionStep` import
- 第 20 行：追加 `isAbsUrl` import
- 第 23-24 行：删除 `async`、`awaitAll` import
- 第 26-27 行：删除 `JSONArray`、`JSONObject` import
- 函数内：在 RSS 解析主流程创建 tracker，逐步骤埋点

### 2.3 `model/webBook/SearchModel.kt`

**差异概述**：max 版新增 `SourceSearchRecord` 数据类、`sourceRecords` 并发映射、逐书源状态跟踪（PENDING→RUNNING→SUCCESS/EMPTY/FAILED）、进度回调 `onSearchProgress`/`onSourceStatesChanged`、暂停/恢复/取消方法。

**落点清单**：
- 第 30 行：追加 `import java.util.concurrent.ConcurrentHashMap`
- 第 33 行后：新增 `SourceSearchRecord` 数据类定义（含 `Status` 枚举）
- 类体内：新增 `sourceRecords` 属性
- `search()` 方法内：在 `mapParallelSafe` 块中，每书源创建 `SourceSearchRecord`，状态流转时回调 `onSourceStatesChanged`
- `search()` 方法内：搜索完成后调用 `onSearchProgress`
- `cancel()` / `close()` 方法：清空 `sourceRecords`
- `SearchCallback` 接口：追加 `onSearchProgress`、`onSourceStatesChanged` 方法

## 3. 织入策略

1. **优先对齐 max 版本**：RssParserByRule.kt 和 Rss.kt 以 max 版为基座，逐函数核对后替换
2. **SearchModel.kt 保留工作区功能**：工作区版可能有 max 版不具备的改进，采用"追加"而非"替换"策略
3. **WebBook.kt import 路径**：保留工作区路径（`ui.main.explore.ExploreAdapter.Companion.exploreInfoMapList`），因该路径在工作区有效
4. **独立分支**：`feat/debug-tracker`，便于回滚

## 4. 风险点

- Rss.kt 差异较大（max 重构了网络请求入口），直接替换可能丢失工作区的网络优化改进
- SearchModel.kt 的回调接口变更可能影响调用方，需检查所有 `SearchCallback` 实现
- 建议 D18 分两步：先 RssParserByRule + Rss，再 SearchModel
