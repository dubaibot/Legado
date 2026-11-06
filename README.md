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
│   ├── rule/          规则层（RuleSyntax 语法 / RuleChain 编排 / RuleEngine 执行）
│   └── video/         TVBox 兼容与本地解析代理
├── data/
│   ├── db/            Room 数据库 / 实体 / DAO（书源、订阅、影视、弹幕）
│   └── backup/        备份 DTO / JSON 编解码 / 导入合并策略
└── tools/             合规与架构门禁脚本
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
| `tests/RuleEngineDispatchTest.kt` | 规则拆解与执行派发 |
| `tests/JsonXpathTest.kt` | JSONPath / XPathLite 求值 |
| `tests/SourceLayerTest.kt` | 源健康度隔离 + 三类源配置校验（85 项） |
| `tests/ReadingLayerTest.kt` | 阅读设置往返 + 翻页状态机 + 分页链路（126 项） |
| `tests/BackupLayerTest.kt` | 备份编解码容错 + 合并策略（89 项） |

## 构建

需要 Android Studio / AGP。本仓库为架构骨架，不含完整 UI 与资源，无法直接出包。

## 说明

本项目不内置、不分发任何书籍或影视内容，也不内置任何源规则；
仅提供阅读与解析引擎能力，内容与源的获取、合规性由使用者自行负责。
