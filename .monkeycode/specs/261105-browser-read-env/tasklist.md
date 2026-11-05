# 阅读 D WebView 阅读页重构 - 实施任务清单

> 基于 design.md，2026-11-05

## T1 偏好与配置
- [x] 1.1 PreferKey 增加 `webViewRead` 常量
- [x] 1.2 AppConfig 增加 `webViewRead` 属性与监听
- [x] 1.3 strings.xml / values-zh/strings.xml 增加开关文案
- [x] 1.4 pref_config_read.xml 增加 `SwitchPreference`
- [x] 1.5 MoreConfigDialog 增加 `webViewRead` 变更事件

## T2 WebReadHtmlBuilder
- [x] 2.1 新建 `WebReadHtmlBuilder.kt`（纯 Kotlin，含 `WebReadStyle` 数据类）
- [x] 2.2 新建 `WebReadHtmlBuilderTest.kt` 覆盖 escape、unwrapUseHtml、buildBody、buildDocument、标题隐藏、样式映射

## T3 WebReadView
- [x] 3.1 新建 `WebReadView.kt`（FrameLayout 包裹 WebViewPool）
- [x] 3.2 实现 `attach` / `detach` / `upContent` / `upStyle` / `pageNext` / `pagePrev` / `getReadPos` / `onDestroy`
- [x] 3.3 实现 `WebReadJsInterface` JS 桥接

## T4 布局接入
- [x] 4.1 activity_book_read.xml 新增 `web_read_view` 节点（默认 gone）

## T5 ReadBookActivity 接入
- [x] 5.1 新增 `applyReadEngine()` 切换显隐
- [x] 5.2 onActivityCreated 调用 `applyReadEngine()`
- [x] 5.3 UP_CONFIG 监听增加 `webViewRead` 分支
- [x] 5.4 keyPage 在 webViewRead 模式下走 `webReadView.pageNext/pagePrev`
- [x] 5.5 upContent 路由到 `webReadView.upContent`
- [x] 5.6 onDestroy 调用 `webReadView.onDestroy()`

## T6 验证
- [x] 6.1 `./gradlew :app:compileAppDebugKotlin` 编译通过
- [x] 6.2 `./gradlew :app:testAppDebugUnitTest --tests "*WebReadHtmlBuilderTest*"` 单元测试通过（10/10 通过）
