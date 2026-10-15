# 多源合并每日任务排期

> 依据：《多源功能合并修改清单》（2026-10-15）。原则：积分有限，每日只动一个文件簇，新增优先、融合单独日、核心侵入单独分支；每日必跑 `./gradlew :app:assembleDebug`，DB 改动日必跑实库迁移。
> 进度：D1–D6 完成（D4/D5 纸面方案与视频管线见 261015-feat-d4/d5 分支，D6 见 d6-report.md）；下一步 D7。

## 排期前环境核对结论（2026-10-15 实测）

| 事项 | 现状 | 对排期的影响 |
|------|------|--------------|
| 四个 fork 源树 | 工作区及周边目录均未找到克隆 | D1 必须先就位，否则全部搬运任务无法开工 |
| media3 | exoplayer、datasource-okhttp 已启用；hls、ui、session 被注释；effect、cronet 未声明 | D7 从「加全套」缩减为「启用 hls/ui + 新增 effect/cronet」 |
| Compose | `org.jetbrains.kotlin.plugin.compose` 未声明，`buildFeatures` 无 compose | D2 需补插件 + BOM + material3 |
| DB | v90 | 所有迁移从 v91 起步，逐功能推进，禁止跳版 |
| 工作区 git | main 分支，工作树干净 | 可直接打基线 tag |

## 通用纪律

1. 每阶段一个 feature 分支（`feat/p0-infra` … `feat/p4-ai`），日末 commit 并注明清单编号。
2. 融合类任务（会覆盖同名文件）开工前先产出对比结论，动文件前有方案。
3. 每阶段结束跑一次回归基线：assembleDebug + assembleRelease 混淆 + Room schema 对比 + 阅读主流程手测。
4. 纸面日（只读分析）尽量排在积分紧张时段，产出的方案文档让后续搬运日一次到位。

---

## 阶段 0 基础设施（D1–D3）

| 日 | 任务 | 内容 | 产出/验证 |
|----|------|------|-----------|
| D1 | 备料 | 四个 fork 克隆到 `/workspace/_refs/{m,arc,max,ng}`（或用户上传），记录各自锁定 commit hash；工作区打 `baseline-v90` tag；跑通 assembleDebug 记录基线日志 | 源树就位 + 基线构建日志 |
| D2 | 清单 1.1 Compose 配置 | toml 加 compose-bom/material3/material-icons-extended/compose 插件；app/build.gradle 开 `buildFeatures.compose=true`；写一个临时 Compose DemoActivity 验证编译后删除 | assembleDebug 通过 |
| D3 | 清单 1.2/1.4 迁移框架与资源约定 | DB 迁移 SOP 文档（逐版本推进、schema 导出核对、实库升级测试流程）；预写 v91 空迁移验证全流程；确定 strings 等资源按源分区命名约定（如 `strings_m.xml`） | 迁移 SOP + 空迁移验证通过 |

## 阶段 1 M 视频栈（D4–D11）

| 日 | 任务 | 内容 | 产出/验证 |
|----|------|------|-----------|
| D4 | 纸面日：融合方案 | 对比工作区与 m 的 `help/gsyVideo` 10 个同名文件、`ui/video`、`service/VideoPlayService`；产出《弹幕保留融合方案》：以 m 播放器壳为基座、弹幕渲染层挂回 `VideoFragment` 的具体落点 | 方案文档 |
| D5 | 清单 2.1.3 视频管线 | 纯新增 `help/video/` 全套（Pipeline、UrlExtractor、MacCmsNormalizer、FavoriteResolver、PlaylistHolder、SniffCandidate、engine/），零覆盖旧文件 | assembleDebug |
| D6 | 清单 2.2.1/2.2.3 并行工具与规则缓存 | 新增 `utils/FlowExtensions.kt`（mapParallelSafe）、`help/source/` 五个缓存类（BookSourceCacheStore、SourceQueryCache、BookSourceStorageScope、SourcePreconnectHelper、BookSourceFileAccessPolicy） | assembleDebug |
| D7 | 清单 2.1.5/2.1.6 依赖与 RSS 字段 | 启用 media3 hls/ui、新增 effect（画质增强）、datasource-cronet；`RssArticle` 视频扩展字段 + DB 下一版迁移 + schema 导出 | 实库 v90→新版本升级测试 |
| D8 | 清单 2.1.1 gsyVideo 内核融合 | 按 D4 方案融合 10 个同名文件，弹幕保留；独立分支 `feat/video-core` | assembleDebug + 弹幕渲染手测 |
| D9 | 清单 2.1.4/2.1.2 前半 服务与播放壳 | `VideoPlayService` 融合；`VideoPlayerActivity/ViewModel`、`VideoFragment`、`VideoPagerAdapter` 搬入改造 | 播放主路径 + 后台播放手测 |
| D10 | 清单 2.1.2 后半 播放壳收尾 | 设置面板（SettingsDialog/Panel）、预加载、详情 Sheet、投屏 cast/、画质增强、RSS 路由与集数适配器；弹幕层挂回 VideoFragment | 弹幕 + 音画同步专项手测 |
| D11 | 清单 2.2.2/2.2.4/2.2.5 订阅源优化 + 阶段回归 | RssSearchModel/Rss/RssParserByRule/RssParserDefault 并行化；http 缓存层 + DNS 兜底；图片解密缓存；阶段 1 回归（阅读主流程 + 弹幕视频清单） | 搜索手测 + 回归清单通过 |

## 阶段 2 arc 自动化（D12–D15）

| 日 | 任务 | 内容 | 产出/验证 |
|----|------|------|-----------|
| D12 | 清单 3.1 模型层 | 纯新增 `model/` 下 11 个 `AutoTask*` 文件 | assembleDebug |
| D13 | 清单 3.2/3.3/3.5 数据层与服务 | `AutoTaskRuleDao`、AppDatabase 注册实体 + 迁移；`AutoTaskService` + `AutoTaskBootReceiver`；Manifest 登记三项 Activity 与 Service/Receiver | 实库升级 + 服务启停手测 |
| D14 | 清单 3.4 前半 Compose UI | `AutoTaskActivity`、`AutoTaskEditActivity` 及 Screen/ViewModel；首次验证 Compose 屏接 `ThemeStore` 主题 | 空列表页跑通 |
| D15 | 清单 3.4 后半/3.6 UI 收尾与入口 | `AutoTaskDebugActivity`、Book/Import dialogs；书籍详情/阅读菜单与个人页入口；阶段 2 回归 | 定时任务创建→执行→日志全链路 |

## 阶段 3 max（D16–D29）

| 日 | 任务 | 内容 | 产出/验证 |
|----|------|------|-----------|
| D16 | 清单 4.1.1 调试模型层 | 纯新增 `model/debug/` 15 文件 | assembleDebug |
| D17 | 纸面日：埋点落点分析 | max 根为 2026-04-20 快照，`AnalyzeRule`/`webBook` 落点偏移；按工作区现行代码逐函数定位，产出埋点落点表 | 方案文档 |
| D18 | 清单 4.1.2 前半 织入（高侵入） | 独立分支 `feat/debug-tracker`；`AnalyzeRule`、`AnalyzeByJSonPath` 等埋 RuleExecutionTracker | 书源调试产出流程日志 |
| D19 | 清单 4.1.2 后半/4.1.5 织入收尾 | `webBook/*`、书源检测服务埋点；检测 UI 接 `RssExecutionRecord`；与基线对比确认无性能回退 | 书源检测全流程通过 |
| D20 | 清单 4.1.3 调试工具台 | 新增 `ui/debug/` 7 组 Activity+Screen（Http/Curl/Regex/Timestamp/Encode/Ping/DebugTools） | 各工具页可达可用 |
| D21 | 清单 4.1.4 悬浮球 | 新增 `ui/debuglog/` 14 文件：悬浮球 Manager、日志屏、分类显隐 | 悬浮球生命周期手测 |
| D22 | 清单 4.1.6 TTS/字典调试（轻量日） | `ui/config` TTS 源调试、`ui/dict` 字重调试界面 | 第一批回归：调试全家桶主路径 |
| D23 | 清单 4.2.1/4.2.5 主题管理 | `lib/theme/` + ui/config 主题分区、日/夜间/顶栏/底栏/封面图集一键切换、主题批量与置顶、备份文件验证 | 日夜间切换 + ThemeStore 一致性 |
| D24 | 清单 4.2.2/4.2.3 底顶栏与封面 | bookshelf 布局模式/自定义图标/壁纸/圆角/标签配色；HTML 封面模板引擎 + 封面图集 | 书架视觉回归 |
| D25 | 清单 4.2.4 段评气泡（轻量日） | SVG 模板 + 日夜间配色接线 | 阅读页气泡渲染 |
| D26 | 清单 4.3.1/4.3.2 规则拓展 | `@webjs` 规则类型、JsExtensions 增强函数、help/rhino | 样例书源跑通 @webjs |
| D27 | 清单 4.3.3/4.3.5 导入与编辑器 | association 智能导入/自动更新；`ui/code` 编辑器增强 | 导入手测 |
| D28 | 清单 4.3.4 阅读记录数据 | urlRecord 实体/DAO/迁移 + 四视图/热力图 UI、URL 记录、记录搜索、缓存管理增强 | 实库升级 + 记录页手测 |
| D29 | 清单 4.3.6 搜索收尾 + 阶段回归 | 帮助文档全局搜索、书签搜索；阶段 3 全量回归（含 assembleRelease 混淆 keep 规则补齐） | 回归清单通过 |

## 阶段 4 ng AI（D30–D35）

| 日 | 任务 | 内容 | 产出/验证 |
|----|------|------|-----------|
| D30 | 清单 5.1.1 前半 AI 引擎·Provider | 新增 `help/ai/`：AiManager、AiChatClient、AiProvider + OpenAi/Claude/Google 三 Provider、ModelRegistry、DefaultProviders、ProviderStore；同时搬 ng `help/tts` 目录（多角色 TTS 前置） | assembleDebug |
| D31 | 清单 5.1.1 后半 AI 引擎·存储与净化 | PromptStore、ChatContextManager、ChatHistoryStore、SkillRegistry/PackageRegistry、BalanceProvider、PurifyHelper 等剩余文件 | assembleDebug |
| D32 | 清单 5.1.3 AI 数据层 | AI 相关实体/DAO/Preferences + DB 迁移 + schema 导出 | 实库升级测试 |
| D33 | 清单 5.2.3/5.2.1 净化接线与配置页 | AiPurifyHelper 挂进 webBook 内容后处理；服务商配置页（Sigma View 重写或独立 Compose Activity） | 净化生效 + 配置页可保存 |
| D34 | 清单 5.2.2/5.2.4 阅读入口与对话面板 | ReadBookMenu/划词菜单挂 AI 入口；围绕书籍的聊天浮层面板（Sigma 原生控件重写，摆脱 ng 的 ui/design 依赖） | 阅读页内对话主路径 |
| D35 | 阶段 4 回归与收尾 | 第一版范围回归：阅读主流程 + AI 对话 + 全量回归；assembleRelease keep 规则补 AI 包 | 回归清单通过 |

## 后置批次（第一版裁掉，另行排期）

| 日 | 任务 | 内容 |
|----|------|------|
| D36+ | 清单 5.1.2 MCP 服务 | McpHttpServer、ToolCatalog、AgentMemory/RssSource 工具 |
| — | 清单 5.1 后置 | AI 技能（Skills）、多角色 TTS、有声书离线缓存 |

---

## 风险控制点（对应清单第 7 节）

- D4 方案日直接决定 D8–D10 成败，弹幕融合不达标则回滚 `feat/video-core`。
- D17 落点表是全清单侵入最深的 4.1.2 的安全网，逐函数 review 后再动 D18/D19。
- 所有 DB 迁移只在功能就位当日推进一版，出现分叉字段一律做工作区/源/上游三方对比取并集。
- Compose 屏一律走 ThemeStore 适配，避免双主题体系分叉。
