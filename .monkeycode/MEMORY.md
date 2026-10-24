# User Instruction Memory

This file records user instructions, preferences, and teachings for reference in future interactions.

## Format

### User Instruction Entry
User instruction entries should follow this format:

[User Instruction Summary]
- Date: [YYYY-MM-DD]
- Context: [Mentioned scenario or time]
- Instructions:
  - [Content of user teaching or instruction, described line by line]

### Project Knowledge Entry
Entries discovered by the Agent during task execution should follow this format:

[Project Knowledge Summary]
- Date: [YYYY-MM-DD]
- Context: Discovered by Agent while performing [specific task description]
- Category: [Operations & Deployment|Build Methods|Testing Methods|Troubleshooting & Debugging|Workflow & Collaboration|Environment Configuration]
- Instructions:
  - [Specific knowledge points, described line by line]

## Deduplication Strategy
- Before adding a new entry, check for similar or identical instructions.
- If a duplicate is found, skip the new entry or merge it with the existing one.
- When merging, update the context or date information.
- This helps avoid redundant entries and keeps the memory file tidy.

## Entries

[Git 回退禁令与工作区恢复方法]
- Date: 2026-10-20
- Context: D13 批次中误用 `git checkout -- <file>` 回退 ExoPlayerHelper.kt，导致未提交的 1534 行工作区版本被回退为 HEAD 的 162 行版本
- Category: Troubleshooting & Debugging
- Instructions:
  - 本仓库所有批次（D1-D13+）的移植工作均处于未提交状态（大量 M 文件），禁止对任何文件执行 `git checkout --` / `git restore`，一律用编辑工具做定向回退
  - ws 的 ExoPlayerHelper.kt（1533 行 Sigma 视频引擎版）源自 fork m 的同名文件，二者逐锚点一致（imports 40-66、lazy databaseProvider 1313/1376）；ws 版本=m 版本+1 行尾部差异
  - 工作区文件意外丢失时，先核对 `/tmp/opencode/forks/{arc,m,max,ng}` 同路径文件，fork 源即可作为恢复底本
  - git fsck 无 dangling blob（文件从未 add 过），stash 为空，均非可用恢复渠道

[Kotlin 大块移植切片方法]
- Date: 2026-10-20
- Context: D13 向 ExoPlayerHelper 追加 max 的 475 行媒体缓存成员块
- Category: Build Methods
- Instructions:
  - 按锚点行切片时，边界必须「从下一声明的 fun 行向上回溯：跳过空行，若遇 `*/` 则继续回溯到配对 `/**`」，否则会把下一函数的 KDoc 切成两半（KDoc 计数不匹配即可检出）
  - 切完必须断言：`/**` 与 `*/` 数量相等、`{` 与 `}` 数量相等、不含同名重复声明（如 getMediaUrls/MediaRequest 在 m 已有）
  - 不同签名的同名函数是合法重载（如 max 的 isMediaUrlCached(cache,url,cacheDir) 3 参与 m 的 isMediaUrlCached(url) 1 参），引用方按实参个数解析，两者需同时存在
  - 审计脚本对接收者扩展声明（`fun <T> Receiver.sym`、`fun <VB> Fragment.viewBinding`）需用 `fun\s+[\w<>\s,]*\.sym` 宽松匹配；R/databinding/BuildConfig/代码生成类直接白名单，否则全量误报
- import 闭包审计对「成员导入」（object/伴生对象/嵌套类型/`const val`，如 `AppConst.UA_NAME`、`CookieManager.mergeCookies`、`AnalyzeUrl.Companion.getMediaItem`）会因「按 import 路径找文件」失败而误报：先按「去掉最后一段后的 .kt 文件存在」判定成员导入，rx 备选里补 `const val`；更可靠的兜底是直接对被引用包目录 grep 符号声明
- 字符串存在性检查必须扫全部 res（如 `http_ip` 在 non_translat.xml，WebSigma 的 WebService 在用），只扫 values/strings.xml 会误报
  - 删除枚举/注册表条目时逐条按行前缀删（每条目单行成对）；禁用「进入 skip 后等 endswith 复位」的状态机——SPEAK_ENGINE( 前缀同时命中进入分支并 continue，复位永不执行，曾吞掉 BaseContentSearchDialog 600 行尾部
  - 每次脚本改写文件后立刻断言花括号/KDoc 平衡 + 文件行数与预期一致，并在关键锚点（createDialog/SourceFieldItem 等）抽查存在性

[assembleDebug 全量编译通过与缺失视图类占位策略]
- Date: 2026-10-23
- Context: 第 6 节验证基线 assembleDebug 构建，历经 dbg6-dbg14 共 9 轮迭代修复
- Category: Build Methods
- Instructions:
  - 构建基线：AGP 8.13.2、Kotlin 2.3.10、Gradle 8.14.4、compileSdk/targetSdk 36、minSdk 23；Room 2.7.1；依赖 media3 1.10.1
  - 编译必须用后台终端（cgroup 限制 memory_percent 70 / cpu_percent 200 / timeout 30min），日志 tee 到 /tmp/opencode/assemble_dbgNN.log
  - 错误规模演进：dbg6 884 → dbg7 712 → dbg8 0 Kotlin错（资源链接失败）→ dbg10 273 → dbg11 240 → dbg12 130 → dbg13 27 Java错（缺视图类）→ dbg14 BUILD SUCCESSFUL
  - Kotlin 编译耗时约 5-10min，Java 编译+打包约 1-2min；总构建约 11min
  - 并行 agent 修复策略：按文件簇分组，每个 agent 独立搜索 fork 对照修复，只添加缺失成员不覆盖
  - 缺失视图类占位策略：布局引用但无 Kotlin 代码直接访问的自定义视图，创建简单 FrameLayout/TextView 子类桩实现即可通过编译（BatteryViewOrgin、EpubReadView、ReadAiFloatingPanel、ReadAiSummaryPanel、ReadAloudPlayerPanel）
  - StableLiquidGlassView 依赖 com.qmdeve.liquidglass 库（非项目依赖），替换为无依赖的 FrameLayout 桩实现
  - exo_icon_* drawable 来自 media3-ui 1.10.1 AAR（media3-exoplayer 不包含）；video_* 和 unlock drawable 在所有 fork 和 git 历史中均不存在，创建占位版本
  - Room 迁移链合并为 90→96 单步（ws 的 91-94 中间 schema 不可考）
  - AppConfig.kt 重建策略：取 HEAD 833 行提交版为基座 + fork 联合收割的 71 缺失成员
  - gradle.properties 内存收敛：-Xmx4g + in-process kotlin 编译，单堆 4GB 避免 OOM
  - assembleRelease 验证：R8 混淆通过（proguard-rules.pro 已覆盖 Room/Cronet/media3/View 构造器等 keep 规则），总耗时约 13min
  - lintVitalAnalyzeAppRelease 超时 30min 被 cgroup kill，跳过方式：`./gradlew :app:assembleRelease -x lintVitalAnalyzeAppRelease -x lintVitalAnalyzeRelease`
  - Release APK 产物：universal 30MB（R8 混淆后）、arm64-v8a 22MB、armeabi-v7a 22MB
