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
