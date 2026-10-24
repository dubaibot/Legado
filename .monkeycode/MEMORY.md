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
  - assembleRelease 验证（D35）：R8 混淆通过，总耗时 23m 20s（Kotlin 编译约 8min + R8 约 2min + lintVital 约 10min + 打包约 3min）
  - `-x lintVitalAnalyzeAppRelease -x lintVitalAnalyzeRelease` 不会跳过 `lintVitalAppRelease`（不同任务名），但该任务正常完成无需额外跳过
  - soundtouch-jni（D30 TTS 引擎依赖）需要 proguard keep 规则：`-keep class com.tianscar.soundtouch.** { *; }`（ng fork 有此规则）
  - Release APK 产物：universal 31.9MB（R8 混淆后）、arm64-v8a 23.3MB、armeabi-v7a 22.5MB

[死代码审计方法教训]
- Date: 2026-10-24
- Context: 分支全面检测中误判 data/entities/readRecord/ReadRecord.kt 为死代码（grep head -5 截断漏掉 BackupFileValidator.kt:17 的 import），删除后 compileAppDebugKotlin 失败，恢复后通过
- Category: Troubleshooting & Debugging
- Instructions:
  - 引用完整性审计禁止对 grep 结果用 head/N 截断采样——截断处之外的真实引用会漏检，必须全量输出
  - 死代码判定需四重验证：限定 import 全量 grep + 同包未限定引用 grep（含子目录共享包声明）+ 通配 import `pkg.*` 检查 + 编译兜底
  - 同名文件在不同包是合法的（style1/style2 书架适配器等 26 对同名文件均为正常），判定死代码看引用看包名，看文件名
  - Launcher1-7 等类可定义在同名外文件中（WelcomeActivity.kt 内含 7 个 Launcher 顶级类），按类名全树 grep 而非按文件名找
  - Kotlin 词法扫描需正确处理 raw string 的 `""""` 四引号结尾（末尾 3 引号是终止符，第 4 个是内容），否则误报花括号不平衡

[分支盘点与收编方法]
- Date: 2026-10-24
- Context: 分支合并复核时发现 origin 上有 8 个从未 fetch 的旧分支（git branch -r 不可见），经 ls-remote 才暴露
- Category: Workflow & Collaboration
- Instructions:
  - `git branch -r` 只显示本地已 fetch 的远程跟踪分支，全面盘点远程分支必须 `git ls-remote --heads origin`
  - 被后续重做取代的旧分支链用 `git merge -s ours <tip>` 收编：历史统一（52 提交经第二父可达）、工作树零变化、删除安全；真合并会复活 209 个双线修改冲突与被取代的旧实现
  - 收编前先验拓扑（merge-base --is-ancestor 两两比对找链式超集末端），只需合并超集分支一个即可覆盖全链
  - 删除远程分支后跑 `git fetch --prune origin` 清理本地跟踪引用
