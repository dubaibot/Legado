# D9 报告：VideoPlayService 融合 + 播放壳搬入（分支 261015-feat-d9-video-shell）

## 结论
- `:app:assembleDebug` BUILD SUCCESSFUL（6m02s，APK 56.6MB，共 20 轮构建迭代）。
- DB 实库演练 v90→v93 PASS（migration_90_93.sql）、v92→v93 PASS（migration_92_93.sql），结构与行数保全比对一致。
- 清单 2.1.4（VideoPlayService 融合）与 2.1.2 前半（播放壳 Activity/Fragment/Adapter + 布局资源）完成。

## 取 m 文件清单（约 55 文件）
### 覆盖（融合/严格超集）
- service/VideoPlayService.kt（T2.8 initSourceJob 泄漏治理随 m 版落地）
- ui/video/{VideoPlayerActivity,VideoPlayerViewModel,ChapterAdapter}.kt、res/layout/activity_video_player.xml
- help/glide/{ImageLoader,OkHttpModelLoader,OkHttpStreamFetcher}.kt（m 为同功能线进化版：failUrl→LruCache TTL 指数退避、referer 兜底注入、小内存 skipDecode 守卫、AppLog 日志治理）
- ui/widget/image/CoverImageView.kt、model/BookCover.kt、utils/BitmapUtils.kt（m 纯超集）
- ui/rss/read/ReadRss.kt（工作区 121 行自 D2 零改动，m 381 行超集演进）
- utils/{ImageLoader 同上}；ReadTipTemplate/AdvancedTitleConfig 随 D8 UiTypography 消费链补拉

### 新增
- ui/video/：VideoFragment、VideoPagerAdapter、RssEpisodeAdapter、RssRouteAdapter（视频 Fragment 壳家族）
- ui/video/cast/：DlnaCastDialog、DlnaCastContent、DlnaCastSettingsPanel；service/DlnaCastService（D10 落点提前量）
- ui/video/config/SettingsDialog（onLayoutModeSelected 生产者）；ui/video/{VideoSettingsPanel,VideoSettingsPanelContent,VideoBookDetailSheet}（D10 提前量）
- ui/image/：ImageGalleryActivity（1437 行）、ImagePlay、ImageCanvasItem、ImageCanvasViewModel、ImagePyramidLoader、adapter/{ImageCanvasAdapter,ImageDetailAdapter,ImageDetailViewPagerAdapter}
- ui/widget/compose/：AppUiTokens、AppComposeDialogs、LegadoMiuixComponents、ComposeDialogFragment、ComposeDialogAdapters、ComposeViewOwners、LegadoComposeTheme、ComposeChoiceListDialog、BookCoverImage
- ui/widget/components/：AppDropdownMenu、GlassTopAppBar、AppMenuSheet、ThemeSpec、AppModalBottomSheet、AppShapes、InlineTaskBar、SingleChoiceDialog、SettingsToggleRow、SettingsCard、SettingsClickRow、RowIcon
- ui/theme/{LegadoTheme,ThemeSync}；lib/theme/{ComposeUiCorner,UiTypography}；base/ComposeBindingShells
- help/：CoverDisplayResolver、MemoryPressure、CoverThumbnailCache、help/image/{ImageFileNameBuilder,ImageShareHelper,ImageUrlExtractor,ImageUrlCache,ImageSnifferWebView}、help/http/StreamResetRetryInterceptor
- help/config/：ReadTipTemplate、AdvancedTitleConfig、AdvancedTitlePackageManager、AdvancedTitlePackageStorage、BubbleDirectoryTransaction、AtomicTextFileStore
- data/entities/SearchRssArticle；ui/rss/search/{ChangeRssArticleSourceDialog,RssSearchSourceHolder}；ui/main/ai/AiConfigModels；ui/config/AiProviderEditScreen；help/webView/SilentSslWebViewClient

### 自写桥接（3 文件）
- help/config/TopBarConfig.kt shim：m 原版 695 行 import AppCloudStorage（拖 lib/cloud S3/WebDav 栈，D23 顶栏包范围）；shim 覆盖已拉 compose 组件消费面（Icons.navBack/more、STYLE_*、currentSignature/currentConfig、actionContainerSize/actionIconSize、resolvePageBarColorWithAlpha、withOpacity、cornerRadius）。D23 落位时以 m 原版覆盖
- ui/widget/compose/AppSettingPaletteShim.kt：AppSettingComponents.kt（781 行）拖出 LazyColumnScrollbar jitpack 依赖，撤拉改 shim（17 字段 palette + AppManagementPalette + 两 remember）；D10 全量拉入时删除
- help/config/CoverCollectionManager.kt shim：封面收藏是 m DB v94 AI 批次 Room 实体（sceneBookmarks），注册即改 schema；shim 提供 selectionKey/isMixedMode/selectedCollectionCover/isRealCoverPath 四成员（收藏封面恒 null）。随 AI 批次以 m 原版覆盖
- lib/theme/UiTypographyBridge.kt：titleTextColor/toThemeTextColorOrNull 桥接

## 基座桥接（8 处合并）
- BaseActivity + recreateOnThemeChange；BaseService + stopSelfOnTaskRemoved
- AppConst + channelIdAiTask/channelIdCast；NotificationId + DlnaCastService(114)
- PreferKey + videoGestureGuideShown/manageBgAlpha/loadCoverHighQuality/imageArticlePrefetch/advancedTitle×4/updateCacheThreadCount/advancedTitlePackage
- AppConfig + uiFontColor/titleFontColor/manageBgAlphaFraction/uiFontPath/titleFontPath（.orEmpty() 适配工作区 getPrefString 可空签名）/loadCoverHighQuality/imageArticlePrefetch/updateCacheThreadCount
- ColorUtils + MIN_FONT_SURFACE_CONTRAST/contrastOnColor；RssArticleDao + getImage/getListByOriginSort（纯 @Query 增量，m 列裁剪版，无 schema 变化）
- VideoFragment/VideoPlayerActivity 的 Download.start 五参→三参桥接（m 的 DownloadTaskType 在 service/DownloadState.kt 且 import DownloadTaskEntity → DB v94，download-manager-maturity 批次不在计划）

## miuix 依赖接入
- toml：miuix=0.8.8 + ssiv=3.10.0；build.gradle：miuix-android 字面坐标 + Groovy exclude 排除 androidx.compose.{ui,foundation,runtime,animation,material3,material} + org.jetbrains.compose{,.ui,.foundation,.runtime,.animation,.material3,.material} + org.jetbrains.androidx.{navigationevent,lifecycle}（CMB 链把 compose 对齐 1.10.x/minSdk23）；miuix 剩余传递依赖仅 kotlin-stdlib + io.github.kyant0:shapes:1.2.0
- manifest `<uses-sdk tools:overrideLibrary="top.yukonga.miuix.kmp" />`；compose 解析回工作区既有 1.9.0（minSdk 21 兼容）
- subsampling-scale-image-view-androidx:3.10.0（ImagePyramidLoader/ImageCanvasAdapter 依赖）+ lottie:6.6.6（坐标直引，m 同款，AdvancedTitleConfig 消费）

## 资源分区（D9 增量，五批 strings）
- strings_m.xml：video_gesture 系列 + video_layout_mode 系列 + cast/ai 字符串 + image_load_error/refresh/scene_bookmark + advanced_title 系列 + image_back_to_top/image_browse/image_share_failed（约 60 键）；values-zh 同步部分
- layouts：fragment_video、item_video_chapter_vertical、item_image_canvas/detail/detail_viewpager/recycler_view(4)；drawables：ic_danmaku、ic_danmaku_off、bg_overlay_button、ic_fullscreen_exit、ic_reset、ic_rotate_left、ic_rotate_right、bg_image_page_index、bg_rotate_toolbar；anim：dialog_center_enter/exit；styles：AnimDialogCenter + Theme.Legado.ComposeDialog.Center；dimens_m：text_11-15sp + bookshelf_action_button_size/padding + top_bar_regular_action_size；ids：ui_title_typeface_role、btn_reset/btn_rotate_left/btn_rotate_right、tv_page_index、layout_rotate_toolbar；raw：advanced_title_lottie.json

## DB（无 schema 变化日 + 补齐 D8 演练快照）
- 补齐 migration_92_93.sql 与组合 migration_90_93.sql（依据 92/93.json 差异推导）
- 实库演练 v90→v93 PASS、v92→v93 PASS（种子 21/21 表，结构+行数保全一致）

## 与 m 源的偏差（全部为对基座适配）
1. **SceneBookmark stub**：ImageGalleryActivity.addSceneBookmarkFromImage 降级为 toast（scene_bookmark_pending）；SceneBookmark 实体/SceneBookmarkHelper 未拉（m DB v94 + help/ai/AiSceneDescService 依赖，AI 批次落地）；文件备份于 /tmp/opencode/d9-pulled-backup/
2. **OkHttpStreamFetcher 整拉为 m 进化版**：工作区旧 failUrl-hashSet/skipDecode 分支被 m 的 FailEntry-LruCache（TTL 5/10/20min 退避）+ 小内存有界读守卫覆盖，语义为上游演进非工作区专属定制
3. **isRealCoverPath/selectionKey 语义**：无封面收藏特性下恒定（isRealCoverPath 与 m 实现一致；selectionKey=""，selectedCollectionCover=null，isMixedMode=false）
4. **AppConfig.uiFontPath/titleFontPath** 桥接返回 getPrefString(键,"").orEmpty()（m 返回非空默认）；uiFontColor/titleFontColor 返回 ""（字体颜色治理→ThemeConfig.effective*FontColor 属后续批次）；省略 isEInkMode 分支（基座无 E-Ink）
5. **ReadRss 整拉**：连带拉入 m 图片查看子系统（ImageGalleryActivity 家族 + ImageSnifferWebView + ssiv 依赖）；view_book_page.xml 的 advanced_title_lottie id 由 raw 资源 + AdvancedTitlePackageManager R.raw 引用满足，D23 落位时布局到位

## 手测移交（本环境无法覆盖）
1. 视频页手势（亮度/音量/快进）与弹幕开关（ic_danmaku 新资源）
2. DLNA 投屏对话框三面板（cast/ 下新文件首次接 UI）
3. 图片查看页（ssiv 子采样加载、旋转工具条、分享/保存/复制 URL 菜单、场景书签 stub 提示）
4. Compose 对话框家族（AppModalBottomSheet/SingleChoiceDialog/AppMenuSheet）在 miuix 排除 compose 传递组下的视觉回归
5. 高级标题 Lottie（raw 样例包渲染）与字体路径桥接（当前恒走系统字体）

## D10 输入
- ui/widget/compose 组件库剩 AppSettingComponents 等 12 文件（AppSettingPaletteShim 删除点）+ LazyColumnScrollbar 依赖裁决
- miuix minSdk 23 vs 工作区 21 正式裁决（当前 overrideLibrary+排除组方案需在 D10 全量组件下复验）
- VideoSettingsPanel/VideoBookDetailSheet/cast 三面板/LegadoTheme 已提前拉入（今日为 Activity 硬依赖），D10 落点 3 的 dialog_video_settings.xml 删除项待执行
- TopBarConfig shim→m 原版（D23）；CoverCollectionManager shim→m 原版（AI 批次）
