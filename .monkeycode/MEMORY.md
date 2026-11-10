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

[Project Knowledge: 纯 JVM 测试验证流程]
- Date: 2026-11-07
- Context: 蓝阅（lanyue）阅读器源码迭代中，发现本环境无 Android SDK，Room / Android 类无法编译后自建的验证流程
- Category: Testing Methods
- Instructions:
  - 验证用 `bash tools/test_jvm.sh`：kotlinc 2.0.20 以 `-include-runtime` 编译 `app/src/main/java` 中除 EXCLUDES 清单外的全部 .kt + `tests/*.kt` + Log 桩，gson / jsoup / kotlinx-coroutines 内联进 jar，再逐个运行 `XxxTestKt` 主类
  - kotlinc 与依赖 jar 位于 `/tmp/opencode`（kotlinc-2.0.20、coroutines-core.jar、jsoup.jar、gson.jar、okhttp.jar、okio.jar、conscrypt.jar、out/），脚本支持 `KC` / `CP_DIR` / `OUT` 环境变量覆盖
  - 新增带硬 Android 依赖（Room / Keystore / Canvas / 播放器等）的源码文件时，需把路径追加到 `tools/test_jvm.sh` 的 EXCLUDES，否则编译失败
  - 测试均为纯 JVM `main()` 入口（无 JUnit），新增测试类也要在脚本末尾的类名清单中登记
   - kotlinc 把 `tests/*.kt` 视为同一模块编译进同一 jar，顶层类名即使 `private` 也不能跨文件重复（如 `FakeJs`）；无 package 声明的测试文件必须显式 import 被引用的源码类型（如 `com.lanyue.reader.core.engine.SnifferSession`）
   - r21 起各测试私有类按模块加前缀命名：DandanHttp / DmCookies（弹幕源）、FeedHttp / FeedCookies / FeedJs（订阅源），避免 Dandan 与订阅源跨文件顶层私有类同名冲突
   - 小 JSON 响应体（< 1KB 且不含 `<`）会被 `NetworkDispatcher` 视为空 HTML 触发降级链；需要这个样式的响应体时注入 `,"x":"<1"` 即可绕开误判
   - 下载第三方 jar 后的第一次编译可能因缺失 Transitive 依赖失败；已确认 coroutines / jsoup / gson / okhttp / okio / conscrypt 六件套足够
   - 适配 fetchJson 类请求时注意 HTML-like 判定：URL 无后缀（如 `http://a/s?ac=list`）会被 `HtmlRequestClassifier.isHtmlLike` 判为 HTML 类；响应体 < 1KB 且不含 `<` 时，`NetworkDispatcher` 视其为空 HTML 触发三级降级链二次请求（OkHttp 重发）。测试需补第二份相同响应（如 `pushHtmlLike(body)` 双 push）或让响应含 `<` 或超过 1KB，否则 fake channel 会抛 `no queued response`
    - 测试 raw string 中写含 `$` 分隔符的 JSON 样例（如 `vod_play_url` 以 `$` 分线路）时，不能写 `\$`（旧编译器仍当模板插值报错），需定义 `val D = "$"` 后用 `${D}` 插值

[Project Knowledge: Android APK 打包流程]
- Date: 2026-11-08
- Context: 推进真机/真包目标，从零搭建 Android 打包环境并打通 assembleDebug
- Category: Build Methods / Environment Configuration / Operations & Deployment
- Instructions:
  - 环境：JDK 17；Android SDK 装在 `/opt/android-sdk`（cmdline-tools latest + `platforms;android-34` + `build-tools;34.0.0` + `platform-tools`，licenses 已签）；Gradle 8.10.2 装在 `/opt/gradle-8.10.2`（本地无 gradle 时直接用它）
  - 仓库根 `local.properties` 写 `sdk.dir=/opt/android-sdk`（不要提交该文件）
  - 构建命令：`/opt/gradle-8.10.2/bin/gradle assembleDebug`，在工作区根目录执行；产物在 `app/build/outputs/apk/debug/app-debug.apk`
  - 长编译类命令（assembleDebug 首次 4~5 分钟）必须走 background_terminal，`memory_percent` 给 30~50% 即可，`timeout` 给 1800000 以上
  - 校验：`aapt2 dump badging <apk>` 看包名/版本/权限；`apkanalyzer manifest print <apk>` 看合并后 manifest；`apksigner verify --print-certs <apk>` 验签名
  - 网络可达 `dl.google.com` / `repo.maven.apache.org` / `services.gradle.org`，无需代理即可拉 AGP/androidx/media3 依赖

[Project Knowledge: AGP 8.5.2 + Kotlin 2.0 打 Android 包的坑]
- Date: 2026-11-08
- Context: 打包联调中逐个排除的编译错误
- Category: Troubleshooting & Debugging
- Instructions:
  - Kotlin 2.0 起 Compose 编译必须用 `org.jetbrains.kotlin.plugin.compose` 插件（版本与 Kotlin 一致，如 2.0.20），只写 `composeOptions.kotlinCompilerExtensionVersion` 会报 Compose compiler 找不到
  - 编译报「unresolved reference LifecycleService / START_NOT_STICKY 等」时：androidx.lifecycle 2.8.x 把 LifecycleService 拆到了独立 artifact `androidx.lifecycle:lifecycle-service`，需单独加 implementation，否则继承它的类（含 media3 的 ServiceLifecycleOwner/MediaSessionService）全挂
  - `org.conscrypt.*` 不在 android.jar 里：Android 平台 TLS provider 本就是系统内置 Conscrypt，业务侧不需要显式 newProvider，删掉 `Security.insertProviderAt(Conscrypt.newProvider(), 1)` 和对应 import 即可回落平台默认（Legado 系原版做法）
  - `android.nonTransitiveRClass=true`（AGP 8 默认）下，非 namespace 子包引用 R 必须显式 `import com.lanyue.reader.R`，否则报错 unresolved reference 'R'
  - Room TypeConverter/备份编解码使用 Gson 时，要在 app 模块声明 `com.google.code.gson:gson`，否则 ksp 阶段报 Converter 无此类
  - 顶层聚合搜索、换源、导入等纯逻辑仍按 `tools/test_jvm.sh` 的纯 JVM 路线验证，Android 打包不来影响这套流程
   - UI 层（Composable）的坑：`ActivityResultContracts.GetContent()` 构造是无参的，mime 类型在 `launcher.launch("*/*")` 里传；`mutableStateListOf` 的返回值不能按 `by remember { mutableStateListOf<T>() }` 委托（无 getValue 重载，编译报 "Property delegate must have a 'getValue' method"），用普通 `val` 持有即可；`TextButton`/`ElevatedButton` 等 Material3 组件各有独立 import，需逐个补

[Project Knowledge: 音频书源 §6.1 UI 落地细节]
- Date: 2026-11-09
- Context: 在 ReaderScreen / ArticleScreen 接入 AudioPlayerScreen，复用 PlayerManager 进程内播放音频直链
- Category: Troubleshooting & Debugging
- Instructions:
  - Material Icons 陷阱：build.gradle 只声明 `androidx.compose.material3:material3`，未含 `material-icons-core`。要写播放/暂停图标按钮时 `Icons.Filled.PlayArrow` / `Pause` 编译直接报 unresolved reference 'Icons'，避免加 `material-icons-core` 依赖（一种方式是用 `OutlinedButton+Text("播放")` 替代 `IconButton+Icon`，零新依赖到位）
  - PlayerManager API：`ensurePlayer()` / `loadAudio(url, headers)` / `pause()` / `resume()` / `seekTo(Long)` / `release()` / `player: ExoPlayer?`（property）。`player` 在 `ensurePlayer` 前为 null，调用要判空；`seekTo` 不 suspend；`Media3 1.4.1` 的 Player.Listener 没有 `onPositionChanged()`，500ms 轮询 `player.currentPosition/duration/isPlaying` 驱动 UI
  - AudioPlayerScreen 调用方约定：ReaderScreen/ArticleScreen 保留上一章/下一章按钮，切换章节触发 LaunchedEffect(newUrl) 重新 loadAudio（内部会先 release 旧实例）；AudioPlayerScreen 上不用触发 DanmakuMatchEngine（音频流无画面承载弹幕叠层）
  - Compose DSL 不允许 `return` 早退：在 `@Composable` 函数体里用 `if (cond) { AudioPlayerScreen(...) } else { Column(...原状...) }` 包裹，不能 `return`
  - 编辑多 Composable 函数的同一文件时，先用 `grep -n "^@Composable\|^internal fun\|^fun "` 看函数边界，再决定 newString 锚点，避免 edit 修 ReaderScreen 时把 ArticleScreen 或 BookSearchScreen 撞坏

[Project Knowledge: §4.9 MediaSession 接 ExoPlayer 的编译与生命周期要点]
- Date: 2026-11-09
- Context: AudioPlaybackService 从 LifecycleService 升级到 MediaSessionService，接 ExoPlayer 锁屏控件
- Category: Troubleshooting & Debugging / Build Methods
- Instructions:
  - ServiceCompat 没有 `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` 常量——常量在 `android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`（javap 校验）；`ServiceCompat.startForeground(Service, int id, Notification, int type)` 存在但常量要引用 ServiceInfo
  - `Notification.MediaStyle.setShowActionsInCompactView(...)` 参数是三个 `int` 图标资源 ID（如 `android.R.drawable.ic_media_previous` / `ic_media_play` / `ic_media_next`），不是 PendingIntent——传 PendingIntent 会编译报"Argument type mismatch: actual type is 'PendingIntent!', but 'Int' was expected"
  - `session?.setPlayer(playerManager.player)` 会因为 `ExoPlayer?` nullable 报"Argument type mismatch: actual 'ExoPlayer?', expected 'Player'"——用 `playerManager.player?.let { session?.setPlayer(it) }`
  - `MediaSession.Builder(context, player)` 构造时必须给非空 Player——所以要么先 `ensurePlayer()` 再调 `MediaSession.Builder(this, playerManager.ensurePlayer())`，要么延迟到 onStartCommand ACTION_PLAY loadChapter 时再建
  - `MediaSessionService.onGetSession(controllerInfo)` 每次 controller 连接都会回调，必须返回同一个 session 实例（lazy 单例 `session ?: build().also { session = it }`），不能每次构造新 MediaSession 否则会不断创建 session 句柄泄漏
  - MediaSessionService 会自动 add 返回的 session——不需要显式调 `MediaSessionService.addSession` 或 `removeSession`
  - MediaSession 里 GetScreen play/pause/stop 由系统自动透传到 player（player 需通过 `setPlayer` 绑定）；翻章这种 MediaSession 没有内建命令的逻辑走通知栏 action 或 Player.setMediaItems 队列

## 说明

[Project Knowledge: Compose 1.6.8 combinedClickable 长按重载排查]
- Date: 2026-11-09
- Context: 源管理中心 SourceCenterScreen 给行加长按多选，写 `combinedClickable(..., onLongPress=...)` 报 "None of the following candidates is applicable"，javap 清点 foundation-android 1.6.8 bytecode 后确认签名
- Category: Troubleshooting & Debugging
- Instructions:
  - foundation AAR 本地路径：`/root/.gradle/caches/modules-2/files-2.1/androidx.compose.foundation/foundation-android/1.6.8/e89eb7*/foundation-release.aar`，`unzip classes.jar` 后 `javap androidx.compose.foundation.ClickableKt` 可查到所有 clickable 重载的真实签名
  - Compose 1.6.8 combinedClickable 字节码：`(Modifier, MutableInteractionSource, Interaction?, Boolean enabled, String onClickLabel, Role?, String?, () -> Unit, () -> Unit, () -> Unit)`——`Interaction?` 可传 null，但 3 个 lambda 的具体命名需逐版核对，盲目传 `onLongPress` 命名参数会编译不过
  - 稳妥做法：只用 `combinedClickable(interactionSource = ..., indication = null, onClick = ...)` 单 lambda 重载（编译通过），多选等交互用界面上的显式按钮切换，不依赖长按
  - `combinedClickable` 属于 `androidx.compose.foundation`，标注 `@ExperimentalFoundationApi`，调用处需 `@OptIn`

[Project Knowledge: Compose 1.6.8 手势与动画 API 实测签名]
- Date: 2026-11-09
- Context: RootShell §2.5.1 底栏「下滑降透 / 沉浸隐藏」实现中核对 foundation 1.6.8 真实 API
- Category: Troubleshooting & Debugging
- Instructions:
  - 1.6.8 没有 `detectDragGesturesWithInitial`；只用 `androidx.compose.foundation.gestures.detectVerticalDragGestures(onDragStart, onVerticalDrag, onDragEnd, onDragCancel)`——拖动位移参数名是 `onVerticalDrag`，`drag` 是累计 Y（Float），不是逐帧 delta，必须用 `onDragStart` 里记的 `barOffsetPx` 基准做加法
  - `AnimateKt`/`TransitionKt` 里 `animateFloat` 只有 `@Composable` 成员形态（InfiniteTransition/Transition 容器版本）；独立的 suspend `animateFloat` 不存在，动画只能走 `AnimationState.animateTo` 扩展
  - 1.6.8 `AnimationState.value` 的 setter 是 internal（`setValue$animation_core_release`），Compose 外无法直接驱动状态数值；需要跟手更新的指针拖拽场景改用 `remember { mutableStateOf(0f) }` + `by` 委托最省事
  - 底部导航栏隐藏动画做法（RootShell.kt）：NavigationBar 外层 `.offset { IntOffset(0, barOffsetPx.roundToInt()) }` + `.alpha(1f - 位移/栏高)`，`detectVerticalDragGestures` 挂 NavigationBar 自身（避免与内层 LazyColumn 抢手势），过半吸附目标 64.dp



[Project Knowledge: release R8 打包流程与内存配额坑]
- Date: 2026-11-10
- Context: app/build.gradle.kts 默认 release 已开 isMinifyEnabled=true；assembleRelease 触发 R8 压缩
- Category: Build Methods
- Instructions:
  - release 构建：`background_terminal_create` 跑 `/opt/gradle-8.10.2/bin/gradle :app:assembleRelease --no-daemon --console=plain`，约 4-5 分钟
  - Gradle daemon 默认 -Xmx4g；R8 峰值会撑到 3GB+。cgroup memory_percent=40（≈3.11GiB）时 OOM killed，daemon 直接消失报 "Gradle build daemon disappeared unexpectedly"；memory_percent=60（≈4.67GiB）才稳定通过
  - release 无 signingConfig，产物为 `app-release-unsigned.apk`，用调试 keystore 签名：`apksigner sign --ks /root/.android/debug.keystore --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android`，verify v2/v3 OK；APK 3.5MB（R8 收缩）
