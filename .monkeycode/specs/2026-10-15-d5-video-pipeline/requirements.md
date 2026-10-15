# Requirements Document

Feature Name: d5-video-pipeline
Updated: 2026-10-15
清单编号: 2.1.3 视频管线
执行日: D5
前置: D4《弹幕保留融合方案》已确认 D8 前置条件 = D5 管线合入

## Introduction

本文档约束 D5 视频管线搬运任务的目标与验收口径。D5 将 m 源（锁定 commit `46dc931`）的 `help/video/` 视频采集链全套移植到工作区，为 D8（gsyVideo 内核融合）、D9（服务与播放壳）提供播放公共链路基座。D5 为纯新增日：工作区现有文件零覆盖，新增文件在 D8 接线前保持编译就位但零调用路径。

## Glossary

- **m 源**: `_refs/m`，syq17496152/legado 锁定 commit `46dc93167ae67d8d912a0fb52352833cd922f15e`
- **工作区**: `/workspace` 主仓库，DB v90 基线
- **管线**: `help/video/` 下 10 个文件构成的视频采集链组件集（6 个顶层 + 4 个 engine/ 子包）
- **纯新增**: 仅新增文件与既有文件尾部追加成员，禁止改写任何既有代码行
- **惰性**: 新增代码在 D8/D9 接线前无任何工作区既有代码调用
- **支撑性追加**: 为满足编译而向既有文件追加的 m 版成员声明（append-only、对既有调用方零行为影响）

## Requirements

### Requirement 1: 管线文件簇完整搬运

**User Story:** AS D8 执行者, I want `help/video/` 全套管线组件就位, so that gsyVideo 内核与播放壳融合时采集链可直接接线。

#### Acceptance Criteria

1. THE 工作区 SHALL 新增 `help/video/` 下 6 个文件：`VideoPlaybackPipeline.kt`、`VideoUrlExtractor.kt`、`MacCmsNormalizer.kt`、`VideoFavoriteResolver.kt`、`VideoPlaylistHolder.kt`、`SniffCandidate.kt`。
2. THE 工作区 SHALL 新增 `help/video/engine/` 下 4 个文件：`SniffEngine.kt`、`SniffModels.kt`、`HeaderResolver.kt`、`M3u8Parser.kt`。
3. THE 移植内容 SHALL 以 m 源锁定 commit 的文件内容为基准逐字节一致，唯一例外为 `VideoUrlExtractor.kt` 中 `BackstageWebView` 构造调用的单点适配（见 Requirement 4）。
4. WHEN `VideoPlaybackPipeline` 装配弹幕数据, THE 管线 SHALL 保留弹幕赋值点（`chapter.getDanmaku()` 分派至 `VideoPlay.danmakuStr`/`VideoPlay.danmakuFile`），作为 D4 弹幕链路环节 1 的新家。

### Requirement 2: 零覆盖纪律

**User Story:** AS 工作区维护者, I want D5 全部改动可按文件名区分新增与追加, so that 阶段 1 回归时能精确圈定 D5 影响面。

#### Acceptance Criteria

1. THE D5 改动 SHALL 满足工作区既有文件零行级改写，仅允许对本方案披露的 5 个既有文件做尾部追加与白名单内 1 处可见性扩宽（`VideoPlay.videoTempFile` private→internal，对齐 m 声明）。
2. THE 追加成员 SHALL 逐字取自 m 源同名声明（签名、默认值、注解一致），与工作区既有成员零重名冲突。
3. IF 追加成员在工作区缺失其依赖的内部实现（如 AppLog 的 Level 体系）, THE 实现 SHALL 采用工作区既有等价能力做薄适配，并在设计文档依赖台账中登记完整升级路径。

### Requirement 3: 依赖闭包编译完整

**User Story:** AS D5 执行者, I want 管线全部 import 在工作区可解析, so that assembleDebug 一次通过。

#### Acceptance Criteria

1. THE 工作区 SHALL 新增 `data/entities/RssEpisode.kt`（纯 Parcelable 数据类，无 Room 注解，无 DB 迁移）。
2. THE 工作区 SHALL 向 `constant/EventBus.kt` 追加 `VIDEO_PLAY_ERROR` 常量、向 `constant/AppLog.kt` 追加 `Level` 枚举与 `TAG_RSS` 常量及 `putError`/`putWarn`/`putInfo`/`putDebugWithTag` 日志变体、向 `help/exoplayer/ExoPlayerHelper.kt` 追加 `sanitizeUrl` 函数、向 `model/VideoPlay.kt` 追加 `cachePlay`/`isLoadingFalse()`/`switchTokenCounter`/`currentSwitchToken`/`currentPlayHeaders`/`resolvedChapterUrl` 六个成员、向 `help/http/BackstageWebView.kt` 追加惰性属性 `lastSniffCandidate`。
3. WHEN assembleDebug 执行, THE 构建 SHALL 通过且无因 D5 新增文件引入的编译错误。

### Requirement 4: 已知缺口单点适配与台账登记

**User Story:** AS D7/D8 执行者, I want D5 无法闭环的依赖缺口有明确登记与恢复路径, so that 后续搬运日按台账补齐、无隐性漂移。

#### Acceptance Criteria

1. THE `VideoUrlExtractor.kt` 移植版 SHALL 在 `BackstageWebView` 构造调用处省略 `interceptAllRequests` 与 `videoSniffJs` 两个实参，并以注释标记 D7 恢复点。
2. THE 设计文档 SHALL 将「BackstageWebView 视频嗅探增强融合」登记为台账项并指派 D7 执行，含恢复动作（补回 2 个实参 + BackstageWebView 增强移植约 150 行）。
3. THE 设计文档 SHALL 修正 D4 依赖台账中 `RssEpisode` 的搬运日（D8 → D5）并注明修正原因（`VideoPlaybackPipeline` 直接 import）。

### Requirement 5: 惰性保证

**User Story:** AS 工作区维护者, I want D5 合入后阅读与既有视频主流程行为零变化, so that D5 不引入任何功能回归风险。

#### Acceptance Criteria

1. THE 工作区既有代码 SHALL 在 D5 后对管线组件保持零引用（管线消费方随 D8/D9 搬入）。
2. THE 追加成员 SHALL 为惰性声明：既有调用方路径无法触达新增成员（新成员仅被管线与 D8 后接线代码调用）。
3. WHILE DB 处于 v90, THE D5 改动 SHALL 保持 DB 版本号、实体注册、迁移文件零变化（`RssEpisode` 为非 Room 数据类）。

### Requirement 6: 验证与交付

#### Acceptance Criteria

1. WHEN D5 代码就位, THE 执行 SHALL 通过 `./gradlew :app:assembleDebug` 构建验证。
2. THE 日末提交 SHALL 注明清单编号（2.1.3）并推送远端分支。
3. THE 产出 SHALL 包含静态核查记录：新增文件数、追加成员数、零覆盖核查（`git diff` 仅含新增文件与 5 个追加文件）。
