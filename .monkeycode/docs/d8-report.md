# D8 报告：gsyVideo 内核融合（分支 261015-feat-video-core）

## 结论
- `:app:assembleDebug` BUILD SUCCESSFUL（6m31s，全量含 schema 导出 93.json）。
- D4 方案落点表全部完成：10 个 gsyVideo 文件 + 播放器家族文件取 m，弹幕链路保留不变量静态校验通过。

## 取 m 文件清单
### gsyVideo（10 文件，m 严格超集，D4 方案 §融合取 m）
- VideoPlayer.kt（融合基座，11/11 弹幕函数保留）、ExoVideoManager.kt、CustomExoPlayerManager.kt、VideoTextureView.kt、PlayerSurfaceCallback.kt、SpeedDialog.kt、SwitchVideoDialog.kt 等。

### 播放器家族
- help/player/：ErrorMapper、PlaybackErrorPolicy、SniffRace（3 文件）
- help/exoplayer/：HlsKeyDataSourceFactory、PlayerInstancePool、FirstFramePreloader、ImageEnhanceEffects、DeviceInfoHelper、M3u8PreCheckDataSource、MimeSniffer、MimeSnifferCache、TlsFallbackDataSourceFactory、VideoPrefiller、VideoPreloader + ExoPlayerHelper 覆盖为 m 版（含 createPreloadDataSource）
- lib/theme/：UiCorner、ThemeUiPalette、ThemeRuntimeKeys
- model/：VideoPlay.kt（2460 行 m 版）、VideoPlaybackQueue.kt
- ui/video/：ImageEnhanceController、VideoBookPreloader
- data/：PlayHistoryStore、entities/PlayHistory、dao/PlayHistoryDao
- help/dlna/：整包 21 文件（SSDP/AVTransport/Soap/Hls 重写/分段缓存/代理服务）
- help/http/：DohDns、HostAccessStrategy
- help/source/：SourceLastHostHelper、SourceNetworkClient；BookSourceExtensions 增 isVideoSource
- model/rss/：Rss.kt 整体取 m（多线路多集采集 getRoutesContentAwait/getEpisodesAwait/buildRouteJson/getEpisodesListByIndex/parseEpisodesResult）

## 资源分区（D8 增量）
- strings_m.xml：43 条 video_* + 13 条 player_error_*；values-zh/strings_m.xml：22 条
- dimens_m.xml：text_16sp、text_18sp、ui_panel_radius(10dp)、ui_action_radius(9dp)
- drawable：ic_volume_off、bottom_progress_buffer
- layout：speed_dialog_item、switch_video_dialog_item_separator；video_layout_controller(_full) 覆盖为 m 版（含 iv_mute/tv_ratio/tv_audio 布局 id）
- 死布局 video_player_control.xml 已按 D4 方案 git rm
- 与基线同名同义键 3 条（video_play/video_setting/video_setting_summary）已从分区去重

## 与 m 源的偏差（全部为对基座适配）
1. **HttpHelper 增量取用**：只加 `videoStreamClient`（HTTP/1.1 强制，Range 嗅探防 ERR_HTTP2_PROTOCOL_ERROR）；m 的 HttpHelper 全量演进（412 行 vs 工作区 226 行）仍留 D11。
2. **AppConfig/AppLog/PreferKey**：AppLog 整体取 m（337 行，put 签名兼容 493 处旧调用）；AppConfig 增 sniffRaceEnabled + 6 个外观属性（bookCoverShadow/uiCornerScale/uiLayoutAlpha/dialogAlpha/uiCornerSearchFollow/uiCornerReplyFollow + Float.toPlainScale）；PreferKey 增 46 常量（43 外观键 + sniffRaceEnabled + themeFirstInstallDone/currentAppearanceKitId/appearanceKitAutoApplyDone）。去重后无重复声明。
3. **VideoPlayerActivity/VideoPlayService initSource 挂起化适配**：m 的 initSource 为 suspend；D5 版两处调用点包 `lifecycleScope.launch`，语义对齐 m 的 VideoPlayService:222 同款写法（T2.8 initSourceJob 泄漏治理属 D9）。
4. **AppLogDialog 桥接**：Sigma 版 LogAdapter 泛型 Triple<Long,String,Throwable?> 保持，调用点 `AppLog.logs.map { Triple(it.time, it.message, it.throwable) }`；LogEntry.level 信息未在对话框展示（m 的 Compose 版 AppLogDialog 依赖 ui/widget/compose 组件库 54 文件，属 D10+ 批次）。
5. **controller 布局覆盖**：Sigma 若有定制样式（28/49 行差异）被 m 版覆盖，视觉回归项移交手测。

## DB v92→93（D4 方案明示 D9 前置提前落地）
- 新表 playHistories（复合主键 articleUrl+videoUrl+rssSourceId，schema 取 m v109 终形态，无存量数据迁移）
- rssSources + ruleRoutes/ruleEpisodes（视频多线路/多集规则列）
- bookSources + rssSources + lastHost（源真实域名回填列）
- 全部为 AutoMigration(92→93) 可自动处理的加表/加可空列；93.json 已导出
- dao 增 bookSourceDao/rssSourceDao.updateLastHost；AppDatabase 注册 playHistoryDao

## 不变量校验
- 弹幕：fused VideoPlayer.kt 11/11 弹幕函数在位；播放数据赋值点唯一于 VideoPlaybackPipeline.kt:163-164；VideoPlay.kt 仅 null 清理（与 m 一致）
- EventBus 增 VIDEO_BOOK_UNIT_SWITCHED、ARTICLES_LOADED 两常量（m 原值）

## 手测移交（本环境无法覆盖）
1. 真机弹幕渲染与音画同步（D4 方案 8 项验证清单）
2. 倍速/线路/集数对话框（新布局资源）视觉回归
3. DLNA 投递全链路（SsdpDiscovery/AvTransport，dlna 包首次进入工作区）
4. controller 布局被 m 版覆盖后的全屏/静音/比例控件行为
5. 主题外观键（uiCornerScale/uiLayoutAlpha/dialogAlpha）默认值下的 UI 观感

## D9 输入
- VideoPlayService 融合（现工作区 D5 版已含 lifecycleScope 适配，D9 取 m 版时注意保留）
- PlayHistory 表已就绪（v93）；m 的 VideoPlayerActivity/ViewModel T2.8/AD-04/AD-06 治理随 D9 落
- m 的 ui/widget/compose 组件库（54 文件）与 miuix 依赖评估仍属 D10+
