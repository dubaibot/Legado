# APK 开发任务清单

> 配套文档：[`APK-DEVELOPMENT-PLAN.md`](APK-DEVELOPMENT-PLAN.md)（路线与判断）、
> [`AI-HANDOFF.md`](AI-HANDOFF.md)（环境重建与纪律）。
>
> **状态标记**：`[ ]` 未开始 · `[~]` 进行中 · `[x]` 已完成 · `[!]` 阻塞
> **每条任务都给了「验收」，接手时按验收判断是否真做完，不要凭感觉勾选。**

---

## 里程碑 M1 — 构建打通（阻塞一切，最高优先）

### T01 `[x]` 补 `LanyueApp` 应用类
- **现状**：`app/src/main/AndroidManifest.xml:18` 声明 `android:name=".LanyueApp"`，
  源码中**不存在**该类。这是当前唯一的硬编译阻塞。
- **要做**：创建 `app/src/main/java/com/lanyue/reader/LanyueApp.kt`，
  继承 `Application`，职责限定为：
  1. 持有 `LanyueDatabase` 单例（懒初始化，别在 `onCreate` 里做重活）；
  2. 初始化日志开关（release 关 debug 日志）；
  3. 安装崩溃兜底（捕获未捕获异常，落本地文件，**不上报**）。
- **约束**：不要在 `Application` 里做网络请求、书源预加载、弹幕探活。
  这些会拉长冷启动，且首帧前崩溃难以定位。
- **验收**：`LanyueApp` 编译通过；清单 `android:name` 指向的类真实存在；
  启动 APK 后 `adb logcat` 无 `ClassNotFoundException`。

### T02 `[x]` 补 Gradle Wrapper
- **现状**：有 `build.gradle.kts` / `settings.gradle.kts` / `app/build.gradle.kts`，
  缺 `gradlew` 与 `gradle/wrapper/gradle-wrapper.properties`。
- **要做**：生成 wrapper 并**锁定版本号**（与 AGP 匹配，AGP 8.x 对应 Gradle 8.x）。
  `gradle-wrapper.properties` 里用 `-bin` 或 `-all` 均可，但版本必须写死，
  不要用 `latest`。
- **验收**：干净目录下 `./gradlew --version` 成功；`gradlew` 有可执行位。

### T03 `[x]` 补最小 `MainActivity`
- **现状**：清单第 26 行声明了 activity，无对应实现。
- **要做**：Compose 空壳 `MainActivity`，只做 `setContent { }` 渲染一个
  「蓝阅」占位页，先把启动链路打通。**不要在这一步写业务 UI**。
- **验收**：APK 安装启动后能看到占位页，切后台再回来不重建崩溃。

### T04 `[~]` 首次真实构建并修全部编译错
- **要做**：`./gradlew assembleDebug`，逐条修错。预期问题集中在：
  - Room KSP：实体注解、DAO 返回类型、`@Transaction` 用法；
  - Compose 编译器插件：Kotlin 版本与 compose compiler 版本必须对齐；
  - 依赖冲突：`jsoup`（见下）、`okhttp` 与 media3-datasource-okhttp 的版本；
  - `kotlinx-serialization` 插件是否已在 `build.gradle.kts` 声明。
- **⚠️ jsoup 版本陷阱**：`app/build.gradle.kts` 用 `1.17.2`，
  而 `tools/test_jvm.sh` 用 `1.13.1`。**这是刻意的**——1.17.2 的可空注解会让
  JVM 测试代码大面积编译失败。不要为了「版本统一」把测试脚本升上去。
- **验收**：`assembleDebug` 成功；`tools/ci.sh` 全绿；`tools/test_jvm.sh`
  仍是 21 套 / 1,434 项 / FAIL=0。

> **状态说明（2026-11-11）**：`[~]` 而非 `[x]`。配置侧已完成并经静态核验——
> compose 编译器插件 `2.0.20` 已在根脚本 `apply false`、在 `app` 模块应用，
> 与 Kotlin 版本对齐；`@Serializable` 全仓 0 处引用，故无需序列化插件。
> 但 **`assembleDebug` 本身从未真正跑通**：执行环境没有 Android SDK，
> 构建无从启动。这条验收线仍是空的，等有 SDK 的环境回填。

### T05 `[~]` 开启 Room schema 导出
- **要做**：给 `@Database` 加 `exportSchema = true`，
  配置 `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`，
  提交生成的 schema JSON。
- **为什么必须现在做**：等加了字段再想起来，就没有基线 schema 可对比，
  迁移测试无从写起，只能靠 `fallbackToDestructiveMigration()` 掩盖
  ——那意味着用户升级会丢数据。
- **验收**：`app/schemas/` 下有 JSON 并已提交；构建无 Room 警告。

> **状态说明（2026-11-11）**：`[~]`。前两项已完成——`@Database(exportSchema = true)`
> 原本就有，本轮补上 `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`，
> 并新增 `tools/check_schema.py` 接入 `tools/ci.sh`，让"三处缺一即静默失效"
> 变成门禁可拦的错误。
>
> **但 `app/schemas/1.json` 尚未生成**。它的 `identityHash` 是 Room 对表结构的
> 内部哈希，无法手推，只能由 Gradle+KSP 在构建期算出；kapt 与 KSP cmdline
> 两条离线通路都试过，均无法加载处理器（详见 README 第二十九轮）。
> **没有手写 JSON 冒充产物**——那样 `identityHash` 必然与真实结构不符，
> 门禁会变绿，但真正做迁移时立刻失效，比没有基线更危险。
>
> 回填方式：在有 Android SDK 的环境执行
> `./gradlew :app:kspDebugKotlin`，提交生成的 `app/schemas/1.json`。
> 此后 `check_schema.py` 会自动开始校验它与 `@Database.version` 是否一致。

---

## 里程碑 M2 — 阅读主链路（MVP）

### T10 `[~]` 应用骨架与导航
- **要做**：
  1. 底部导航三页：书架 / 书城 / 我的；
  2. 主题系统接 `core/render/Theme`，支持跟随系统深浅色；
  3. 全局状态容器：`ViewModel` + `Repository`。**UI 层不许直接碰 DAO**，
     否则后续换缓存策略要改遍所有页面。
- **验收**：三个页面可切换，深浅色切换生效，返回栈行为正确。

> **状态说明（2026-11-11）**：`[~]`。规则层已落地并全部可脱机验证，
> 但 Compose 界面本身尚未接入——三个页面仍是 `ui/PlaceholderShell.kt`
> 的占位壳。
>
> 已完成：
> - `core/shell/AppDestination.kt`：三目的地枚举（书架/书城/我的）与顺序、索引；
> - `core/shell/BottomNavPolicy.kt`：底部导航是**平级切换**而非叠栈——重复点击
>   同一页不产生新栈帧，切换后返回栈恒为一层；返回键在非首个目的地回书架、
>   在书架拦截以交还系统退出；支持深链直达任意目的地且栈规范化；
> - `core/shell/ThemeMode.kt`：跟随系统/强制浅色/强制深色三态，系统值未知时
>   回落跟随系统，强制模式下屏蔽系统深浅色变化；
> - `core/shell/ScreenState.kt`：空/加载/成功/失败四态容器，失败可重试；
> - `tests/ShellNavTest.kt`：42 项断言覆盖上述规则；
> - `tools/check_ui_layering.py`：新增第七道门禁，扫 `ui/` 下是否出现
>   `@Dao` / `RoomDatabase` / `androidx.room.` / `*Dao(`，把「UI 不碰 DAO」
>   从口头约定变成 CI 可拦的错误。
>
> 缺口：`app/` 的 Compose 界面（NavHost 接线、`collectAsState` 订阅、
> 真实三页内容）尚未写；沙箱无 Android SDK，无法验证渲染与真机返回栈。
> 也就是说本轮交付的是**导航/主题/状态的纯逻辑内核 + 分层护栏**，
> 界面接线留待有 SDK 的环境或在 T11 一并接入。

### T11 `[~]` 书源管理页
- **要用**：`source/import/`（Legado JSON 解析）、`source/repo/`（仓库）、
  `data/db/`（书源 DAO）、`source/debug/`（离线调试）。
- **要做**：导入（文件选择 + 粘贴文本两条路径）、源列表、启用停用、
  单源进入离线调试、错误提示（解析失败要说清是语法错还是字段缺失）。
- **验收**：能导入一份真实 Legado 书源 JSON；导入后列表可见；
  点调试能看到搜索→详情→目录→正文的逐阶段结果。

> **状态说明（2026-11-11）**：`[~]`。页面所需的规则层已完成并通过
> 33 套 / 2279 项回归，但 Compose 界面与文件选择器尚未接入。
>
> 已完成：
> - `source/manage/SourceIntake.kt`：把「文件」与「粘贴」两条来源收成同一入口，
>   失败分为空/过大/编码/语法/缺字段/未识别六档。**这是 T11 验收里最容易被糊弄
>   的一句「说清是语法错还是字段缺失」**——`SourceImportParser` 原本只回一个
>   `format` 字符串，界面上拿不到「该换文件还是该改书源」的判断依据，这里补齐并
>   用 `actionable` 标出来。编码判定先认 BOM，再用**严格模式**试 UTF-8、GBK：
>   默认解码器遇非法字节会静默替换成 U+FFFD，那样 GBK 文本会被误认为「UTF-8 但
>   全是问号」而蒙混过关。超限在编码判定之前拦，避免把「一次导太多」误报成
>   「编码错」。
> - `source/manage/SourceListModel.kt`：列表查询管线（关键词 × 分组 × 状态三条件、
>   四种排序、未分组归并、分组下拉未分组置顶、空态区分「一个都没有」与「筛没了」）、
>   行模型、启停切换（无变化或未知地址时返回原列表，避免无谓重组）。
>   刻意不持仓库、不落库——落库仍归 `SourceRepository`，符合 T10 立下的
>   「UI 不碰 DAO」分层护栏。
> - `tests/SourceManageTest.kt`：107 项断言覆盖上述规则。
>
> 缺口：Compose 页面（导入按钮、文件选择器、列表项、分组下拉、启停开关、
> 调试入口）尚未写；`data/db/` 的书源 DAO 读取路径未接（`SourceRepository`
> 目前接受内存列表构造）；沙箱无 Android SDK，无法验证真机文件选择与渲染。

### T12 `[ ]` 搜索页与多源聚合
- **要用**：`source/aggregate/`（去重、书名归并、空作者回填）、
  `core/engine/`（并发与限流）。
- **要做**：关键词搜索、按来源分组或归并展示、加载态与单源失败隔离
  （一个源超时不应让整页转圈到死）。
- **验收**：搜索出结果；某源故意配错时其余源仍正常返回；
  同一本书多源命中时能归并成一条并可切换来源。

### T13 `[ ]` 详情页与目录
- **要做**：书籍信息、加入书架、目录列表（支持倒序）、
  已读章节标记、跳转到阅读页。
- **验收**：从搜索结果进入详情，目录能加载，点击章节进入阅读并定位正确。

### T14 `[ ]` 阅读页排版与绘制（最大一块）
- **要用**：`core/render/` 的 `Paginator`、`PageTurnController`、
  `ReadingSettings`、`ReadingProgress`、`TypographySpec`。
- **关键约束**：**分页必须在 `Paginator` 算完再交给 Compose 绘制**。
  每次重排都重新测量整章会明显卡顿。Compose 只画已算好的页。
- **要做**：分页绘制、左右/上下翻页、点击翻页、字号行距边距调节、
  背景主题、进度条与跳转、阅读位置持久化与恢复。
- **验收**：一本真实书能从头读到尾；翻页无文字截断/重叠；
  调字号后重新分页且位置不丢；退出重进回到原位置。

### T15 `[ ]` 正文获取与净化串联
- **要用**：`source/rule/`（取正文）、`source/purify/`（替换净化 + 管线）。
- **顺序要求**：**先净化、后排版**。反过来会让字数变化导致分页错位，
  表现为「明明没动设置，页码却变了」。
- **要做**：正文获取 → 净化 → 交排版；缓存净化结果避免重复计算。
- **验收**：带水印/站点后缀的章节，正文干净；
  同一章二次打开不重复净化（可看日志耗时）。

---

## 里程碑 M3 — 朗读接线

### T20 `[ ]` `TextToSpeech` 适配层
- **要用**：`core/tts/TtsReadController`（已实现状态机与分段）+
  `TtsSegmenter`。
- **要做**：
  1. `TextToSpeech` 初始化与语言可用性检测（不可用时给明确提示，
     不要静默失败）；
  2. 把 `speak(segment)` 接到 `tts.speak(text, QUEUE_ADD, params, utteranceId)`；
  3. `UtteranceProgressListener.onDone` → `controller.onSegmentFinished()`；
  4. `stopSpeaking()` 接 `tts.stop()`。
- **⚠️ 必须守住**：`onDone` 回调可能迟到。`TtsReadController` 已经只在
  `SPEAKING` 态接受回调，**不要**为了「响应更快」在适配层自己推进光标，
  那会破坏这层保护，出现「停了又自动念下一段」。
- **验收**：点朗读能出声；暂停后恢复从当前段继续；
  停止后不会自己又念起来。

### T21 `[ ]` 朗读控制条与设置
- **要做**：播放/暂停/上一段/下一段、语速音调滑块、朗读入口
  （底栏或长按菜单）。语速音调直接用 `controller.rate` / `.pitch`，
  越界由它夹取，UI 不必重复校验。
- **验收**：控制条各按钮行为正确；语速调整立即生效。

### T22 `[ ]` 跟随高亮与章节续读
- **要用**：`TtsProgress.charOffset`（分段偏移已保留，可直接回对正文）。
- **要做**：朗读时正文高亮当前段；点正文任意位置从该处起读
  （`controller.load(..., startCharOffset)`）；章节读完自动续读
  做成设置项（默认行为要明确，不要两头都做一半）。
- **验收**：高亮与语音同步无明显漂移；点正文中段能从那句开始读。

---

## 里程碑 M4 — 弹幕渲染

### T30 `[ ]` 弹幕渲染视图
- **要用**：`danmaku/render/DanmakuRenderParams`（参数）、
  `DanmakuFilter`（屏蔽策略）。
- **技术选择**：优先 `SurfaceView` / `Canvas` 直绘。同屏几十个元素持续移动，
  Compose 做这个会有明显额外开销。
- **要做**：滚动/顶部/底部三种轨道、按时间轴出弹幕、离屏回收、
  同屏密度控制（复用参数里的上限）。
- **验收**：播放时弹幕正常飘过；长时间播放不卡顿、内存不持续增长。

### T31 `[ ]` 弹幕设置面板
- **要做**：透明度、字号、速度、显示区域、屏蔽词、屏蔽类型开关。
  屏蔽策略已由 `DanmakuFilter` 实现，UI 只负责传参。
- **验收**：各项调整实时生效，不做成「改完要重进页面」。

### T32 `[ ]` 弹幕源配置页
- **要用**：`danmaku/DanmakuSourceValidator`（三态校验）+
  `danmaku/DanmakuSourceRegistry`（排序/启停/增删）。
- **要做**：
  1. 新增源表单：只要求填 `apiBase`；校验失败时**允许强制保存**
     并显示「未验证」（这是 R23 的刻意设计，不要改成拒绝保存）；
  2. 列表支持上移下移、启用停用；关闭最后一个启用源时给出提示
     （registry 会拒绝，UI 要把原因说清而不是静默无反应）；
  3. 显示「已验证 / 未验证」状态。
- **验收**：能新增一个源并保存；地址写错时可强制保存但标未验证；
  排序调整后重启仍保持。

---

## 里程碑 M5 — 离线与备份

### T40 `[ ]` 下载队列与章节落盘
- **要做**：批量下载、并发上限、失败重试与退避、断点续传、
  已下载标记、空间占用统计与清理入口。
- **约束**：下载与搜索共用 `core/net/` 的限流，不要另开一套并发池
  ——否则批量下载会把搜索请求全挤掉。
- **验收**：断网能读已下载章节；下载中断后重进能续传。

### T41 `[ ]` 备份与恢复
- **要用**：`data/backup/`（DTO、JSON 编解码、合并策略）。
- **要做**：导出/导入、版本迁移、冲突处理（同名书保留哪份要有明确规则）、
  导入失败要能回滚而不是留下半套数据。
- **验收**：导出后在另一台设备导入，书架与阅读进度完整恢复。

### T42 `[ ]` WebDAV 同步（可选，视需求优先级）
- **要做**：接自托管场景，支持配置服务器与自动同步。
- **验收**：手动触发同步成功；冲突时按既定策略处理。

---

## 里程碑 M6 — 交付打磨

### T50 `[ ]` 签名与构建变体
- **要做**：debug / release 变体分离；签名密钥走环境变量或
  `local.properties`，并确认被 `.gitignore` 覆盖。
- **⚠️ 红线**：**任何密钥都不许提交进仓库**。历史轮次里出现过把 token
  直接写进提交的情形，务必避免。
- **验收**：`assembleRelease` 产出签名包；`git ls-files` 中无密钥文件。

### T51 `[ ]` R8 / ProGuard 规则
- **要做**：保留 Room 实体、`kotlinx.serialization` 序列化类、
  JS 引擎反射入口（`core/engine/` 通过反射调用的类名必须 keep）。
- **验收**：release 包各功能与 debug 一致；无 `ClassNotFoundException`、
  无字段被混淆导致的反序列化失败。

### T52 `[ ]` 兼容性与体积
- **要做**：Android 8～15 关键路径回归；重点查前台服务类型
  （`FOREGROUND_SERVICE_MEDIA_PLAYBACK` 在 Android 14+ 有额外声明要求，
  漏了会直接崩溃）；Compose 与 media3 体积裁剪。
- **验收**：目标区间内的系统版本均能启动并完成一次完整阅读。

### T53 `[ ]` 全量验收
- **要做**：
  1. `tools/ci.sh` 全绿（含四道合规门禁）；
  2. `tools/test_jvm.sh` 全绿，断言数不低于 1,434；
  3. 逐条对照本清单勾选，未完成项写明原因与缺口；
  4. 更新 README 的模块清单与单测表格。
- **验收**：上述四项全部有据可查，不接受「应该没问题」。

---

## 附：任务依赖关系

```
T01 ─┐
T02 ─┼─→ T04 ─→ M1 出口（首个 APK）
T03 ─┘         │
T05 ───────────┘
                ↓
            T10 ─→ T11 ─→ T12 ─→ T13 ─→ T14 ─→ T15  （M2 / MVP）
                                            ↓
                                    ┌───────┴───────┐
                                   T20…T22        T30…T32
                                   （M3 朗读）    （M4 弹幕）
                                            ↓
                                   T40…T42（M5 离线）
                                            ↓
                                   T50…T53（M6 交付）
```

**可以并行的部分**：M3（朗读）与 M4（弹幕）互不依赖，两人接手时可分开做；
M5 的备份（T41）与下载（T40）也可拆开。
**不能并行的部分**：M1 必须最先完成，否则任何 UI 代码都无法验证。
