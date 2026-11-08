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
