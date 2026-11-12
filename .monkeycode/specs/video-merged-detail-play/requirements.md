# Requirements Document

## Introduction

阅读 App 的视频类书源当前存在「详情页」与「播放页」分离的链路：从分类/发现列表点击视频条目进入 BookInfoActivity 详情页，再点击播放才进入全屏 VideoPlayerActivity。本次改造将详情页与播放页合并为单一页面（播放器在上、详情信息/播放源/合集在下同页滚动，参考流媒体 App 的播放详情页形态），并针对视频类书源与首页影视壳源，使分类列表点击直接进入合并播放页，减少一次中间跳转。

## Glossary

- **视频类书源**：书源配置中 kind 包含「视频」标识的书源，条目点击后应进入视频播放链路。
- **首页影视壳源**：首页（PyHomepage）通过 VideoSource 插件提供的影视类来源，列表条目点击后进入视频播放链路。
- **合并播放页**：播放器与详情信息（封面、标题、简介、播放源线路、合集选集）在同一页面纵向排列、可整体滚动的播放页。
- **详情页（旧）**：当前 BookInfoActivity 对视频书源的展示页，改造后视频书源不再经此页进入播放。
- **布局模式**：VideoPlayerActivity 支持的两种布局——沉浸式 ViewPager2（layoutMode=0）与传统合并布局（layoutMode=1）。

## Requirements

### Requirement 1: 视频书源分类列表直达合并播放页

**User Story:** AS 视频书源用户，我希望在分类列表点击视频条目后直接进入合并播放页，以便跳过详情页中间跳转、一步进入播放并查看详情与选集。

#### Acceptance Criteria

1. WHEN 用户在分类列表（ExploreShowActivity 的分类条目列表）点击视频类书源条目，THE 系统 SHALL 直接启动合并播放页（VideoPlayerActivity 合并布局模式），并携带 bookUrl 与书架状态，不经过 BookInfoActivity 详情页。
2. WHEN 合并播放页启动，THE 系统 SHALL 立即加载视频书源目录（卷/线路/集数映射），并在播放器就绪后起播当前集。
3. WHEN 合并播放页已打开，THE 系统 SHALL 在同一页面内展示封面、标题、简介、播放源线路与合集选集列表，用户可上下滚动浏览详情并点选集/线路。
4. IF 用户在合并播放页内点选其他集或线路，THE 系统 SHALL 在当前页面内切换播放，无需跳转新页面。

### Requirement 2: 首页影视壳源列表直达合并播放页

**User Story:** AS 首页影视壳源用户，我希望点击首页影视列表条目后直接进入合并播放页，以便与视频书源保持一致的合并播放体验。

#### Acceptance Criteria

1. WHEN 用户在首页影视壳源列表点击播放条目，THE 系统 SHALL 直接启动合并播放页，携带 videoUrl、集数列表（episodesJson）、插件 ID 与初始集序号，不经过其他中间页面。
2. WHEN 合并播放页启动影视壳源内容，THE 系统 SHALL 在页面内展示集数列表供点选，并保持书源与影视壳源的播放能力一致（播放、选集、切换、收藏、下载、设置）。
3. IF 影视壳源集数列表为空，THE 系统 SHALL 优雅降级为仅标题的单 URL 播放，不阻塞播放。

### Requirement 3: 合并播放页信息展示与交互

**User Story:** AS 视频类书源用户，我希望合并播放页按流媒体风格展示详情与选集，以便在同一页面完成观看、了解详情与选择剧集。

#### Acceptance Criteria

1. WHILE 合并播放页展示，THE 系统 SHALL 将播放器固定于页面顶部，详情信息区（封面、标题、简介等）与选集区置于播放器下方，整体纵向滚动。
2. WHEN 书源提供封面、标题与简介，THE 系统 SHALL 在详情区展示封面、标题与简介；简介支持纯文本与富文本（HTML）渲染。
3. WHEN 视频书源包含多线路（目录卷），THE 系统 SHALL 在详情区下方展示线路列表供用户切换，切换后 SHALL 重新起播对应线路当前集。
4. WHEN 视频书源包含多集，THE 系统 SHALL 在页面内展示集数列表（当前播放集高亮），点选集 SHALL 在当前页面切换播放并高亮新集。
5. WHEN 用户点击播放器区域或全屏按钮，THE 系统 SHALL 提供进入全屏沉浸播放的能力，以便获得大屏观看体验。

### Requirement 4: 合并播放页功能完整性

**User Story:** AS 视频类书源用户，我希望合并播放页保留完整的播放与管理功能，以便在合并页面内完成观看与操作。

#### Acceptance Criteria

1. WHEN 合并播放页展示，THE 系统 SHALL 提供与沉浸式播放页一致的核心功能：收藏、下载、悬浮窗、设置、弹幕。
2. WHEN 视频书源存在队列上下文（上一部/下一部），THE 系统 SHALL 在合并播放页提供上一部/下一部切换，切换后 SHALL 保持合并布局不漂移。
3. WHEN 合并播放页内播放失败，THE 系统 SHALL 展示与沉浸式一致的错误提示，并支持换线路自愈或错误框处理。
4. WHEN 用户切换布局模式（合并 ↔ 沉浸），THE 系统 SHALL 保持当前播放进度与线路/集数上下文，切换后从原进度续播。

### Requirement 5: 兼容与回退

**User Story:** AS 用户，我希望改造不影响既有非视频链路，视频书源在无法进入合并页时仍可正常播放。

#### Acceptance Criteria

1. IF 视频书源条目未满足直达条件（如数据加载失败），THE 系统 SHALL 回退至既有详情页链路，保证可播放。
2. WHEN 音频书源、图片书源、普通文本书源点击，THE 系统 SHALL 保持既有跳转链路（AudioPlayActivity/ReadMangaActivity/ReadBookActivity），不受本次改造影响。
3. WHEN 书架、阅读记录、存储管理等已入库入口点击视频书源，THE 系统 SHALL 保持既有 VideoPlayerActivity 跳转，并按合并布局呈现（与列表直达一致）。
4. WHEN 用户从详情页（BookInfoActivity）内点击视频书源「开始阅读」，THE 系统 SHALL 进入合并播放页，与列表直达体验一致。

## References

- 截图目标形态：播放器（顶部）+ 标签/详情卡 + 播放源线路 + 合集选集，同页滚动
- 现状链路：ExploreShowActivity 列表 → BookInfoActivity 详情 → VideoPlayerActivity 播放
- 既有合并布局：VideoPlayerActivity 传统布局（layoutMode=1），已支持书源信息/线路/集数绑定

## 设计决策（已与用户确认）

1. **合并页实现方式：复用现有合并布局**。不新建独立 Activity，VideoPlayerActivity 通过 intent 布局模式覆盖（`layout_mode_override`）强制使用传统合并布局（layoutMode=1），复用全部播放功能（收藏/下载/悬浮窗/设置/弹幕/DLNA）。
2. **保留全屏沉浸入口**。合并页为默认形态，用户点击播放器区域或全屏按钮时切换到现有 ViewPager2 沉浸模式，返回合并页时保持进度与上下文衔接。
3. **入口范围全部统一**。分类列表、搜索结果、首页影视壳源、书架/阅读记录/存储管理已入库入口、详情页内「开始播放」、订阅源文章播放，全部统一走合并布局呈现。

