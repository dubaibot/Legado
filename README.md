# 蓝阅 Android 阅读器（架构骨架）

自托管、本地优先的 Android 阅读器骨架。纯本地运行，无埋点、无遥测、无第三方统计 SDK。

## 目录

```
app/src/main/java/com/lanyue/reader/
├── core/
│   ├── engine/        规则引擎核心（JsRuntime / DomParser / JsExtensions / 通道粘滞 /
│   │                  RateLimiter / Sniffer / NetworkDispatcher / pool）
│   ├── net/           网络通道抽象与 OkHttp 实现
│   ├── render/        排版引擎（TypographySpec / Paginator / ReadingProgress /
│   │                  ReadingSettings / PageTurnController / Theme）
│   ├── security/      权限与桥接安全
│   └── observability/ 本地指标（仅落盘，不外发）
├── source/
│   ├── model/         源字段/类型契约（SearchBookItem / TocItem / SourceResult）
│   ├── rule/          规则层（RuleSyntax 语法 / RuleChain 编排 / RuleEngine 执行）
│   ├── aggregate/     多源聚合搜索（去重、书名归并、空作者回填）
│   ├── import/        书源导入解析（Legado JSON / 订阅合集）
│   ├── repo/          源仓库与同步
│   ├── debug/         书源离线调试管线（搜索→详情→目录→正文串链路）
│   ├── book/ rss/ video/ danmaku/   各站点适配器
├── bookshelf/         书架操作层（增删/分组/排序）与换源进度映射
├── data/
│   ├── db/            Room 数据库 / 实体 / DAO（书源、订阅、影视、弹幕、书架）
│   └── backup/        备份 DTO / JSON 编解码 / 导入合并策略
├── danmaku/ player/   弹幕层 / 播放器（断点与播放进度持久化）
└── tools/             合规与架构门禁脚本 + 纯 JVM 单测脚本
```

## 规则引擎

一条规则按「自外向内」的优先级拆解：

| 语法 | 语义 |
|------|------|
| `A \|\| B` | 备选，A 有结果即短路，不再请求 B |
| `A && B` | 并列，两段结果按顺序合并 |
| `sel##正则##替换` | 取数后正则替换，支持 `$1` 反向引用；单 `##正则` 表示删除匹配 |
| `@put:{k:v}` / `@get:{k}` | 变量写入 / 读取 |
| `{{ 表达式 }}` | 结果模板拼接 |
| `class.x.1@text` | 尾部 `.N` 取第 N 个元素（负数取倒数） |

拆分逻辑在 `RuleSyntax`（纯字符串处理）与 `RuleChain`（纯函数编排），
`RuleEngine` 只负责取数与 I/O 接线，因此整条规则的拆解行为可在 JVM 单测中锁定。

## 排版引擎

- `Paginator`：按字宽单位估算分页（中文 1 格、ASCII 半格），带避头尾与英文单词保护；
  全局字符偏移为唯一真值，改字号重排后位置不跳章。
- `ReadingProgress`：不可变进度状态机，含空书 / 越界 / 单章边界保护。
- `TypographySpec` / `ThemePalette`：排版度量与主题为纯数据，校验在构造期完成。

## 源调度层

- `SourceRouter`：统一调度四类源（书源 / 订阅源 / 影视站点 / 弹幕源），
  `Semaphore` 闸门限制单源并发，失败按类型化健康度隔离。
- `SourceHealth`：故障计数按 **源类型 + 源地址** 二维键分开累计。
  这是刻意修掉的一个真实缺陷——早期实现只用 sourceId 单键计数，
  同一个 id 的书源失败会连带隔离影视源，导致两类源互相拖垮。
- `SourceValidator`：书源 / 订阅源 / 影视站点的配置自检，
  区分 `ERROR`（阻止保存）与 `WARN`（仅提示），并给出可直接展示的中文原因；
  内置 `isHttpUrl` / `looksLikeJson` / `balanced` 三个纯函数做入参体检。

## 阅读运行时

- `ReadingSettings`：字号 / 行距 / 字距 / 主题 / 翻页方式 / 常亮 / 全屏 / 自动夜间，
  全部字段构造即校验并夹紧边界；`serialize` / `deserialize` 为稳定 key=value 串，
  单个字段损坏只回落该字段，不整体丢弃配置。
- `PageTurnController`：翻页状态机，区分页内翻动 / 跨章 / 撞书首书尾四种边界；
  跨章时按章节号强制打已读标记（不依赖 charOffset 是否恰好等于章节长度，
  因为分页会裁掉尾段空白）；滚动阅读模式用字符步进模拟，越界自动夹紧。
- `resolveTheme(hour)`：开启自动夜间后按小时切换，白天保留用户原本的浅色主题。

## 数据备份与恢复

- `BackupModels`：备份 DTO 独立于 Room 实体。
  实体带 androidx 注解，纯 JVM 无法加载；备份又是对外长期契约，
  表结构不应绑架备份格式，因此单独定义一套带默认值的朴素数据类。
- `BackupCodec`：手写字段读写而非 Gson 反射映射。
  反射反序列化会丢掉 Kotlin 默认值，老备份缺字段时会得到 null/0；
  手写实现可逐字段容错，并给出「哪一段、哪条记录坏了」的精确告警。
  失败分层：格式标记不符 / 版本越界整体拒绝；某段类型错、某条缺主键只局部跳过。
- `BackupMerger`：三种冲突策略（保留本机 / 以备份为准 / 按时间取新），
  纯函数实现，输入「本机主键+时间戳快照」与备份内容，输出待写记录。
  用户手工编辑过的书源受保护，导入不会覆盖调好的规则；备份内部重复条目按后者去重。

## 聚合搜索层（第八轮）

`source/aggregate/SearchAggregator`：多源搜索结果做「一本书一行」的合并去重：

- `normalizeText` 做全/半角归一 + 去装饰标点后比较，使「《斗破苍穹》」与「斗破苍穹！」归为同一本；
- 聚合键为「归一化书名 + 作者」，同 sourceUrl 与同键的重复条目只保留首条；
- 缺作者时只做宽松并入：同名聚类存在多个时互不合并，恰好同名聚类唯一时才归入，
  宁可多一行也不错误合并；
- 聚类排序：多源命中数降序（越多源收录越可能是「正主」），其次按精确命中关键字加权，
  再按首个命中源的优先级；
- 源状态由 `SourceStatus` 传递（成功/空/失败/超时/隔离），聚合模块只负责汇总、不决定跳过；
- 每个聚类带 `origin -> 条目` 的备选表，直接喂给下方换源菜单。

## 书架操作层（第八轮）

- `bookshelf/ShelfOps`：内存书架上的增删分组排序全为纯函数。
  重复加书只刷新元信息（封面/简介/最新章），用户域（分组、排序序、阅读位置）绝不被覆盖；
  移出为软删除，进度保留，`parked()` 列出「稍后再读」候选。
- `bookshelf/SourceSwapper`：换源进度映射，置信度四档：
  `EXACT`（归一化标题唯一对应）→ `NUMBER`（章节号最近者对应，含「012」前导零花招与中文数字）
  → `INDEX`（按序号对齐，提示用户核对）→ `RESET`（从头再读）；
  标题归一化标题在两侧目录重复出现时（卷名/公告类）不走 EXACT，避免把进度带歪章。

## 书源调试管线

`source/debug/SourceDebugger`：搜索 / 详情 / 目录 / 正文四个阶段各有一个独立 `check*` 检查方法，
`buildReport` 串成一条链——前一阶段失败后，后续阶段不再执行并标记「上一步失败，未继续」；
`staticCheck` 只做配置字段体检，不发请求，用于书源列表批量自检。

## 本地解析代理

`LocalParseProxy` 仅监听 `127.0.0.1` 随机端口，每次播放会话签发随机 token：
会话有 TTL 与数量上限，同 URL 并发解析会合并，上游地址过滤 CR/LF 防响应头注入。

## 合规门禁

```bash
bash tools/ci.sh
```

四道门禁：权限白名单、免责声明、无遥测出口、架构契约。

## 单测

纯 JVM 逻辑层全部可脱机验证，不依赖 Android SDK：

| 套件 | 覆盖 |
|------|------|
| `tests/RuleEngineDispatchTest.kt` | 规则拆解与执行派发（33 项） |
| `tests/JsonXpathTest.kt` | JSONPath / XPathLite 求值（64 项） |
| `tests/SourceLayerTest.kt` | 源健康度隔离 + 三类源配置校验（85 项） |
| `tests/ReadingLayerTest.kt` | 阅读设置往返 + 翻页状态机 + 分页链路（126 项） |
| `tests/BackupLayerTest.kt` | 备份编解码容错 + 合并策略（89 项） |
| `tests/SourceImportLayerTest.kt` | 书源导入解析与同步策略（161 项） |
| `tests/ShelfAggregateLayerTest.kt` | 聚合搜索 + 书架操作 + 换源映射（101 项） |
| `tests/DanmakuFilterTest.kt` | 弹幕渲染参数 + 过滤屏蔽/合并去重/同屏截断（55 项） |
| `tests/SecurityGuardTest.kt` | 安全闸门（URL/SSRF/重定向/限流/HTML 判定） + 权限分级（114 项） |
| `tests/EngineRuleSyntaxTest.kt` | 规则语法层（RuleSyntax 顶层切分/正则替换/put/get/模板/索引/规则类型 + UrlTemplate + SortUrlParser 静态/动态降级/截断）（69 项） |
| `tests/EngineCoreTest.kt` | 核心引擎层（ChannelSticky 2 次命中/7 天 TTL/源清理 + 限流桶/并发闸 + DomParser 降级链/2MB 阈值 + Sniffer 媒体命中 + JsExtensions）（84 项） |
| `tests/RuleEngineDispatchRound2Test.kt` | 派发第二批（CSS 尾接 `@js:` 转换、`<js>` 整段、XPath /@attr 指令透传、尾部索引越界/负索引/col-* 类名边界、absolutize scheme 缺失补全、@put/@get/{{变量}} 联动）（21 项） |
| `tests/NetworkDispatcherE2ETest.kt` | 网络派发层执行期权限闸与通道缝（默认通道直达、源限速第 3 次拒绝、WAF 指纹两击绑定、Cronet 抛错回落 OkHttp、空 HTML 判指纹拦截、Cookie 注入标记与 Set-Cookie 回写、通道粘滞绑定、SSRF 子资源转发校验/剥 Cookie/方法限流/异常吞并、执行期方法授权拒绝）（46 项） |
| `tests/RuleChainTest.kt` | 规则编排层 RuleChain（`||`/`&&` 顶层切分、花括号内不切、`@put` 先于 `##` 解析、无替换段删除匹配、坏正则不抛、shouldAccept/finish/merge 语义、allPuts/allGets 去重、空规则规划）（52 项） |
| `tests/JsRuntimePoolTest.kt` | JS 运行时池与 RunnerInstance（CAS 状态机生命周期、同层耗尽不互借、低档共享高档池、POISONED 销毁不回池、Reaper 2× 超时剔除与代数自增、缩容/低内存回收）（50 项） |
| `tests/SourceRouterTest.kt` | 源调度层与健康隔离（SourceRouter 四类源调度、单源失败不影响其它源、3 连败隔离与恢复、超时计入失败、正文三形态分流与错误映射、弹幕首命中/无命中错误码、订阅源不写隔离、并发闸门峰值）（51 项） |

完整运行方式见 `tools/test_jvm.sh`。

## 构建

需要 Android Studio / AGP。本仓库为架构骨架，不含完整 UI 与资源，无法直接出包。

## 说明

本项目不内置、不分发任何书籍或影视内容，也不内置任何源规则；
仅提供阅读与解析引擎能力，内容与源的获取、合规性由使用者自行负责。
