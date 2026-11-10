# APK 开发大纲

> 目标：把当前 118 个文件、75 个 Kotlin 源文件（12,762 行）的**纯逻辑骨架**，
> 补成可安装、可运行的 Android 应用并产出签名 APK。
>
> 本文是**路线与技术判断**；逐条可执行事项见 [`TASK-BACKLOG.md`](TASK-BACKLOG.md)；
> 环境重建、测试跑法、踩过的坑见 [`AI-HANDOFF.md`](AI-HANDOFF.md)。

---

## 一、当前真实状态（接手前必读）

### 1.1 已经有什么

| 层 | 位置 | 完成度 |
|---|---|---|
| 规则引擎 | `core/engine/` | 可用：JsRuntime 池、DOM 解析、通道粘滞、限流、探嗅、分发 |
| 网络通道 | `core/net/` | 可用：通道抽象 + OkHttp 实现 |
| 源契约与执行 | `source/model/` `source/rule/` | 可用：字段契约、语法、编排、执行 |
| 多源聚合 | `source/aggregate/` | 可用：去重、书名归并、空作者回填 |
| 书源导入 | `source/import/` | 可用：Legado JSON、订阅合集 |
| 正文净化 | `source/purify/` | 可用：替换引擎 + replaceRule 编解码 + 管线缓存（R19–R21） |
| 朗读分段调度 | `core/tts/` | 可用：分段 + 状态机（R22），**未接系统 TTS** |
| 弹幕数据层 | `danmaku/` | 可用：匹配、格式转换、屏蔽、渲染参数、源校验与排序（R23） |
| 排版计算 | `core/render/` | 可用：TypographySpec、Paginator、进度、设置、翻页控制、主题 |
| 数据层 | `data/db/` `data/backup/` | 可用：Room 实体与 DAO、备份 DTO 与合并策略 |
| 书架操作 | `bookshelf/` | 可用：增删、分组、排序、换源进度映射 |
| 安全与观测 | `core/security/` `core/observability/` | 可用：权限、桥接安全、本地指标（仅落盘） |

**测试资产**：27 个测试文件、6,267 行，`tools/test_jvm.sh` 注册 24 个测试类，
最近一次全量 21 套 / 1,434 项断言 / FAIL=0。这是接手时最重要的安全网——
任何改动都必须保持这个数字只增不减。

### 1.2 还缺什么（按阻塞强度排序）

1. **`LanyueApp` 类不存在。** `AndroidManifest.xml` 第 18 行声明了
   `android:name=".LanyueApp"`，但源码里没有这个文件。**这是当前的硬编译阻塞**，
   APK 一定打不出来。
2. **没有 Gradle Wrapper。** `settings.gradle.kts` / `build.gradle.kts` /
   `app/build.gradle.kts` 都在，但缺 `gradle/wrapper/gradle-wrapper.properties`
   与 `gradlew`，任何人都无法用固定版本复现构建。
3. **没有任何 UI。** 无 `MainActivity`、无 Compose 页面、无导航。
   `AndroidManifest` 第 26 行已声明一个 activity 但无对应实现。
4. **系统 TTS 未接线。** `TtsReadController` 通过 `speak` / `stopSpeaking`
   两个函数注入引擎，真实实现（`TextToSpeech` 生命周期、语言包检测、
   断句参数）还没有。
5. **弹幕没有渲染视图。** `danmaku/render/` 只有参数与屏蔽策略，
   没有负责绘制/滚动的 View 层。
6. **下载与离线缓存缺失。** 只有网络层与数据库，没有章节落盘队列、
   断点续传、空间回收。
7. **打包配置未就绪。** 无签名配置、无 R8 规则、无正式/调试 flavor 区分。

---

## 二、目标形态与验收口径

### 2.1 「能打出 APK」的三档定义

| 档位 | 含义 | 验收 |
|---|---|---|
| **A. 可构建** | `./gradlew assembleDebug` 成功出包 | APK 可安装，启动到首页不崩 |
| **B. 可读**（MVP） | 导入书源 → 搜索 → 详情 → 目录 → 阅读正文 | 全链路在真机跑通，翻页正常 |
| **C. 可交付** | B + 朗读 + 弹幕 + 离线缓存 + 备份恢复 | 需求文档 P0 全绿，P1 主要项可用 |

本文按 **A → B → C** 推进。**不要跳过 A 去做 UI**：现在连
`LanyueApp` 都没有，UI 代码写了也编不过，会把问题搅在一起。

### 2.2 不可协商的约束

- **纯本地**：无埋点、无遥测、无第三方统计 SDK。`tools/check_no_telemetry.py`
  是门禁，不是建议。
- **权限最小化**：新增权限必须同步更新 `tools/check_permissions.py` 的白名单，
  否则 CI 直接失败。
- **免责声明**：`tools/check_disclaimer.py` 校验相关文案存在。
- **契约稳定**：`tools/check_contracts.py` 校验源字段契约。
  改 `source/model/` 必须走显式版本迁移，不能在原字段上改语义。

---

## 三、里程碑路线

### M1 — 构建打通（约 2 轮）

**目标**：`assembleDebug` 出包，装到模拟器能启动。

要做的事：
1. 补 `LanyueApp`（`Application` 子类，负责 Room 初始化、日志开关、
   崩溃兜底），与清单声明对齐。
2. 补 Gradle Wrapper，锁定版本（与 `app/build.gradle.kts` 中的 AGP/Kotlin 匹配）。
3. 补最小 `MainActivity`（Compose 空壳），先把清单第 26 行的 activity 兑现。
4. 首次真实构建：**这一步会暴露所有只在 Android 编译期才出现的错误**
   （Room KSP 生成、Compose 编译器插件、依赖冲突）。预计需要 1 轮专门修错。

**出口**：`./gradlew assembleDebug` 成功；APK 安装后启动不崩；现有 1,434 项
JVM 测试仍全绿。

> ⚠️ 已知风险：`app/build.gradle.kts` 用 `jsoup:1.17.2`，但 JVM 测试脚本用
> 1.13.1（1.17.2 的可空注解会让测试代码编不过）。Android 编译用 1.17.2 没问题，
> 但**不要**为了「一致」把测试脚本升到 1.17.2，那会让 27 个测试文件大面积报错。

### M2 — 阅读主链路（约 4 轮，MVP 核心）

**目标**：导入书源 → 搜索 → 详情 → 目录 → 正文 → 翻页，全链路真机可用。

1. **应用骨架**：导航（书架 / 书城 / 我的）、主题系统接线、
   全局状态容器（ViewModel + Repository，不要直接让 UI 碰 DAO）。
2. **书源管理页**：导入（文件选择 / 粘贴 JSON）、列表、启用停用、
   进入离线调试。复用 `source/import/` 与 `source/debug/`。
3. **搜索与详情页**：接 `source/aggregate/` 的多源聚合，
   展示来源归并结果；详情页拉起目录。
4. **阅读页**：这是最大一块。接 `core/render/` 的
   `Paginator` / `PageTurnController` / `ReadingSettings` / `Theme`，
   实现分页绘制、字号行距边距调节、主题切换、进度保存与恢复。
5. **正文获取与净化串联**：`source/rule/` 取正文 → `source/purify/`
   净化 → 交给排版。注意净化必须在排版前，否则字数变化会让分页错位。

**出口**：一本真实书源的书能从搜索读到最后一页，翻页无错位，
退出重进能恢复位置。这一档就是通常说的 MVP。

### M3 — 朗读接线（约 2 轮）

1. `TextToSpeech` 适配层：初始化、语言可用性检测、`speak` 排队策略、
   `UtteranceProgressListener` 回调桥接到 `TtsReadController.onSegmentFinished`。
2. 朗读控制条 UI：播放 / 暂停 / 上一段 / 下一段 / 语速音调。
3. 跟随高亮与阅读位置同步（`TtsProgress.charOffset` 已经准备好了）。
4. 章节读完的处理：自动续读下一章还是停下等用户，做成设置项。

**出口**：点朗读能出声；暂停恢复不从头重来；读完一章行为符合设置。

### M4 — 弹幕渲染（约 2 轮）

1. 渲染视图：滚动/顶部/底部三种轨道，用 `danmaku/render/` 的参数驱动。
2. 引擎循环：按时间轴出弹幕、回收离屏项、控制同屏密度。
3. 设置面板：透明度、字号、速度、显示区域、屏蔽词与屏蔽类型
   （`DanmakuFilter` 已实现策略）。
4. 弹幕源配置页：接 `DanmakuSourceValidator` + `DanmakuSourceRegistry`，
   把 R23 做好的校验三态、排序、启停真正露出到 UI。

**出口**：播放视频时有弹幕飘过；调节设置实时生效；弹幕源可增删排序。

### M5 — 离线与备份（约 2 轮）

1. 下载队列：章节批量落盘、并发上限、失败重试、断点续传、空间统计与清理。
2. 备份恢复：接 `data/backup/`，导出/导入 JSON，处理版本迁移与合并冲突。
3. 可选：WebDAV 同步（需求文档提到自托管场景）。

**出口**：断网能读已下载章节；备份导出后在另一台设备能完整恢复。

### M6 — 交付打磨（约 2 轮）

1. 签名配置：debug / release 分离，密钥不进仓库（走环境变量或
   `local.properties`，并确保被 `.gitignore` 覆盖）。
2. R8/ProGuard：保留规则（Room 实体、序列化类、JS 引擎反射入口）。
3. 体积与启动优化：Compose 与 media3 是体积大头，按需裁剪。
4. 兼容性回归：Android 8～15 关键路径过一遍，重点看
   前台服务类型声明（`FOREGROUND_SERVICE_MEDIA_PLAYBACK` 在 14+ 有额外要求）。

**出口**：release APK 可签名安装、可升级覆盖安装、无明显崩溃。

---

## 四、工作量估算

| 里程碑 | 轮数 | 累计 | 产出 |
|---|---|---|---|
| M1 构建打通 | 2 | 2 | **首个可安装 APK（A 档）** |
| M2 阅读主链路 | 4 | 6 | **MVP 可读（B 档）** |
| M3 朗读 | 2 | 8 | 朗读可用 |
| M4 弹幕 | 2 | 10 | 弹幕可用 |
| M5 离线备份 | 2 | 12 | 离线可用 |
| M6 交付打磨 | 2 | 14 | **可交付（C 档）** |

一轮 ≈ 一个可独立评审的提交单元（一个模块或一组强相关文件），
含实现 + 单测 + README 更新 + 全量回归。

> 估算偏差主要来自 M1 和 M2：Android 首次真实构建常在 KSP/Compose 插件/
> 依赖版本上卡住，可能多吃 1～2 轮；阅读页排版如果追求精细（标点悬挂、
> 段首缩进、双栏），也会超预期。

---

## 五、技术决策备忘

- **UI 用 Compose**：`app/build.gradle.kts` 已经引入 compose + material3，
  不要另起 View 体系，否则两套并存。
- **阅读页不要用 Compose 的 Text 逐页排版**：分页计算必须在
  `core/render/Paginator` 里完成（它已经能算），Compose 只负责绘制
  已算好的页。每次重排都重新测量整章会明显卡顿。
- **弹幕用自绘 View 或 Canvas**：Compose 做高频动画会有额外开销，
  弹幕是同屏几十个元素持续移动，优先考虑 `SurfaceView` / `Canvas` 直绘。
- **朗读与播放共用前台服务**：`AudioPlaybackService` 已存在，
  朗读接进来比新开一个服务更省事，也能复用通知栏控制。
- **数据库迁移**：`data/db/` 已有实体，接入真实构建后 Room 会做 schema 校验。
  第一版就把 `exportSchema = true` 打开并提交 schema JSON，
  否则后续加字段会没有迁移依据。
