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

[构建配方与引擎构建约束（assembleDebug/release/worktree 合并）]
- Date: 2026-10-29（合并 2026-10-23 与 2026-10-26 条目）
- Context: 多轮构建验证、test 分支 worktree 构建、JS 引擎换件构建
- Category: Build Methods
- Instructions:
  - 构建基线：AGP 8.13.2、Kotlin 2.3.10、Gradle 8.14.4、compileSdk/targetSdk 36；依赖 media3 1.10.1、Room 2.7.1
  - 编译必须用后台终端（cgroup memory_percent 70 / cpu_percent 200 / timeout 30min），日志 tee 到 /tmp/opencode/assemble_dbgNN.log
  - Kotlin 编译约 5-10min，Java+打包约 1-2min，assembleDebug 全量约 10-11min；assembleRelease（含 R8+lintVital）约 23min
  - `-x lintVitalAnalyzeAppRelease -x lintVitalAnalyzeRelease` 不跳过 `lintVitalAppRelease`（不同任务名）
  - soundtouch-jni 需 proguard keep：`-keep class com.tianscar.soundtouch.** { *; }`
  - 缺失视图类占位策略：布局引用但无代码直接访问的自定义视图，创建 FrameLayout/TextView 子类桩即可通过编译（BatteryViewOrgin、EpubReadView、ReadAiFloatingPanel、ReadAiSummaryPanel、ReadAloudPlayerPanel）；StableLiquidGlassView 用无依赖 FrameLayout 桩替代
  - exo_icon_* drawable 来自 media3-ui 1.10.1 AAR；video_* 和 unlock drawable 在所有 fork 和 git 历史中均不存在，创建占位版本
  - Room 迁移链合并为 90→96 单步（ws 的 91-94 中间 schema 不可考）
  - gradle.properties 内存收敛：-Xmx4g + in-process kotlin 编译，单堆 4GB 避免 OOM
  - 编译错误修复策略：按文件簇分组并行修复，只添加缺失成员不覆盖
  - Release APK：universal 31.9MB / arm64-v8a 23.3MB / armeabi-v7a 22.5MB
  - worktree 构建：`git worktree add --detach <dir> origin/test`，worktree 需补 local.properties（sdk.dir）；test 分支 gradle.properties 改 -Xmx4g + in-process + workers.max=2 防 R8 OOM
  - applicationId 改 com.myreaderD.reader 需为 google-services.json 增 com.myreaderD.reader.release 客户端条目
  - JS 引擎换件约束：engine 用 org.htmlunit:htmlunit-core-js:5.3.0，其 SlotMapOwner 使用 MethodHandle.invokeExact（min-api 26），app 与 modules/rhino 的 minSdk 必须保持 26（回退 23 会在 mergeExtDexAppDebug 报 D8 错）
  - APK 下载预览：复制产物到 /tmp/opencode，由后台终端 `python3 -m http.server 8902` 对外提供
  - 构建日志经 `| tail` 缓冲时文件为 0 字节，必须等命令结束再读

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

[分支与 MR 工作流]
- Date: 2026-10-24（MR 推送知识 2026-10-29 补充）
- Context: 分支合并复核时发现 origin 上有 8 个从未 fetch 的旧分支（git branch -r 不可见），经 ls-remote 才暴露
- Category: Workflow & Collaboration
- Instructions:
  - `git branch -r` 只显示本地已 fetch 的远程跟踪分支，全面盘点远程分支必须 `git ls-remote --heads origin`
  - 被后续重做取代的旧分支链用 `git merge -s ours <tip>` 收编：历史统一（52 提交经第二父可达）、工作树零变化、删除安全；真合并会复活 209 个双线修改冲突与被取代的旧实现
  - 收编前先验拓扑（merge-base --is-ancestor 两两比对找链式超集末端），只需合并超集分支一个即可覆盖全链
  - 删除远程分支后跑 `git fetch --prune origin` 清理本地跟踪引用
  - MR 创建：remote 是 GitHub，`git push -o merge_request.create` 会 HTTP 500，gh CLI 未认证；MR 需用户手动从 GitHub 创建

[Release APK 签名配置]
- Date: 2026-10-25
- Context: 用户安装 release APK 报「安装包无效」，根因为 RELEASE_STORE_FILE 属性缺失导致签名配置被跳过，产出未签名 APK
- Category: Build Methods
- Instructions:
  - release 签名仅在 gradle 存在 RELEASE_STORE_FILE 属性时生效（app/build.gradle signingConfigs 条件块），属性缺失时产出未签名 APK，安装必报「安装包无效」
  - 签名属性写在 ~/.gradle/gradle.properties（不进仓库）：RELEASE_STORE_FILE/RELEASE_STORE_PASSWORD/RELEASE_KEY_ALIAS/RELEASE_KEY_PASSWORD
  - 用户级（GRADLE_USER_HOME）gradle.properties 优先级高于项目级：在项目 gradle.properties 里重复写 RELEASE_* 不会生效，构建始终用 ~/.gradle 那份（曾误配项目级 legado.jks 被静默覆盖，APK 仍用 myreader 密钥签名）
  - 密钥库 /workspace/keystore/myreader-release.jks（alias myreader，密码在上述属性文件），已 gitignore /keystore/
  - 签名验证命令：/opt/android-sdk/build-tools/36.0.0/apksigner verify --print-certs <apk>
  - 换签名密钥后旧版本必须先卸载才能装新包（签名冲突）

[环境无预装 JDK/Android SDK 时的构建前置]
- Date: 2026-10-27
- Context: 在 feat/debug-tracker 分支用 assembleDebug 重新生成 APK（含图标改动）
- Category: Environment Configuration
- Instructions:
  - 环境默认无 java/sdkmanager，需先装 JDK 17（apt openjdk-17-jdk-headless，位于 /usr/lib/jvm/java-17-openjdk-amd64）与 Android cmdline-tools（装到 /opt/android-sdk，sdkmanager 装 platforms;android-36、build-tools;35.0.0/36.0.0、platform-tools），并写 local.properties `sdk.dir=/opt/android-sdk`
  - debug 构建走 ~/.android/debug.keystore（Android Debug 签名，包名 com.myreaderD.reader.debug），与 release 的 myreader 密钥不同
  - Maven Central 偶发 TLS 中断（Remote host terminated the handshake），重试即可，已下载依赖缓存于 ~/.gradle/caches

[移植 M3 分支 UI 到 AppCompat 主题项目的崩溃与修复模式]
- Date: 2026-10-29
- Context: Legado_T 自动任务页移植后崩溃：MaterialCardView 构造时 ThemeEnforcement 校验 isMaterialTheme 失败（本项目 Base.AppTheme parent 仍是 Theme.AppCompat.DayNight.NoActionBar，而 Legado_T 已整体升级 Theme.Material3）
- Category: Troubleshooting & Debugging
- Instructions:
  - 强校验控件（在 AppCompat 主题下必崩）：MaterialCardView、MaterialButton；宽松控件（可正常运行）：FloatingActionButton、TabLayout、TextInputLayout（DEF 样式走 Design 家族）、MaterialSwitch
  - 修复模式：values/styles.xml 定义 `ThemeOverlay.App.MaterialWidget`（parent=Theme.Material3.DayNight + colorPrimary/colorAccent 覆盖为 app 色板），在每个 Material 控件标签上加 `android:theme="@style/ThemeOverlay.App.MaterialWidget"`，只作用单棵子树不动全局主题
  - android:theme 会传播到子视图（LayoutInflater 子节点用 parent.getContext()），布局级修复对全部消费方生效
  - 从 Legado_T 再移植布局时，凡见 MaterialCardView/MaterialButton 必须挂此 overlay；项目已修：item_manage.xml、dialog_recycler_view_adaptive.xml、dialog_ai_purify_confirm.xml
  - 全量排查方法：`rg -ln "com.google.android.material.(card|button)" app/src/main/res/layout*`，layout-land 等变体目录也要扫

[edit 工具模糊匹配风险]
- Date: 2026-10-29
- Context: 编辑 RssParserByRule.kt 导入区时 oldString 把 coroutineScope 误写成 coroutineContext，编辑仍然"成功"，实际破坏了 import
- Category: Troubleshooting & Debugging
- Instructions:
  - edit 工具对 oldString 存在模糊匹配，oldString 与文件有差异时可能静默按近似内容替换
  - 对 import 区等精细位置的编辑，编辑后必须立即回读验证关键行；出现"oldString 不完全一致却编辑成功"的情况优先怀疑模糊匹配
