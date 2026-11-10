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

**当前阶段**：逻辑层完备（75 个 Kotlin 源文件 / 12,762 行），UI 层尚未开始。
`./gradlew assembleDebug` 现在会失败——缺 `LanyueApp`、缺 Gradle Wrapper、
缺 Activity。这不是损坏，是路线上的下一站，见 TASK-BACKLOG 的 M1。

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

完整运行方式见 `tools/test_jvm.sh`。

## 构建

需要 Android Studio / AGP。本仓库为架构骨架，不含完整 UI 与资源，无法直接出包。

## 说明

本项目不内置、不分发任何书籍或影视内容，也不内置任何源规则；
仅提供阅读与解析引擎能力，内容与源的获取、合规性由使用者自行负责。
