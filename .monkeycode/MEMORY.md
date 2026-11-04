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

[死代码审计、edit 工具模糊匹配与 Git 回退禁令]
- Date: 2026-11-04（合并 2026-10-20/24/29 条目）
- Context: D13 批次误用 git checkout 回退文件；分支全面检测中误判死代码；编辑 RssParserByRule.kt 导入区时 oldString 误写仍"成功"
- Category: Troubleshooting & Debugging
- Instructions:
  - 本仓库所有批次移植工作均处于未提交状态，禁止对任何文件执行 `git checkout --` / `git restore`，一律用编辑工具做定向回退；ws 文件意外丢失时先核对 `/tmp/opencode/forks/{arc,m,max,ng}` 同路径文件作为恢复底本
  - 引用完整性审计禁止对 grep 结果用 head/N 截断采样——截断处之外的真实引用会漏检，必须全量输出
  - 死代码判定需四重验证：限定 import 全量 grep + 同包未限定引用 grep + 通配 import `pkg.*` 检查 + 编译兜底
  - edit 工具对 oldString 存在模糊匹配，oldString 与文件有差异时可能静默按近似内容替换；对 import 区等精细位置的编辑，编辑后必须立即回读验证关键行

[Kotlin 大块移植切片方法]
- Date: 2026-10-20
- Context: D13 向 ExoPlayerHelper 追加 max 的 475 行媒体缓存成员块
- Category: Build Methods
- Instructions:
  - 按锚点行切片时，边界必须「从下一声明的 fun 行向上回溯：跳过空行，若遇 `*/` 则继续回溯到配对 `/**`」，否则会把下一函数的 KDoc 切成两半
  - 切完必须断言：`/**` 与 `*/` 数量相等、`{` 与 `}` 数量相等、不含同名重复声明
  - 不同签名的同名函数是合法重载，引用方按实参个数解析，两者需同时存在
  - import 闭包审计对「成员导入」会因「按 import 路径找文件」失败而误报：先按「去掉最后一段后的 .kt 文件存在」判定成员导入；更可靠的兜底是直接对被引用包目录 grep 符号声明
  - 字符串存在性检查必须扫全部 res（如 `http_ip` 在 non_translat.xml），只扫 values/strings.xml 会误报
  - 删除枚举/注册表条目时逐条按行前缀删（每条目单行成对）；禁用「进入 skip 后等 endswith 复位」的状态机——SPEAK_ENGINE( 前缀同时命中进入分支并 continue，复位永不执行
  - 每次脚本改写文件后立刻断言花括号/KDoc 平衡 + 文件行数与预期一致

[构建配方与引擎构建约束（assembleDebug/release/worktree 合并）]
- Date: 2026-10-29（合并 2026-10-23 与 2026-10-26 条目）
- Context: 多轮构建验证、test 分支 worktree 构建、JS 引擎换件构建
- Category: Build Methods
- Instructions:
  - 构建基线：AGP 8.13.2、Kotlin 2.3.10、Gradle 8.14.4、compileSdk/targetSdk 36；依赖 media3 1.10.1、Room 2.7.1、Gson 2.13.2
  - 编译必须用后台终端（cgroup memory_percent 70 / cpu_percent 200 / timeout 30min），日志 tee 到 /tmp/opencode/build_*.log
  - Kotlin 编译约 5-10min，Java+打包约 1-2min，assembleDebug 全量约 10-11min；assembleRelease（含 R8+lintVital）约 21-24min
  - `-x lintVitalAnalyzeAppRelease -x lintVitalAnalyzeRelease` 不跳过 `lintVitalAppRelease`（不同任务名）
  - soundtouch-jni 需 proguard keep：`-keep class com.tianscar.soundtouch.** { *; }`
  - GSON 反射反序列化的 Kotlin data class 必须加 `@Keep`，否则 R8 剥离构造函数导致运行时崩溃
  - 缺失视图类占位策略：布局引用但无代码直接访问的自定义视图，创建 FrameLayout/TextView 子类桩即可通过编译
  - gradle.properties 内存收敛：-Xmx4g + in-process kotlin 编译，单堆 4GB 避免 OOM
  - Release APK：universal 31.9MB / arm64-v8a 23.3MB / armeabi-v7a 22.5MB
  - worktree 构建：`git worktree add --detach <dir> origin/test`，worktree 需补 local.properties（sdk.dir）
  - applicationId 改 com.myreaderD.reader 需为 google-services.json 增 com.myreaderD.reader.release 客户端条目
  - Chaquopy 约束：build.gradle defaultConfig 内 `pip { install("requests") }` 预装 requests；py 插件接口约定：插件模块须定义 search/detail/play，返回 JSON 字符串；HomeBridge 15s 超时
  - Chaquopy PyObject 无 hasAttr/getAttr 方法，检查属性存在只能用 try-catch 调 callAttr 或 Python 侧 getattr；Python None 经 callAttr 返回后 toString() 为 "None" 字符串，需手动判空
  - Chaquopy Java 方法只接受位置参数：Python 关键字参数会按顺序映射到 Java 方法的位置参数；解决方式：base/spider.py 改为 Python 包装类，正确映射关键字参数到 Java 位置参数
  - Chaquopy isinstance(cls, type) 对 Java 类不成立返回 False；修复：直接 `try: spider = spider_cls() except: return None` 不依赖 isinstance 检查
  - Gson Kotlin API：asJsonObject/asString/asJsonArray/asJsonPrimitive 是属性访问（无括号），不是方法调用；`el.asJsonObject` 正确，`el.asJsonObject()` 报 "receiver type mismatch" 编译错误；entrySet()/keySet()/size() 是方法调用（有括号）；getAsJsonObject()/getAsString() 也是方法调用（有括号）
  - CMS API URL 模式（对照 github.com/huajideshutiao/legado TvBoxCmsSpider.kt 实测）：home 用 `?ac=list`（返回 class+list）、category 用 `?ac=videolist&t=&pg=`、search 用 `?ac=videolist&wd=&pg=`（带 ac=videolist，不带 quick）、detail 用 `?ac=detail&ids=`、play 返回直链+防盗链头(UA+Referer)；quick 是 FongMi/TV 约定不是 MacCMS V10 参数
  - header 字段支持三种格式：JSON 对象 `{"k":"v"}`、JSON 字符串 `"{\"k\":\"v\"}"`、`"k=v&k2=v2"` 纯字符串；ext 字段支持字符串/JSON 对象/数组
  - Release 签名：signingConfigs 仅在 gradle 存在 RELEASE_STORE_FILE 属性时生效；属性写在 ~/.gradle/gradle.properties；密钥库 /workspace/keystore/myreader-release.jks（alias myreader）
  - VideoPlayerActivity 单 URL 播放：传入 videoUrl + videoTitle extra 即设 VideoPlay.singleUrl=true 直接播放
  - Glide Compose 集成可用：GlideImage + ContentScale.Crop + @OptIn(ExperimentalGlideComposeApi::class)
  - APK 下载预览：复制产物到 /tmp/opencode，由后台终端 `python3 -m http.server 8901` 对外提供

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

[环境无预装 JDK/Android SDK 时的构建前置]
- Date: 2026-10-27
- Context: 在 feat/debug-tracker 分支用 assembleDebug 重新生成 APK（含图标改动）
- Category: Environment Configuration
- Instructions:
  - 环境默认无 java/sdkmanager，需先装 JDK 17（apt openjdk-17-jdk-headless，位于 /usr/lib/jvm/java-17-openjdk-amd64）与 Android cmdline-tools（装到 /opt/android-sdk，sdkmanager 装 platforms;android-36、build-tools;35.0.0/36.0.0、platform-tools），并写 local.properties `sdk.dir=/opt/android-sdk`
  - debug 构建走 ~/.android/debug.keystore（Android Debug 签名，包名 com.myreaderD.reader.debug），与 release 的 myreader 密钥不同
  - Maven Central 偶发 TLS 中断（Remote host terminated the handshake），重试即可，已下载依赖缓存于 ~/.gradle/caches
  - Gradle 构建可能产生 java_pid*.hprof 崩溃堆转储（OOM 时自动生成，单文件可达 3.7GB），构建前检查 `df -h` 磁盘空间，发现 .hprof 用 `truncate -s 0` 清空而非删除
  - `./gradlew clean` 仅释放 build 目录（~1.5GB），Gradle 缓存（~9.6GB）不可随意清理否则下次构建全量重下载；磁盘空间不足时优先处理 .hprof 和旧 APK 产物

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

[RedirectCacheInterceptor 仅缓存 GET 请求的重定向映射]
- Date: 2026-10-29
- Context: 排查定时任务发帖「显示3次成功实际1帖」事故，定位到 RedirectCacheInterceptor 对 POST 也缓存 302 跳转
- Category: Troubleshooting & Debugging
- Instructions:
  - POST/PUT/DELETE 的 302 跳转目标与请求体强相关（论坛发帖→本次新帖页面），跨请求复用映射会把后续 POST 改写到上次跳转目标：定时任务连发 3 帖只真发 1 帖，其余被改写到首帖 viewthread 页，脚本拿到含成功标记的页面误判成功
  - 修复形态：intercept() 开头 `if (request.method != "GET") return chain.proceed(request)`，非 GET 不查也不写缓存；已配回归测试 app/src/test RedirectCacheInterceptorTest
  - 该拦截器是阅读D 特有修改（Legado_T 无），且只缓存 GET；排查网络类 bug 时优先对比 fork 差异而非引擎层
  - 缓存容器用 java.util.LinkedHashMap(accessOrder) 而非 android.util.LruCache，纯 JVM 单测可测
   - 单测注意：AppLog.putDebug 依赖 Android 框架（DebugLogScope=SupervisorJob+Dispatchers.IO），纯 JVM 单测避开走 putDebug 的路径

[底栏渲染链路与顶栏高度系统]
- Date: 2026-10-30
- Context: 用户反馈「默认悬浮底栏看起来是常规底栏样式」+「顶栏能不能改窄」
- Category: Troubleshooting & Debugging
- Instructions:
  - 底栏渲染链路：MainActivity.applyBottomNavigationShell() → NavigationBarConfig.activeConfig() → loadConfigs() → 内置 createDefaultDay/Night()，AppConfig.bottomBarLayoutMode 仅 MainLayoutPresetConfig.apply() 写入但渲染代码从不读取，是死偏好
  - 底栏默认样式由 NavigationBarConfig.createDefaultDay/Night() 硬编码决定（原 LAYOUT_STANDARD+EFFECT_SOLID+opacity 30），已改为 LAYOUT_FLOATING+EFFECT_GLASS+opacity 76 匹配 AppConfig 默认偏好
  - 效果模式：EFFECT_GLASS（液态玻璃，需 StableLiquidGlassView 采样）/ EFFECT_FROSTED（磨砂）/ EFFECT_SOLID（纯色）；布局模式：LAYOUT_FLOATING（圆角 24dp）/ LAYOUT_STANDARD（无圆角）/ LAYOUT_SIDEBAR
  - 顶栏高度系统：View 侧 TitleBar(AppBarLayout→Toolbar) 默认 minHeight 56dp（Widget.AppCompat.Toolbar→?attr/actionBarSize）；Compose 侧 HomepageScreen Row height(56.dp)、AppDimens.topBarHeight=56.dp
  - 顶栏收窄：Toolbar XML 加 android:minHeight="48dp"（view_title_bar.xml + view_title_bar_dark.xml）；HomepageScreen.kt height(56.dp)→height(48.dp)；Dimensions.kt topBarHeight 56.dp→48.dp；字体图标不变（ToolbarTitle 20sp 保留）
  - GlassTopAppBar.kt 有 barHeight: Dp? 参数（null 时默认 64.dp），管理族传入 48dp 档启用自绘紧凑布局；MainTopBarView 和 AppPageTopBar 无硬编码高度

[影视源 VideoHttpClient 独立 OkHttpClient 策略]
- Date: 2026-11-04
- Context: 用户反馈 JSON CMS 源"调用源失败"，薯条版 shuliao 同一接口正常工作
- Category: Troubleshooting & Debugging
- Instructions:
  - VideoHttpClient 不再继承 okHttpClient（HttpHelper.kt 的全局客户端），改用独立 OkHttpClient
  - 原因：okHttpClient 的拦截器链（DecompressInterceptor 发送 Accept-Encoding: gzip、RedirectCacheInterceptor、FaviconCache、Cronet、StreamResetRetryInterceptor 等）可能干扰 CMS API 响应
  - 薯条版 catvod.OkHttp 的关键配置：followRedirects(false)、不发 Accept-Encoding、系统 DNS（OkDns 直接用 Dns.SYSTEM.lookup）、30s 超时、信任所有证书
  - doGet 不再检查 resp.isSuccessful（对齐薯条版 string() 行为，非 2xx 也返回 body）
  - JSON 源接口失败时抛出异常含 URL 和响应内容（替代原先静默返回空数据导致 UI 只显示"该源暂无数据"）
