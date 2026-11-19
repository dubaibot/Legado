# 统一播放器全屏设计

## 实现

- Activity 记录进入时的 `fullscreenHost`、Fragment、屏幕方向和 ViewPager 输入状态，两种页面共用宿主 `startWindowFullscreen`；普通页面布局保持各自实现。
- GSY 全屏动画关闭，Activity 退出请求使用宿主公开的同步 `backToNormal` 包装，在释放、重建或浮窗转移前完成窗口撤除和状态回拷。
- `VideoPlayer.resolveNormalVideoShow` 在 GSY 参数及自定义倍速、比例、静音、弹幕回拷后通知 Activity。退出请求和完成通知分别处理，宿主身份校验保证重复通知幂等。
- `VideoPlayer.cloneParams` 同步待消费的 `seekOnStart`，保证视频准备期间进出全屏时保留历史恢复位置。
- 配置变化仅更新系统栏和主题；全屏入口由用户控件及既有起播策略驱动，避免恢复方向触发重入。
- Fragment 挂起普通控制层、自动隐藏、缓冲更新及触摸，取消待处理手势并恢复长按倍速。`onPrepared` UI 操作遵循挂起状态，历史恢复 seek 使用当前播放器。预加载进度监控继续保留。
- Fragment 页面停用先退出自身全屏；释放使用本地播放器，避免通过 Activity 全屏 ID 查询释放其他页面的播放器。
- `VideoPlay.resolvePlaybackPlayer` 优先解析 Activity 当前宿主，其他上下文沿用播放器自身的 `currentPlayer`，保留浮窗路径。Pipeline 两个起播出口及 VideoPlay 异步 setUp 出口在主线程重新解析实例，继续沿用切集 token 和请求头透传。

## 验证与限制

执行差异检查、静态自审与 Debug APK 构建。设备验收重点覆盖 GSY 11.3.0 同步窗口恢复、Surface 转移、解析期间全屏切集、相邻页回收、浮窗恢复及横竖屏配置回调时序。全屏转移采用即时切换，普通页面保留原控制布局；详情页配置变化自动入全屏改为显式入口，避免退出方向回调重新打开全屏。
