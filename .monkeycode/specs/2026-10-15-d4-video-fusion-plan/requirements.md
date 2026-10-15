# Requirements Document

Feature Name: d4-video-fusion-plan
Updated: 2026-10-15

## Introduction

本文档约束 D4《弹幕保留融合方案》必须达成的分析目标与方案质量要求。D4 为纸面日（只读分析），产出物为方案文档，供 D8（gsyVideo 内核融合）、D9（服务与播放壳）、D10（播放壳收尾）三个搬运日直接执行。方案必须回答：以 m 播放器壳为基座时，工作区弹幕渲染层如何零丢失保留。

## Glossary

- **m 源**: `_refs/m`，syq17496152/legado 锁定 commit `46dc931`，视频栈增强版
- **工作区**: `/workspace` 主仓库，DB v90 基线
- **弹幕渲染层**: `VideoPlayer`（GSY 控件）内基于 `master.flame.danmaku` DanmakuView 的弹幕加载/渲染/开关实现
- **播放壳**: `ui/video` 下承载播放器控件的界面层（Activity/Fragment/Adapter）
- **同名文件**: 工作区与 m 在 `help/gsyVideo/` 下文件名相同的 10 个文件
- **覆盖性**: m 版文件包含工作区版全部函数与语义的程度

## Requirements

### Requirement 1: 逐文件对比结论

**User Story:** AS D8 执行者, I want 10 个同名文件逐一的对比结论, so that 覆盖动作有据可依。

#### Acceptance Criteria

1. WHEN 方案文档产出, THE 方案文档 SHALL 包含 10 个同名文件每个的行数对比、差异本质归纳、采用结论（取 m / 取工作区 / 手工合并）。
2. THE 方案文档 SHALL 以函数级覆盖性检查证明 m 基座对工作区 `VideoPlayer`（55 函数）、`VideoPlayService`（42 函数）、`VideoPlayerViewModel`、`ChapterAdapter`、`VideoPlay`（14 函数）零缺失。

### Requirement 2: 弹幕链路完整保留

**User Story:** AS 用户, I want 融合后弹幕功能与融合前行为一致, so that 观看体验无回退。

#### Acceptance Criteria

1. THE 融合方案 SHALL 覆盖弹幕链路全部五个环节：数据源赋值、全局状态、视图渲染、开关入口、生命周期同步。
2. WHEN D8 以 m 版 `VideoPlayer.kt` 覆盖工作区同名文件, THE 方案 SHALL 确认弹幕渲染全套函数（`initDanmaku`/`resolveDanmakuShow`/`createParser`/`onPrepareDanmaku`/`danmakuOnPause`/`danmakuOnResume`/`releaseDanmaku`）在 m 版中全部存在。
3. IF m 版对弹幕任一环节有增强, THE 方案 SHALL 记录增强点及其对工作区调用方的影响。

### Requirement 3: 依赖闭包完整披露

**User Story:** AS D8/D9/D10 执行者, I want 完整的隐藏依赖清单, so that 搬运日一次到位无编译断链。

#### Acceptance Criteria

1. THE 方案文档 SHALL 列出 m 视频栈（gsyVideo 10 文件 + ui/video 全部 + VideoPlayService）import 的、工作区缺失的每一个 `io.legado.app` 类。
2. THE 方案文档 SHALL 为每个缺失依赖标注建议搬运日（D5/D7/D8/D9/D10）与来源路径。
3. WHEN 缺失依赖涉及 Room 实体或 DB 迁移, THE 方案文档 SHALL 明确标注迁移时机与版本推进约束。

### Requirement 4: 落点可执行

**User Story:** AS D8 执行者, I want 逐日落点表, so that 动文件前有精确操作序列。

#### Acceptance Criteria

1. THE 方案文档 SHALL 给出 D8/D9/D10 逐日文件操作清单（覆盖/新增/删除/资源）。
2. THE 方案文档 SHALL 给出弹幕在双布局模式（legacy 单页 / ViewPager 多页）下的开关入口落点及防重复入口措施。
3. WHEN 融合存在失败风险, THE 方案文档 SHALL 给出回滚分支与回滚动作。

### Requirement 5: 纸面日纪律

1. THE D4 执行 SHALL 零代码改动（不含 `.monkeycode` 文档与规格）。
2. THE D4 产出 SHALL 在日末提交并注明清单编号（2.1.1 前置 / 2.1.2 前置）。
