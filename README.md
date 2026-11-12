# 蓝阅 Android 阅读器（架构骨架）

自托管、本地优先的 Android 阅读器骨架。纯本地运行，无埋点、无遥测、无第三方统计 SDK。

## 文档

开发路线、任务清单与接手须知在 [`docs/`](docs/)：

| 文档 | 用途 |
|---|---|
| [`docs/APK-DEVELOPMENT-PLAN.md`](docs/APK-DEVELOPMENT-PLAN.md) | APK 开发大纲：现状盘点、里程碑路线（M1–M6）、工作量估算、技术决策备忘 |
| [`docs/TASK-BACKLOG.md`](docs/TASK-BACKLOG.md) | 可勾选任务清单：每条含现状、要做、验收标准；附依赖关系图 |
| [`docs/AI-HANDOFF.md`](docs/AI-HANDOFF.md) | AI 接手须知：环境重建（工具链+编译桩）、测试跑法、已知坑、代码纪律 |

**接手请先读 `docs/AI-HANDOFF.md`**——里面写了如何从零重建 JVM 测试环境，
以及 `jsoup` 版本故意不一致、测试类需手动注册等会重复踩的坑。

**当前阶段**：逻辑层完备（83 个 Kotlin 源文件 / 13,484 行），应用外壳（第二十四轮）、
构建入口（第二十五轮）、启动壳（第二十六轮）、构建配置对齐（第二十七轮）与
单测清单对齐（第二十八轮）已补齐。
`./gradlew` 已可用（Gradle 8.9 已锁定），Compose Compiler 插件已按 Kotlin 2.0.20 对齐，
清单声明的四个组件均有实现。`./gradlew assembleDebug` 在装有 Android SDK 的机器上
已无「缺类」与「缺插件」两类阻塞，但本沙箱无 SDK，故未在此验证，见 TASK-BACKLOG 的 M1。

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
│   ├── build/         构建入口约束（Wrapper 配置解析与校验）
│   ├── shell/         启动页纯逻辑（标题恢复校验 / 版本标签 / 占位模型）
│   ├── diagnostics/   应用外壳支撑（日志下限 / 惰性图 / 崩溃兜底）
│   └── observability/ 本地指标（仅落盘，不外发）
├── source/
│   ├── model/         源字段/类型契约（SearchBookItem / TocItem / SourceResult）
│   ├── rule/          规则层（RuleSyntax 语法 / RuleChain 编排 / RuleEngine 执行）
│   ├── aggregate/     多源聚合搜索（去重、书名归并、空作者回填）
│   ├── import/        书源导入解析（Legado JSON / 订阅合集）
│   ├── repo/          源仓库与同步
│   ├── debug/         书源离线调试管线（搜索→详情→目录→正文串链路）
│   ├── purify/        正文/标题替换净化（ReplaceRule 执行、自检、书源 replaceRule 编解码）
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

## 影视站点与弹幕

- `TvBoxSiteInstaller`：把一份 TVBox 配置里的 `sites` 安全并入本地库。
  站点主键是 **(configUrl, siteKey)** 复合键，而不是单独的 siteKey——
  不同仓库存在大量同名 key（如 `csp_xxx`），单键会把两个仓的站点互相覆盖。
  重复导入是幂等的：定义未变计入 `unchanged`，不产生无意义写入；
  定义变更才更新，且**保留用户手动设置的启用状态与快搜偏好**，
  避免升级仓配置把用户主动关掉的站点又打开。
  本次导入内部的重复按后者生效并计数；`type` 越界时按 api 后缀
  （`.js` / `.py` / `.jar` → 3，其余 → 1）回推，jar 站点标记「暂不支持」并自动关闭搜索。
- `DanmakuTimelineScheduler`：把弹幕事件排进屏幕轨道，纯计算、可脱机验证。
  轨道按模式分流（滚动 / 顶部 / 底部各自独立轨道池），进场时间即事件时间，
  离场时间由 `scrollDurationMs` / `fixedDurationMs` 决定。
  追尾判定用「上一条离场 + `laneGapMs` 安全间隔」与当前进场时间比较，
  轨道全忙时默认丢弃并计入 `droppedByLanes`；`allowOverlap` 打开则降级同轨显示并打 `overlapped` 标。
  另有两道保护：同 100ms 桶内「模式 + 文本」相同的视为重复只留一条，
  以及 `maxPerSecond` 每秒密度上限，防止刷屏瞬间把轨道打满。

## 替换净化（第十九轮）

`source/purify/TextPurifier`：正文与标题的替换净化，对齐 Legado `ReplaceRule` 能力。

规则按 `order` 升序执行，`order` 相同则保持声明顺序，保证结果可复现。
作用域用 `PurifyScope` 枚举（`CONTENT` / `TITLE` / `ALL`）显式声明；Legado 那边是
null / `"title"` / `"content"` 的字符串约定，拼错不报错、整条规则静默失效。

四条保护都是为了修掉会真实毁掉整章正文的情形：

- **非法正则只废这一条**。编译失败计入 `failed` 后继续执行后续规则，不中断整章净化；
  替换模板里的非法分组引用（只有 2 组却写 `$9`）同样只废这一条。
- **非正则规则绝不走正则引擎**。书源作者写 `(*广告*)` 这类字面量时，
  当正则会编译失败或让元字符改写语义，所以字面量分支只做 `String.replace`。
- **空匹配串直接跳过**。`replace("", x)` 会把 x 塞进每个字符间隙，一次就把正文毁掉。
- **净化后长度骤降则回退原文**。错误规则（例如把 `.` 当正文全替）会把正文清空，
  这种结果比不净化更糟；比例闸只对正文生效，标题天然会大幅缩短，用同一阈值会误判。

另有 `firstLineOnly` 只处理首行，用于「章节标题混进正文首行」的场景——
整篇替换会误伤正文里的同名字符串。空白归一化处理非断空格 / 全角空格 / 行尾空白，
并把连续空行折叠成一个：爬来的正文里这几类字符极常见，不归一化会让去重与搜索全部失效。
超过 `maxTextLength` 的文本整篇放行，避免灾难性回溯拖死主线程。

`TextPurifier.validate` 供书源编辑器做规则自检，返回可直接展示的中文问题列表。

### 书源 replaceRule 接入（第二十轮）

`source/purify/PurifyRuleCodec`：把书源 JSON 里的 `replaceRule` 解成可执行规则，
并解决两个按 Legado 文档直译必然写错的地方。

- **`isRegex` 的默认值是 false，不是 true**。Legado 书源作者不写 `isRegex` 时，
  这条规则是字面量替换。若按我们 `ReplaceRule` 的默认值 true 去反序列化，
  作者写的 `(*广告*)` 会被当成正则编译失败，整条规则静默失效——而且作者无从察觉。
  所以解码时缺省一律取 false。
- **作用域拼错必须能被看见**。Legado 用 `null` / `"title"` / `"content"` 字符串约定，
  写成 `"titel"` 不报错、整条规则静默不生效。这里对未知值给出中文问题提示并退回
  `ALL`，让作者在编辑器里能直接发现。

其余容错都按「坏数据不废整份书源」处理：坏 JSON 标记 `broken` 并放弃规则但不抛异常；
单条缺 `pattern` 或不是对象只跳过该条，其余照常保留；空 `pattern` 与负 `sortOrder`
保留原值并给出提示（保留是为了让作者能看见并修掉，而不是被静默删除）；
布尔与整数字段容忍 `"true"` / `1` 这类手写形态。

`BookSourceEntity` 新增 `replaceRule` 字段，按原样存 JSON 串而非拆表：
书源更新时要能原样回写，解析失败也不能丢原文。

`PurifyRuleCodec.merge` 的语义是**源级覆盖全局、而非整体替换**。
同一 `(pattern, scope)` 视为同一条规则，由源作者的决定优先；但全局规则里
跨站通用的部分（盗版水印、站点后缀）会保留下来——如果源级把全局整份顶掉，
其他站点的这些脏数据会重新冒出来。

### 净化管线（第二十一轮）

`source/purify/PurifyPipeline`：把「全局规则 + 书源 replaceRule」合成一份可执行的
净化配置，并在进程内按源缓存，供正文/标题两条链路直接取用。

三次调用拼配置是最容易出错的地方：书源 JSON 解一次、全局规则合一次、排序再一次，
分散在调用方就会各写各的。管线收口成 `profileFor(sourceId, replaceRuleJson)`，
结果按源缓存，书源或全局规则变动时用 `invalidate(sourceId)` 精确失效，
避免整表清空导致所有源重新解析。

合成顺序是**先全局、后源级覆盖**，与 `PurifyRuleCodec.merge` 的语义一致：
同一 `(pattern, scope)` 由源作者决定，跨站通用的全局规则保留。缓存键包含
规则原文的哈希，源作者改一条规则后无需手动刷新。

`purifyContent` / `purifyTitle` 直接返回 `PurifyResult`，把 `TextPurifier`
的作用域分流、比例回退、空匹配跳过等保护一并继承下来；调用方拿到的
`stats.applied` 可直接展示成「本次净化命中 N 条」，便于作者判断规则是否真的生效。

`SourceDebugger.checkContent` 增加了可选 `purifier` 参数。**判定必须在净化之后**：
未净化的正文里常混着水印与站点后缀，字符数被噪声抬高，会把本该判「过短」的页面
判成通过——调试面板因此永远显示绿色，作者完全看不出问题。传入净化器后先净化
再比长度，噪声撑起来的假通过会被降级成警告。

### 朗读（第二十二轮）

`core/tts/TtsSegmenter` + `core/tts/TtsReadController`：把整章正文切成可逐段送入
系统 TTS 的句子块，并维护「当前读到哪一段」。

需求文档只在 P1 写了一行「朗读/翻译/词典：TTS 兜底」，此前进度表里的朗读能力
在代码里是空的——底栏与长按菜单都还没有落点。这一轮先把**不依赖设备的那一层**
做出来：分段与调度都是纯逻辑，可以在 JVM 上跑单测，真机只需接 `TextToSpeech`。

**为什么必须分段，而不是整章一次 `speak`。** `speak` 是异步排队，整章入队后
「暂停」「跳段」的粒度就退化成整章，用户想跳过一段站点后记也做不到；多数引擎
对超长入参有内部截断，表现是「读到一半突然没声」，而且不抛任何错误，排查时
极难定位；逐句入队才能做跟随高亮与断点续读。

**切句规则按中文正文的实际排版定。** 只在引号以外的句末标点切——引号里的是
台词，「他说：」后面直接切开会把一句台词劈成两段，听感像换了个人在念。右引号、
右括号并入前一段，否则引擎会把「」」单独读成「右引号」，那是很明显的机械感来源。
另外补了一条：**引号收尾且里面已是完整一句时也算句子边界**，否则
「他说：「你先走。」然后转身。」会整段连成一句，台词和叙述被捆在一起，
超长时还会在句子中间硬切。

**段首不许留标点，但偏移必须能对回原文。** 分段保留 `[charStart, charEnd)`
区间，用户朗读到第 7 段切回阅读页，应停在正文对应那句话。只存段号还原不了位置：
分段结果会随引擎长度上限与正文规范化而变，段号与字号的对应关系并不稳定。
`normalize` 因此只做**等长**的 1:1 替换（全角空格、`\r`），不敢合并连续空格、
不敢删零宽字符——一旦长度变化，所有段的偏移整体错位。

**调度器守住的几条约束。** 引擎回调可能迟到：用户按停止后 `onSegmentFinished`
仍可能再来一次，若无条件前进光标，会出现「已经停了却又自动念下一段」，所以只在
`SPEAKING` 态接受回调。空内容不进 `SPEAKING`——否则底栏图标变成「朗读中」却
永远没声音。暂停后恢复从当前段继续，不从头重读：用户暂停是为了处理别的事，
回来重听整章会直接关掉这个功能。读完停在 `FINISHED` 等上层决定是否自动跳章，
调度器自己循环会导致「翻到下一章又被拉回来」。语速音调越界直接夹取而不抛异常，
因为这两个值来自滑块，越界是常态输入。

### 弹幕源配置层（第二十三轮）

`danmaku/DanmakuSourceValidator` + `danmaku/DanmakuSourceRegistry`：把「弹幕源怎么存、
怎么排、什么能存」这三件事从 UI 和 DAO 里收口出来。

此前的状态是断的：`danmaku_sources` 表有 `apiBase` / `enabled` / `sortOrder` /
`verified` 四个字段，DAO 却只有 `ORDER BY sortOrder ASC` 的**读**，没有任何写入
排序号的入口；也没有一层拦下明显写错的地址。两个后果都很实在：

- 新建源的 `sortOrder` 由调用方随手给 0，多个源并列时 SQLite 返回顺序是未定义的，
  表现是「匹配源每次播放都不一样」——用户明明只想要 A 源，偶尔却拿到 B 源，而且
  复现不了；
- 用户把 `https://example.com/api` 漏写成 `example.com/api`，或漏掉协议头，
  保存成功、播放时静默不匹配，看起来就是「弹幕功能坏了」。

**只填 apiBase 即可保存，探活失败也能存。** 4.10.1 的必填只有 `apiBase`，
4.10.6 要求探活失败标「未验证」而不是拒绝保存。这一点容易写反：临时离线、限流、
源站重启都会让一次探测失败，此时若禁止保存，用户改了一条**正确**的地址却因为
网络抖动被卡住，会反复重试并最终放弃这个功能。所以校验返回三态 `Ok` /
`Unverified` / `Rejected`：只有格式明显错误（空、非 HTTP、主机名不成形）才拒绝，
探活失败照样给出可保存实体并带上失败原因。

**规范化必须剥离端口再判主机名。** 最初写成「主机名不含点就判非法」，结果
`localhost:8080` 被误拒——带端口的单段主机名被当成了非法输入。同理，
`https://192.168.1.10:9000/api` 这类自建源也要能用。补协议头是刻意的（手抄地址
最常见的错误就是漏 `https://`），但补完仍要校验，否则 `api` 这种单词会被补成
`https://api` 而通过，等播放时才暴露。未启用的源保存时不探活——存一条备用源
不该触发网络请求。

**排序的坑在于「移动后不能交给 normalize」。** `normalize` 的职责是按现有
`sortOrder` 排序再重编号，用于消除删除留下的空洞（0,1,3）。但 `move` 只调整了
列表里的位置，各元素仍带着旧序号，如果直接把结果交给 `normalize`，它会按旧序号
把列表**原样排回去**，移动等于没发生。所以 `move` 必须在本地直接重编号。
并列 `sortOrder` 时用 id 做次级键：DAO 返回的并列顺序在不同查询计划下可能变化，
用 id 兜底至少保证同一份数据每次算出的顺序一致。

**几处刻意的限制。** 移动越界不环绕——环绕会让「已在第一位」的源突然跑到末尾，
用户连按两次上移会得到完全相反的结果。不允许关闭最后一个启用源：全部关闭等价于
「弹幕功能消失」，而用户按的只是某个源后面的开关，想彻底停用应当走弹幕总开关。

### 应用外壳（第二十四轮）

`LanyueApp` + `core/diagnostics/`（`LogPolicy` / `AppGraph` / `CrashGuard`）。
本轮的产出不是功能，是**「清单指向的类真实存在」**这个事实，以及三件被拆成纯函数、
可以脱机断言的外壳职责。

**为什么拆成三层而不是全部塞进 `Application`。** `Application` 继承 `android.app.Application`，
在 `tools/test_jvm.sh` 里根本编不过（该脚本只挂 Android 打桩，没有 `Application` 基类），
如果日志判断、崩溃落盘逻辑都写在 `onCreate` 内部，它们就永远只能靠真机验证。
所以 `LanyueApp` 只留接线：判断「是否可调试」→ 组装三件套 → 打一行日志。
凡是能判断对错的部分都下沉到 `core/diagnostics/`，进 JVM 单测。
`test_jvm.sh` 的 `EXCLUDES` 因此新增 `LanyueApp.kt` 一条，与既有的 `data/db/LanyueDatabase.kt`
同一类处理：**依赖 Android 基类、但依赖面很窄**的接线文件不进纯 JVM 编译。

**日志下限不能读 `BuildConfig.DEBUG`。** 它是 AGP 生成的，而纯 JVM 测试链不跑 AGP，
诊断层一旦引用它就无法离线编译，判断写反了也要等真机才发现。改成从
`ApplicationInfo.FLAG_DEBUGGABLE` 推导成 `BuildType` 后，`isEnabled(level, type)`
退化成纯函数。release 只放行 `WARN` 及以上：省下的不是「输出干净」，而是
翻页这类高频路径上被过滤掉却**已经求值过**的参数拼接——真正的开关必须发生在调用点。

**「不在 `onCreate` 里做重活」这条约束必须有可断言的信号。** 否则它只是一句
写在文档里的自觉。`AppGraph<T>` 把惰性持有做成可观测对象：`isCreated` 能在建库前后
被断言，`LanyueApp.isDatabaseCreated()` 就是这条约束的探针。这也解释了为什么
没用 `by lazy`——`by lazy` 行为没错，但外部看不见它到底建没建。

**构造失败不缓存。** `AppGraph` 的工厂抛出后 `cached` 仍为 null，下次取用会重试。
反过来（把异常态也缓存）看似「稳定」，实际是把一次磁盘瞬时不可用钉成整个进程的
坏状态，用户只能靠杀进程恢复。

**崩溃兜底：落文件、不上报、交还原处理器。** 三条都是刻意的：

- 文件写在 `filesDir/crash`，卸载即清除，不进外部存储——调用栈里可能带用户书籍
  路径与源地址，多一份明文拷贝没有收益；
- 时间戳与文件名统一 UTC。按本地时区写会让跨设备日志无法按文件名排序，
  「哪次崩得早」变成猜测；
- 处理完**仍然**把异常转交 `Thread.getDefaultUncaughtExceptionHandler()`。
  吞掉它会让进程半死不活，用户看到「点不动」而不是「崩了」，比崩溃更难定位。

文件名里要嵌线程名，而协程默认线程名形如 `DefaultDispatcher-worker/1`，带 `/` 与空格，
不净化会触发「文件名非法」的**二次**异常——在崩溃处理里再崩一次是最难查的形态。
所以 `sanitizeThreadTag` 只留字母数字与 `-_`，并按 24 字符截断。这两条规则的
叠加顺序也固定下来了（先替换、后截断），单测里锁死，避免日后调上限时静默改变行为。

报告保留份数默认 5 份。连环崩溃（每帧都崩）会持续追加，无限写入会把磁盘吃满，
反而让用户连「卸载前导出」都做不到；按文件名里的时间戳升序删旧，
不依赖文件系统 mtime——后者在批量写入或时钟校准后会乱序。

**本轮没有顺带做的事**：`Gradle Wrapper`（T02）、`MainActivity`（T03）仍未补，
所以 `./gradlew assembleDebug` 依旧失败。按 AI-HANDOFF 的判断，
M1 阶段一次只消除一个阻塞，构建配置错与 UI 代码错混在一起时无法归因。

### 构建入口（第二十五轮）

Gradle Wrapper 三件套 + `core/build/WrapperSpec` + `tools/check_wrapper.py`。
T02 的产出同样不是功能，而是「`./gradlew` 能起来」这个事实。

**为什么必须锁版本，而不是让 wrapper 自己选。** `distributionUrl` 决定所有机器
实际跑哪个 Gradle；写成 `-latest` 或 `-rc`，本地与 CI 会在不同版本上构建，
而「本地能过、CI 挂」的排查成本远高于固定一个版本。因此这里锁 `8.9`：
AGP 8.5.2 要求 Gradle ≥ 8.7，8.9 是同代稳定组合。

**锁了版本还不够，必须锁分发包完整性。** Wrapper 首次运行会下载 zip 并执行其中
代码，`distributionSha256Sum` 是唯一能挡住「下载到被替换的包」的手段。
本轮把官方值 `d725d707...cecab` 写进 properties，并**实测下载后 `sha256sum -c` 通过**——
只抄一个数字而不验证，等于把校验变成装饰。同时 `validateDistributionUrl=true`。

**为什么把配置检查写成代码而不是提交前肉眼过一遍。** properties 里
`distributionUrl` 的版本语义有一处很容易错：若按「第一个 `-` 之前」截取版本，
`gradle-8.9-rc-2-bin` 会被读成 `8.9`，于是 **rc 版被判定为已锁定而放行**。
这个缺陷是写完测试后被测出来的——它正是「门禁本身有 bug，比没有门禁更危险」的例子。
正确做法是先剥掉尾部 `-bin`/`-all`，再整体取版本串，让预发布标识保留下来供校验拒绝。
`WrapperSpec.version` 与 `isPinnedVersion` 就是这条规则的落点，单测把 rc / SNAPSHOT /
latest / 三段式版本 / 恰好等于下限的 8.7 逐一钉死。

**门禁只管静态层面，不做联网。** `tools/check_wrapper.py` 检查四件齐全、
`gradlew` 可执行位、版本锁定与 AGP 大版本对齐、sha256 格式合法。它不联网：
每次提交都去拉分发包既慢又会在离线环境误报。校验和与真实包是否一致属于
一次性核对，本轮已人工跑过并记录。

**关于 `assembleDebug`。** 它**仍然失败**，但原因已经从「没有 wrapper」变成
「缺 `MainActivity`（T03）且构建机需要 Android SDK」。本沙箱无 SDK，
无法在此跑真实构建，因此本轮不宣称 `assembleDebug` 通过——
T02 的验收是 `./gradlew --version` 成功，这一条已用 JDK 17 实测（Gradle 8.9 /
Launcher JVM 17.0.2 / exit 0）。把没验证的事说成验证过，比没做更糟。

### 启动壳（第二十六轮）

`ui/MainActivity` + `core/shell/ShellSpec`。补齐清单声明的 `.ui.MainActivity`，
使「缺类」这一硬编译阻塞在 T03 范围内消除。

**为什么不直接把三段 `Text` 写进 Activity。** `MainActivity` 继承 `ComponentActivity`，
`tools/test_jvm.sh` 编不过它（纯 JVM 链没有 Compose 与 Android 基类）。
一旦把「标题显示什么」「恢复的状态是否可信」写成 Composable 内部的分支，
这些判断就只能靠真机点一遍才知道对错——而「切后台回来标题变空」恰恰藏在这种
分支里。因此判断逻辑全部下沉到 `ShellSpec`（纯函数、可离线断言），
Activity 只保留接线与布局。

**恢复路径必须容忍脏数据。** `rememberSaveable` 拿回来的值来自上一个进程或更早
版本，可能是空串、纯空白、含换行制表符，或超长。直接展示的结果是空标题或标题
撑破布局。`sanitizeRestoredTitle` 对这几类一律判为不可用并回落到资源值：

- 空/空白 → 回落。否则界面出现一行空白标题，看起来像渲染失败；
- 含控制字符（换行、制表）→ 整条拒绝，而不是静默清洗。静默清洗会让
  「标题为什么少了两个字」无从追溯；
- 超过 32 字符 → 整条拒绝。截断看似更「友好」，但同样会把真实原因藏起来，
  且截断点会随字体变化产生不同的断字效果。

合法的恢复值则**优先于**资源值，这是旋转后标题不回退的前提。

**版本标签不允许出现 `v` 或 `vnull`。** `BuildConfig.VERSION_NAME` 在某些构建
变体下可能为空；直接拼 `"v$name"` 就会渲染出 `vnull`。`versionLabel` 对空值、
纯空白、字面 `null` 统一回落为 `v?`——用问号明确表示「未知」，而不是编一个版本号。

**占位壳不得引入业务 UI。** T03 的要求是空壳，`isPlaceholderOnly` 把这条要求写成
机器可读的判据；单测同时扫描 `MainActivity.kt` 源码，禁止出现 `ShelfScreen`、
`DiscoverScreen` 等业务界面符号。这样后续轮次误把书架页塞进启动壳时，测试会先失败，
而不是等到发版前才发现。清单里的两个 service（`MediaDownloadService`、
`AudioPlaybackService`）也不在本轮被伪造实现，单测反向确认它们仍只是声明。

**关于 `assembleDebug`。** 清单声明的两个类现已都有实现，就「缺类」而言阻塞已消除。
但本沙箱没有 Android SDK，无法在此执行真实构建，因此本轮不宣称 `assembleDebug` 通过。
T03 的可验证部分是：`MainActivity` 存在、包路径与清单一致、继承与 `setContent` 正确、
判断逻辑有单测覆盖。

### 构建配置对齐（第二十七轮）

补齐 Compose Compiler 插件声明，并把「配置错」这一类失败写成可离线断言的规则。

**本轮修掉的第一个硬阻塞：`compose = true` 却没应用 Compose 插件。**
Kotlin 2.0 起 Compose Compiler 从编译器内部拆成独立 Gradle 插件
（`org.jetbrains.kotlin.plugin.compose`），模块开启了 `buildFeatures { compose = true }`
却不应用它，会在**配置阶段**直接失败，而报错信息指向某个 task，离根因很远。
本轮在根脚本以 `apply false` 声明版本、在模块 `plugins {}` 中应用，两处缺一不可。

**版本必须与 Kotlin 完全一致，包括补丁号。** 该插件由 Kotlin 官方发布，
`2.0.20` 与 `2.0.21` 会拿到不匹配的编译器桥接，报错出现在编译期且措辞晦涩。
`BuildScriptAudit.composeProblems` 对三者做精确比对，不做前缀或模糊匹配——
「差一位也算不一致」这条用单测钉住了。

**为什么把配置检查写成纯函数。** `assembleDebug` 的失败分两类：「代码错」与「配置错」。
代码错有编译器指路，配置错往往在配置阶段就终止，日志离根因很远。
`core/build/BuildScriptAudit` 把四条规则下沉为纯逻辑，可在离线链里跑：

- `composeProblems`：开启 compose 必应用插件；应用插件必在根脚本声明版本；
  插件版本必须等于 Kotlin 版本。三个条件任一不满足都报错，且各报一条不互相淹没。
- `requireAndroidXEnabled`：存在 `androidx.*` 依赖时必须 `android.useAndroidX=true`，
  写成 `false` 同样报错——「写了」不等于「开对了」。
- `buildConfigProblems`：源码引用 `BuildConfig.` 时必须开启
  `buildFeatures.buildConfig`，AGP 8 起该开关默认关闭，不显式打开会编译失败。
- `parsePlugins`：只认 `id("...")` 全名写法。刻意不支持 `kotlin("android")` 别名，
  否则同一件事会有两种写法，审查面反而扩大。

**一处需要更正的事实。** 上一轮汇报称清单里的两个 service「仍未实现」，这是错的。
`player/MediaDownloadService.kt`、`player/AudioPlaybackService.kt` 与 `PlayerManager.kt`
在 T03 之前就已存在。本轮单测改为正向核对：清单声明的四个组件
（`LanyueApp`、`.ui.MainActivity`、两个 service）逐一确认「有声明且有实现」，
把这条对齐关系固定下来，而不是靠人工记忆。序列化依赖同理：`kotlinx-serialization-json`
已引入但代码中零处 `@Serializable` 引用，因此不构成缺插件的阻塞，本轮不为它加插件。

**关于 `assembleDebug`。** 本沙箱仍无 Android SDK（且磁盘可用约 1GB、
`dl.google.com` 实测速度过低），无法执行真实构建，因此本轮**不宣称**它通过。
本轮可验证的部分是：插件声明正确、四项配置规则全部满足、清单四个组件与实现一一对应，
这些都有离线断言覆盖。

### 单测清单对齐（第二十八轮）

把「文档里写了」和「脚本里跑了」这两件事对齐，并修掉两处会阻断脱机验证的工具链缺口。

**第一个缺口：`tests/VideoDanmakuLayerTest.kt` 写进 README 却没被脚本执行。**
该文件有 415 行、126 项断言，覆盖影视站点安装幂等与弹幕轨道调度，
是 R8 阶段最核心的一组用例。README 的表格早已列出它，但 `tools/test_jvm.sh`
的执行清单里没有 `VideoDanmakuLayerTestKt`——于是它从未在回归里跑过，
「全部通过」这句话对它并不成立。本轮把它加入执行清单。

**第二个缺口是一处需要澄清的历史遗留。** `tests/NetDispatcherE2ETest.kt` 只有两行注释：

```kotlin
// 本文件为第十三轮遗留占位。
// 内容已合并到 NetworkDispatcherE2ETest.kt，不再单独执行。
```

它编译后不产生任何类，`main` 自然也不存在，强行注册只会得到一个
`ClassNotFoundException` 式的假失败。因此本轮**不**把它加进清单，
而是在脚本里就地写清原因，避免下一轮再有人把它当成「漏注册的测试」重犯一遍。
真正的用例集中在 `tests/NetworkDispatcherE2ETest.kt`（46 项），一直在跑。

**两处工具链缺口。** 这两处不写在仓库里的业务代码上，但只要缺了，脱机回归就跑不起来：

- `org.jetbrains.annotations` 必须出现在编译期 classpath。缺它时错误出现在
  后端 IR 降低阶段，报成 `ERROR_EXPR 'Default Stub'`，看上去像是源码里有个
  悬挂的默认参数表达式，实际与被测代码毫无关系——这类假错误极易把人引向
  改源码的错误方向。现在它被显式写进 `JARS`，并注明了原因。
- `jsoup` 1.17.2 的包级 `@NullMarked` 会让 Kotlin 把 `Elements.first()`
  这类返回值判定为可空，从而在既有断言上产生「类型不匹配」。
  源码本身不以 JSpecify 为注解契约，因此显式传 `-Xjspecify-annotations=ignore`，
  保持既有断言语义不变，而不是去给调用点加一串无意义的 `!!`。

**一处数字更正。** 上一轮 README 里 `WrapperConfigTest` 记作 53 项，实测为 54 项。
本轮按实际输出改正，并补上「合计 30 套 / 2097 项」的总述——
表格逐行数字现在与脚本输出一一对应，可以在一条命令里复核。

### Room schema 导出（第二十九轮）

给数据库补上迁移基线。这一轮的核心不是"多写一个配置"，而是让一个
**不会报错、只会静默失效**的东西变得可被检查。

**问题在于三处必须同时成立。**

Room 的 schema 导出不像普通功能那样"配了就生效"，它要求三件事同时为真：

1. `@Database(exportSchema = true)` —— 数据库类同意导出；
2. `ksp { arg("room.schemaLocation", ...) }` —— 告诉处理器往哪写；
3. `app/schemas/<version>.json` —— 产物真的生成、并提交进版本库。

仓库此前只有第 1 条。缺第 2 条时，Room 的行为是**构建照常成功**，
只在日志里留一行 warning 说 schema 无处可写。这类失败没有任何信号：
CI 是绿的，APK 打得出来，直到某天有人给实体加字段，才发现根本没有基线——
写不出 `MigrationTestHelper` 需要的迁移测试，只能退回
`fallbackToDestructiveMigration()`。代价是用户一升级，本地数据全丢。

本轮补上第 2 条：

```kotlin
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
```

路径必须用 `$projectDir`，不能写绝对路径：绝对路径会把本机目录写死，
换一台机器或换一个 CI runner 就失效，而失效方式同样是静默的。

**关于第 3 条，需要如实说明：本轮没有生成 schema JSON。**

`schema/1.json` 由 Gradle + KSP 在构建期算出，其中 `identityHash` 是
Room 对表结构的内部哈希结果——它不是可以手推的值。我尝试过两条离线通路：

- `kapt`：`room-compiler` 确实带 `RoomProcessor` 的 javac APT 入口，
  但 Kotlin 2.2 已弃用 kapt，且沙箱内 `kotlin-annotation-processing` 制品
  下载不完整（472 KB，zip 校验失败），运行到插件注册阶段即
  `AbstractMethodError`；
- KSP cmdline 插件：`symbol-processing-cmdline` 可离线驱动，插件 ID 与
  参数名（`apclasspath`、`apoption`、`projectBaseDir` 等）都核对无误，
  但无论是全量工程还是只含"一个实体 + 一个数据库类"的两文件最小工程，
  进程都以 0 退出、输出目录全空、且没有 `[ksp]` 日志——处理器没有被加载，
  而现象与"成功但无线索"完全一样。

这两条都卡在同一个前提上：真实通路需要 AGP 提供的 Android 变体模型，
而这个沙箱里没有 Android SDK 与 Gradle。**我不会手写一份 JSON 冒充 KSP 产物**：
那样 `identityHash` 必然与真实结构不符，`check_schema.py` 会变绿，
但这份"基线"在真正做迁移时会立刻失效——比没有基线更危险。

所以本轮交付的是可离线验证的部分，缺的部分写清楚：

| 交付物 | 说明 |
|--------|------|
| `ksp { arg("room.schemaLocation", ...) }` | 补上缺的第 2 条，注释写明三处缺一即静默失效 |
| `core/build/SchemaExportSpec.kt` | 纯文本解析 + 校验，不启动 Gradle、不联网 |
| `tests/SchemaExportTest.kt` | 33 项断言，覆盖注解解析、参数解析、配置组合、JSON 骨架 |
| `tools/check_schema.py` | 接入 `tools/ci.sh`，缺配置即让门禁失败 |

**门禁的取舍。** `check_schema.py` 对前两项（注解与 ksp 参数）**判错**，
对第三项（schema JSON 是否存在）只**报告不判错**。理由是产物生成依赖
构建环境，在没有 Android SDK 的机器上判错只会逼人伪造文件。它会在输出里
明确写"需在有 Android SDK 的环境执行一次 `./gradlew :app:kspDebugKotlin`"，
而不是假装通过。

**待办**：在有 Android SDK 的环境跑一次 `:app:kspDebugKotlin`，
把生成的 `app/schemas/1.json` 提交。此后 `check_schema.py` 会自动开始
校验它的 `version` 与 `@Database.version` 是否一致。

### 正文获取与净化串联（第三十轮）

`source/content/ContentStage`：把「正文获取 → 净化 → 交排版」收成一处可脱机验证的串联，
并补上 T15 缺的最后一块——**按章缓存净化结果**。

**为什么这一层不能省。** 此前 `PurifyPipeline` 缓存的是「编译好的净化器」（按源），
每次翻章仍要对正文跑一遍正则替换；`ReadingSession` 排版但不净化。两层各自正确，
中间却没有把「跑完的净化结果」按章记下来的地方。结果是一章翻出去再翻回来，
净化器实例虽是同一个、正文替换却从头跑了一遍——长章节连续翻页时这是可感知的卡顿来源。

**净化必须在排版前，不是可选项。** 反过来（先排版原文再净化）会让字数变化导致分页错位：
水印被裁进某页后才被净化掉，该页字数骤减、全局字符偏移串位，进度条与翻页位置都跟着错。
单测里用「正文 100 字 + 水印 60 字」做对照——直接排版原文得到 160 字的分页，
先净化再排版得到 100 字的分页，后者才是与真实正文对齐的真值。

**结果缓存按章命中，指纹覆盖三类失效。** 缓存键是 chapterKey（章节 URL），但光靠它不够——
书源 replaceRule 改了、全局规则改了，旧结果都是脏的。指纹由
`sourceId + 全局规则 hash + replaceRule 原文 hash + 长度` 组成：原文不变时结果可复用，
原文一变必然失效；全局规则指纹取自 `PurifyPipeline.globalRulesHash()`，与它自身的净化器
缓存失效口径一致，不会出现「净化器已重建但结果缓存还在用旧货」。命中缓存时 `fromCache = true`，
上层可据此打日志计时——这是 T15 验收「同一章二次打开不重复净化」的观测点。

**`globalRulesHash()` 是本轮对 `PurifyPipeline` 的唯一改动。** 它只暴露已有的私有 `globalHash()`，
不改任何既有行为，纯粹让上层结果缓存能感知全局规则变化。

**段落拆分在净化之后。** 净化器的空白归一已把连续空行折叠；拆段再滤掉空行，因为
`Paginator` 自己会在段间插视觉空行，保留空段只会让分页多出无字行、把偏移推乱。

**本轮没有顺带做的事**：正文的真实获取（规则取数 + 网络请求）仍由上层注入——`prepare`
接收已取到的原始正文，本层只管净化 + 拆段 + 缓存。沙箱无 Android SDK，无法验证真机翻章
耗时与网络层接线。

### 朗读章节续读协调器（第三十一轮）

`core/tts/TtsChapterCoordinator`：把单章 `TtsReadController` 扩展为多章连续朗读，
补上 T22「章节读完自动续读」的纯逻辑层。

**为什么需要这一层。** `TtsReadController` 只管一章内的段推进与状态机——读完一章
停在 `FINISHED`，等上层决定下一步。但没有「上层」来接这个决定：要么用户手动翻章
（打断通勤/做家务时的连续收听），要么每个调用方自己写一遍续读逻辑（重复且容易漏
迟到回调保护）。协调器把这层决策收成一处可脱机验证的逻辑。

**续读默认开启。** 朗读场景下用户通常希望连续听完整本书，就像听有声书一样。默认
`AutoContinue.ENABLED`，读完一章自动加载下一章继续朗读；设为 `DISABLED` 时停在
`FINISHED`，等用户手动翻章。续读下一章时 `charOffset=0`——章与章之间的正文是独立的，
偏移不能跨章。

**迟到回调安全是底线。** 协调器的 `onSegmentFinished` 只在 controller 处于 `SPEAKING`
时才推进，停止/暂停后的迟到 `onDone` 返回 `IGNORED`，不会触发续读——否则会出现
「停了又自动念下一章」。这层保护与 `TtsReadController` 自身的迟到回调守卫一致，
不因多了一层包装而削弱。

**空章自动跳过。** 遇到无内容的章节自动跳到下一章（不论是否开启续读），否则用户点
朗读却没声音，以为功能坏了。`startChapter` 从目标章往后逐章找内容，跳过的章不算
朗读过；跳章时 `charOffset` 归零，偏移只对首个目标章有效。

**高亮区间数据由协调器输出。** `highlightRange()` 返回当前朗读段在原文中的
`[charStart, charEnd)` 区间，供 Compose 文本层映射到像素范围绘制高亮背景。区间取自
`TtsSegment` 的偏移，与朗读进度同一真值，不会出现高亮与语音不同步的数据层漂移。
续读跳到新章后高亮自动重映射到新章的段偏移。

**`onSegmentFinished` 的返回值让上层知道该做什么 UI 反馈**：`NEXT_SEGMENT`（更新
高亮位置）、`CONTINUED`（刷新正文与新章标题）、`STOPPED`（显示「本章读完」）、
`BOOK_DONE`（显示「全书读完」）、`IGNORED`（迟到回调，什么也不做）。

**本轮没有顺带做的事**：Compose 朗读控制条与高亮渲染（T21）、`TextToSpeech` 引擎
适配层（T20）、真机语音包与续读计时验证——均依赖 Android SDK。

### 下载队列协调器（第三十二轮）

`source/download/DownloadQueueCoordinator`：批量下载的队列管理与状态机，
补上 T40「批量下载、并发上限、失败重试与退避」的纯逻辑层。

**为什么需要这一层。** `MediaDownloadService` 只管**单任务串行**下载（前台服务的
硬约束），但没有「一批章节怎么排队、谁先谁后、失败了怎么办、哪些已经下过了」
的队列管理。如果每个调用方自己写一遍这层逻辑，不可避免会漏掉退避、去重或并发控制。

**已下载标记防重复下载。** `enqueue` 时自动跳过 `completedIds` 中的项——断网重进时
调用方把数据库里已标记完成的 ID 塞进 `preloadCompleted`，就不会重复下载。下载成功后
`finishSuccess` 自动加入 `completedIds`，同一 ID 再次入队仍被跳过。

**指数退避不让坏源拖垮队列。** 失败后 `nextRetryAt = now + min(initialBackoff ×
2^(attempts-1), maxBackoff)`，退避期内 `startNext` 不返回该项，给其他章节让路。
重试次数耗尽后标记 FAILED，不再重试；用户可手动 `retry` 重置。

**并发上限与搜索共用限流。** `startNext` 在 `downloadingCount >= maxConcurrent` 时
返回 null，调用方据此停止取新任务。T40 要求「下载与搜索共用 core/net/ 的限流」——
生产环境应把 `maxConcurrent` 与 `GlobalConcurrencyGate` 协调，不要另开一套并发池
把搜索请求挤掉。

**`startNext` / `finishSuccess` / `finishFailure` 是调用方的下载循环骨架。**
协调器不碰 HTTP、不碰文件 IO、不碰前台服务——生产环境的下载循环是：
`while (!queue.isAllDone()) { item = queue.startNext(); if (item == null) wait; 
result = download(item); if (success) queue.finishSuccess(id, bytes) else queue.finishFailure(id, err) }`。

**本轮没有顺带做的事**：下载执行者（`DownloadExecutor` 接线到 `MediaDownloadService`）、
断点续传的 `.part` 文件管理（已有但未接入队列）、空间占用统计与清理入口的 UI、
前台服务与通知栏接线——均依赖 Android SDK。

## 合规门禁

```bash
bash tools/ci.sh
```

七道门禁：权限白名单、免责声明、无遥测出口、架构契约、Gradle Wrapper 配置、
Room schema 导出配置、UI 分层（`ui/` 下不得直连 DAO / Room）。

其中新增的 `tools/check_ui_layering.py` 是为了守住 T10 的一条硬约束：
**UI 层不许直接碰 DAO**。它扫描 `app/src/main/java/com/lanyue/reader/ui/`
下所有 Kotlin 文件，出现 `@Dao`、`RoomDatabase`、`androidx.room.` 或
`*Dao(` 调用即判错。当前 `ui/` 下 1 个文件（`PlaceholderShell.kt`），
只依赖 `main/` 的壳模型，不碰数据库，因此通过。这条门禁的意义不在当下
——而在后续 T11 书源管理页真的开始写数据访问时，一旦有人图快在
Composable 里直接注入 DAO，CI 会立刻拦住。

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
| `tests/VideoDanmakuLayerTest.kt` | 影视站点安装幂等 + 弹幕轨道调度（126 项） |
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
| `tests/SourceValidatorTest.kt` | 源配置校验与能力判定（书源/订阅/影视三族 validate 分级、URL/JSON 体检、JS 括号重心校验、Report 汇总文案）（69 项） |
| `tests/CapabilityResolverTest.kt` | 源配置文件级能力判定（能力位 miss/降级/订阅兜底/影视双开启映射，entity 数据形状直连接口契约）（19 项） |
| `tests/TvBoxParserTest.kt` | TVBox 配置解析（接口配置展开多站点、type3 drpy/jar 区分与裁剪标记、parses 解析链、lives 持久化字段、hotSearch 裁剪、非法配置 Invalid、可选字段 null 不炸）（63 项） |
| `tests/SortUrlParserTest.kt` | 1.4.4 sortUrl 动态生成与静态分类解析（名::url 换行/&& 双分隔、trim/空行过滤、@js: 与 <js> IIFE 包装且 traceId/NORMAL 池透传、JS 失败与异常降级、100 分类截断与边界恰好等值）（32 项） |
| `tests/TextPurifierTest.kt` | 替换净化：非法正则/空匹配串容错、字面量与正则分流、作用域与 order、首行模式、空白归一、比例回退、规则自检（58 项） |
| `tests/PurifyRuleCodecTest.kt` | replaceRule 编解码：isRegex 缺省为字面量、scope 未知值提示与退回、坏 JSON/缺字段容错、数字与字符串形态、编解码往返、源级覆盖全局的合并语义（74 项） |
| `tests/PurifyPipelineTest.kt` | 净化管线：全局+源级合成与覆盖、按源缓存与精确失效、坏 replaceRule 透传、正文/标题分流、端到端 JSON→管线→净化、调试判定在净化后进行（46 项） |
| `tests/TtsReadTest.kt` | 朗读：规范化等长性、逐句切分与偏移回对原文、引号内句号不切、右引号并入前段、超长优先软断点、段合并、调度状态机（停止后迟到回调忽略、暂停续读、读完重听）、跳段边界、按偏移起读、语速音调夹取（108 项） |
| `tests/DanmakuSourceLayerTest.kt` | 弹幕源配置层：apiBase 规范化（补协议/去尾斜杠/端口/localhost/IP 与全部拒绝路径）、探活失败可强制保存、未启用不探活、顺序规范化与并列稳定、新增/删除/启停/移动、端到端校验→入库→启用顺序联动（86 项） |
| `tests/AppShellTest.kt` | 应用外壳：日志下限随构建类型切换（debug 放行 VERBOSE / release 仅 WARN 及以上、等值边界放行）、惰性图表（首访不构造 / 只构造一次 / 失败不缓存可重试）、崩溃兜底（UTC 时间戳与文件名拼接、线程名净化与 24 字符截断、cause 链编号与环状截断、无 message 不写 null、保留份数按时间戳删旧、目录缺失自动创建、异常转交原处理器且无前驱时不二次崩溃）（63 项） |
| `tests/WrapperConfigTest.kt` | 构建入口：properties 解析（反斜杠转义还原、`#`/`!` 注释、空格裁剪、缺项默认值）、版本解析（精确三段式、rc/SNAPSHOT/latest 均判未锁定、先剥 `-bin`/`-all` 再取版本）、校验规则（http 拒绝、大版本不匹配、次版本低于下限、sha256 缺失/过短/非十六进制、大写允许、关闭 validate 拒绝、8.7 恰好等于下限放行）、仓库真实文件一致性（版本 8.9、sha 与官方一致、无问题清单、`gradlew` 可执行位、jar 为 zip 头）、与 AGP 8.5.2 主版本对齐（54 项） |
| `tests/SchemaExportTest.kt` | Room schema 导出配置：`@Database` 注解解析（exportSchema 显式 true / 缺省 false / 显式 false、version 与实体数、跨行与尾随逗号）、`ksp { arg("room.schemaLocation") }` 解析（参数名大小写敏感、`$projectDir` 与 `${projectDir}` 两种写法、绝对路径判否）、目录名判定（`schemas` vs `schema`/`roomSchemas`、尾随斜杠）、配置组合校验（缺 exportSchema / 缺 ksp 参数 / 产物未提交 / version 与文件名错配 / 实体数为 0）、schema JSON 骨架（format 必须为 1、version 与注解一致、表数量、identityHash 小写 32 位、重复表名）（33 项） |
| `tests/MainShellTest.kt` | 启动壳：标题恢复校验（null/空串/纯空白/含换行制表符/超长一律拒绝，恰好 32 字符放行）、版本标签（`v` 前缀不重复、空值与字面 null 回落 `v?`）、占位模型组装（说明空白归一空串、应用名缺失兜底、合法恢复值优先于资源值、非法恢复值回退）、占位壳判据、清单与实现一致性（包路径 ui、继承 ComponentActivity、setContent、无业务界面符号、委托 ShellSpec、两个 service 仍仅声明）（49 项） |
| `tests/BuildScriptTest.kt` | 构建配置对齐：plugins 块解析（版本/apply false/容忍空格/不识别别名写法）、Compose 插件一致性（开启 compose 必应用插件、应用必在根脚本声明版本、版本须与 Kotlin 精确一致含补丁号、未开启时不强制）、AndroidX 开关（依赖存在必为 true、显式 false 亦报错、无依赖不强制）、BuildConfig 开关（引用必须开启）、仓库真实配置校验（模块已应用 Compose 插件、根脚本有版本、buildConfig 已开、namespace 正确）、清单四组件与实现一一对应（49 项） |
| `tests/ShellNavTest.kt` | 应用骨架与导航：三目的地（书架/书城/我的）顺序与索引、平级切换不叠栈（同页重复点击不变、切换后返回栈恒定为一层）、返回键行为（首个目的地拦截退出、非首个回书架）、深链落地（任意目的地直达且栈规范化）、主题三态（跟随系统/强制浅色/强制深色）与系统深浅色解析（未知值回落跟随系统、强制模式屏蔽系统变化）、状态容器四态（空/加载/成功/失败）与失败重试、非法输入判否（42 项） |
| `tests/SourceManageTest.kt` | 书源管理页：导入入口把文件与粘贴两条来源收成一体，失败分类成空/过大/编码/语法/缺字段/未识别六档，其中语法错与字段缺失必须给出不同提示（前者指向换文件、后者指向改书源），并带可操作性标记；编码判定先认 BOM 再用严格解码试 UTF-8、GBK，非法字节不得被静默替换成问号；超限在编码判定之前拦截，避免把「导多了」误报成「编码错」；列表侧覆盖分组归并（空归未分组）、类型/状态标签、关键词与分组与状态三条件筛选、四种排序（默认按 sortOrder 保导入不打乱既有排列）、行模型、分组下拉未分组置顶、空态区分「一个都没有」与「筛没了」、启停切换的无变化返回原列表与未知地址静默不动、批量启停、计数汇总（107 项） |
| `tests/SearchSessionTest.kt` | 搜索编排与多源聚合：渐进产出（每回一个源即可出快照，快的源不等慢的）、单源预算隔离（超时即踢出等待队列，页面不因一个源卡到死）、整页预算兜底（到点剩余源一律收尾，杜绝「永远差一个源」的转圈）、迟到结果不采信也不并列表（避免列表在用户指尖下重排）、重复回调不覆盖既有结局、失败隔离（已失败源不被后续超时改写成超时，保留最先原因）、空结果为正常结局而非失败、全体无内容判致命并给出错误页文案、单源条数上限与截断计数、跨源聚类归并（同书多源合并出换源表，同名不同作者不合并）、代次控制（换关键词后旧搜索的迟到结果按代次拒绝，防「搜 B 看到 A 的结果」）、边界（恰在预算点仍可采信、超 1ms 即丢弃）（93 项） |
| `tests/DetailTocTest.kt` | 详情页与目录：简介按字符数折叠（恰在上限不截断、超一字符即截断，按字符而非按行——手机宽度随字号变，按行在模型层算不准）、作者/标题缺失兜底、0 章显示「章数未知」而非「共 0 章」、元信息空项剔除、加入书架三前置（唯一标识/书名/未在书架）与重复加入静默不写、移出与切换、阅读按钮「开始/继续」判据（有进度且非开头才算继续）；目录侧覆盖**倒序只翻显示顺序而章号保留原始下标**（点倒序首行必须跳到末章而非首章，已读标记查原始下标）、关键词过滤后下标不变、标题越界兜底、概览已读百分比与越界已读不计入、上下章边界夹紧且原地不动时返回原实例、已读标记复用 `ReadingProgress.markChapterRead`、空态与「筛没了」两种提示（128 项） |
| `tests/ReadingSessionTest.kt` | 阅读会话：位置持久化用字符偏移而非页号（编码字段固定顺序、二次编解码幂等）、反序列化逐字段防御（空串/垃圾串回初始、章号越界夹到末章、偏移超过本章夹到章尾、负值归 0、页号超界夹到末页、页数 0 兜底 1、已读下标过滤）、**重排版保位置**（字号 16→28 后 locator 不变、页数随之增加、页号必须指向包含该偏移的页）、分页完整性（拼接等于原文、无截断无重叠、页偏移单调不回退、页码连续）、冷启动恢复（存档页号在新字号下被重算覆盖、空存档回开头）、进度条跳转（0/10/50/100% 落点、负值与超界夹紧、跳转重置页号但不清已读、空书不崩、单章书书内定位）、章内百分比跳转、页码 1 基与百分比文案、重排版后再翻页仍走同一位置真值、全字号档位（12..34）分页自洽且页数单调不减（74 项） |
| `tests/ContentStageTest.kt` | 正文获取与净化串联：水印/站点后缀去除、正则去标签、全局+源级规则合成、段落拆分（空行滤除、全角空格归一、行尾空白去除）、**按章结果缓存**（二次打开 fromCache=true、同源不同章各自缓存、命中后段落/统计完全一致）、缓存失效（replaceRule 变更后重跑、全局规则变更后指纹不命中、invalidate/clear）、FIFO 淘汰、边界（空内容/null 规则/纯空白）、stats 透明性、**全链路 prepare→repaginate**（排版总字数==净化后字数、页偏移落在净化文本内）、**净化必须在排版前的证明**（原文排版含水印字数 160、净化排版不含水印 100）、缓存命中后分页一致、多段正文端到端（73 项） |
| `tests/TtsChapterCoordinatorTest.kt` | 朗读章节续读协调器：基本装载与续读（auto-continue ENABLED 默认）、续读关闭（读完停 FINISHED）、全书读完（BOOK_DONE）、**空章自动跳过**（startChapter 跳过空/空白章）、续读时遇到空章自动跳到下一非空章、**点正文起读**（startFromPosition with charOffset 定位到正确段）、**高亮区间数据**（highlightRange 映射段偏移、回切原文验证、推进后高亮移动、续读后重映射新章）、**迟到回调安全**（停止后 IGNORED 不触发续读、暂停后 IGNORED）、多章连续续读（3 章逐章读完）、hasNextChapter（含跳过尾部空章）、空书/单章书/越界/负索引、续读从头开始（charOffset 不跨章）、续读时后面全空返回 BOOK_DONE、play/pause/stop/skip 透传、进度章号与协调器一致、长章多段续读后章号正确（97 项） |
| `tests/DownloadQueueTest.kt` | 下载队列协调器：批量入队（跳过已下载 preloadCompleted、跳过重复）、startNext（标记 DOWNLOADING、并发上限 maxConcurrent、退避到期才返回、多候选取 nextRetryAt 最早、空队列返回 null）、finishSuccess（标记完成 + 加入 completedIds）、finishFailure（安排重试 + **指数退避计算** 2^n、退避上限 maxBackoff 夹取、重试耗尽标记 FAILED）、cancel/cancelAll、retry（手动重试失败项、非失败项拒绝）、isAllDone（含失败项也算完成）、snapshot summary 文案（空队列/进行中/全完成/部分失败）、clearHistory（清除已完成+已取消、保留失败项）、边界（越界 ID 不崩、maxRetries=0 首次失败即 FAILED）、config 参数校验、**端到端批量下载全流程**（5 章 × 混合成功/失败/重试、循环有界不死锁、完成数/失败数/总字节校验）（104 项） |

合计 39 套 / 2848 项断言，全部通过（FAIL = 0）。上表逐行数字与
`tools/test_jvm.sh` 的实际输出一一对应，可用一条命令复现。

完整运行方式见 `tools/test_jvm.sh`。

## 构建

需要 Android Studio / AGP。本仓库为架构骨架，不含完整 UI 与资源，无法直接出包。

## 说明

本项目不内置、不分发任何书籍或影视内容，也不内置任何源规则；
仅提供阅读与解析引擎能力，内容与源的获取、合规性由使用者自行负责。
