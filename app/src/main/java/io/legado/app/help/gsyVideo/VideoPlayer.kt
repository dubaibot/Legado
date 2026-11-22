package io.legado.app.help.gsyVideo

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.res.ColorStateList
import android.media.MediaScannerConnection
import android.graphics.Bitmap
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import com.shuyu.gsyvideoplayer.listener.LockClickListener
import com.shuyu.gsyvideoplayer.utils.CommonUtil
import com.shuyu.gsyvideoplayer.utils.GSYVideoType
import com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer
import com.shuyu.gsyvideoplayer.video.base.GSYVideoPlayer
import com.shuyu.gsyvideoplayer.video.base.GSYBaseVideoPlayer
import com.shuyu.gsyvideoplayer.video.base.GSYVideoView
import androidx.core.graphics.ColorUtils
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.video.ImageEnhanceController
import io.legado.app.ui.video.VideoPlayerActivity
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.model.VideoPlay
import io.legado.app.utils.postEvent
import master.flame.danmaku.controller.DrawHandler
import master.flame.danmaku.danmaku.loader.IllegalDataException
import master.flame.danmaku.danmaku.loader.android.DanmakuLoaderFactory
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.DanmakuTimer
import master.flame.danmaku.danmaku.model.IDisplayer
import master.flame.danmaku.danmaku.model.android.DanmakuContext
import master.flame.danmaku.danmaku.model.android.SpannedCacheStuffer
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser
import master.flame.danmaku.ui.widget.DanmakuView
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class VideoPlayer: StandardGSYVideoPlayer {
    constructor(context: Context?, fullFlag: Boolean?) : super(context, fullFlag) //必须的,全屏时依靠这个构建知道获取全屏布局
    constructor(context: Context?) : super(context)
    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)

    init {
        // dual-layout W3-fix：音频焦点丢失改为暂停而非释放（GSY 默认 mReleaseWhenLossAudio=true）
        // 铁证：2026-09-04 模拟器日志，起播 10s 后播放器被 releaseVideos() 整体回收
        // （ExoPlayer 网络错误自动重试排程后 2ms 即被杀 → isReleased=true → 黑屏死播放器），
        // 释放链唯一入口 releaseVideos()，静态排查无布局切换/onNewIntent/视图 detach 证据，
        // 最大嫌疑即 GSY 默认"焦点丢失=整体释放"。改为暂停（GSY 官方语义），
        // 并在 onAudioFocusLoss 埋点，后续若复现可从日志确认触发源
        mReleaseWhenLossAudio = false
    }

    override fun onAudioFocusLoss() {
        AppLog.put("GSY VideoPlayer onAudioFocusLoss: policy=pause-no-release")
        super.onAudioFocusLoss()
    }

    private var episodeList: TextView? = null
    private var playbackSpeed: TextView? = null
    /** video-gesture-overhaul: 改为 internal 供 VideoFragment 访问（长按倍速恢复原速） */
    internal var playSpeed: Float = 1.0f
    private var btnNext: ImageView? = null
    private var ivMute: ImageView? = null
    private var prevEpisode: ImageView? = null
    private var introSkip: TextView? = null
    private var outroSkip: TextView? = null
    private var videoQualityBtn: TextView? = null
    private var rightLock: ImageView? = null
    private var rightRotate: ImageView? = null
    private var isMuted = false

    // FongMi 风格浮动 widget
    private var widgetContainer: ViewGroup? = null
    private var widgetSeek: ViewGroup? = null
    private var widgetSeekTime: TextView? = null
    private var widgetBright: ViewGroup? = null
    private var widgetVolume: ViewGroup? = null
    private var widgetSpeed: ImageView? = null
    private var widgetError: View? = null

    /** R3：画面镜像状态（由 VideoPlay 持久化） */
    var isMirrored = VideoPlay.mirrorOnStart
        set(value) {
            field = value
            VideoPlay.mirrorOnStart = value
            applyMirror()
        }

    /** 切换画面镜像 */
    fun toggleMirror() {
        isMirrored = !isMirrored
    }

    /** 对渲染视图应用镜像变换（仅横向翻转） */
    fun applyMirror() {
        val textureView = mTextureView ?: return
        val matrix = Matrix().apply {
            setScale(if (isMirrored) -1f else 1f, 1f, textureView.width / 2f, textureView.height / 2f)
        }
        textureView.setTransform(matrix)
    }

    /** R3：定时关闭（分钟，0=关闭，-1=播放完毕），由 VideoPlay 持久化 */
    var sleepTimerMinutes: Int = VideoPlay.sleepTimerMinutes
        set(value) {
            field = value
            VideoPlay.sleepTimerMinutes = value
        }

    private var sleepTimerRunnable: Runnable? = null

    /** 全屏切换标记：新实例创建时设为 true，onPrepared 中据此跳过跳过标记重置并重建定时器 */
    var isFullscreenSwitch: Boolean = false

    /** 设置定时关闭（minutes: 0=关闭, 15/30/60/90=分钟, -1=播放完毕） */
    fun setSleepTimer(minutes: Int) {
        sleepTimerMinutes = minutes
        if (minutes in 1..9999) {
            VideoPlay.sleepTimerStartTime = System.currentTimeMillis()
        }
        setupSleepTimer()
    }

    /** 清除定时关闭并恢复播放 */
    fun clearSleepTimer() {
        sleepTimerRunnable?.let { removeCallbacks(it) }
        sleepTimerRunnable = null
        if (sleepTimerMinutes != 0) {
            sleepTimerMinutes = 0
        }
        VideoPlay.sleepTimerStartTime = 0L
    }

    private fun setupSleepTimer() {
        sleepTimerRunnable?.let { removeCallbacks(it) }
        sleepTimerRunnable = null
        when (sleepTimerMinutes) {
            -1 -> showOverlayTip(context.getString(R.string.video_sleep_timer_until_end), 2000)
            in 1..9999 -> {
                showOverlayTip(
                    context.getString(R.string.video_sleep_timer_set, sleepTimerMinutes),
                    2000
                )
                // 计算剩余时间：支持全屏切换后新实例重建定时器时继续倒计时
                val totalDuration = sleepTimerMinutes * 60L * 1000L
                val startTime = VideoPlay.sleepTimerStartTime
                val remaining = if (startTime > 0) {
                    val elapsed = System.currentTimeMillis() - startTime
                    (totalDuration - elapsed).coerceAtLeast(0L)
                } else {
                    totalDuration
                }
                if (remaining <= 0) {
                    fireSleepTimer()
                    return
                }
                val runnable = Runnable { fireSleepTimer() }
                sleepTimerRunnable = runnable
                postDelayed(runnable, remaining)
            }
            else -> showOverlayTip(context.getString(R.string.video_sleep_timer_off), 2000)
        }
    }

    private fun fireSleepTimer() {
        sleepTimerRunnable = null
        VideoPlay.sleepTimerStartTime = 0L
        showOverlayTip(context.getString(R.string.video_sleep_timer_finished), 3000)
        getGSYVideoManager().player?.pause()
    }

    /** R3：片头/片尾自动跳过（每段仅触发一次，由 VideoPlay 持久化，全屏切换不丢失） */
    private val introSkipped: Boolean get() = VideoPlay.introSkipped
    private val outroSkipped: Boolean get() = VideoPlay.outroSkipped

    private fun validSkipRange(): Boolean = IntroOutroSkipPolicy.isValid(
        getDuration(), VideoPlay.introSkipSeconds, VideoPlay.outroSkipSeconds,
        VideoPlay.isLiveStream(mOriginUrl.orEmpty())
    )

    private fun updateSkipButtons() {
        introSkip?.apply {
            visibility = VISIBLE
            setTextColor(if (VideoPlay.introSkipSeconds > 0) android.graphics.Color.BLUE else android.graphics.Color.WHITE)
            contentDescription = context.getString(R.string.video_intro_skip_btn) +
                if (VideoPlay.introSkipSeconds > 0) " " + context.getString(
                    R.string.video_seconds_format, VideoPlay.introSkipSeconds
                ) else ""
            text = context.getString(R.string.video_intro_skip_btn)
        }
        outroSkip?.apply {
            visibility = VISIBLE
            setTextColor(if (VideoPlay.outroSkipSeconds > 0) android.graphics.Color.BLUE else android.graphics.Color.WHITE)
            contentDescription = context.getString(R.string.video_outro_skip_btn) +
                if (VideoPlay.outroSkipSeconds > 0) " " + context.getString(
                    R.string.video_seconds_format, VideoPlay.outroSkipSeconds
                ) else ""
            text = context.getString(R.string.video_outro_skip_btn)
        }
    }

    private fun markSkipPosition(intro: Boolean) {
        if (VideoPlay.isLiveStream(mOriginUrl.orEmpty())) {
            showOverlayTip(context.getString(R.string.video_skip_unavailable), 2000)
            return
        }
        val seconds = IntroOutroSkipPolicy.markedSeconds(
            getDuration(), getCurrentPositionWhenPlaying(), intro
        ) ?: run {
            showOverlayTip(context.getString(R.string.video_skip_unavailable), 2000)
            return
        }
        val introSeconds = if (intro) seconds else VideoPlay.introSkipSeconds
        val outroSeconds = if (intro) VideoPlay.outroSkipSeconds else seconds
        if (!IntroOutroSkipPolicy.isValid(getDuration(), introSeconds, outroSeconds, false)) {
            showOverlayTip(context.getString(R.string.video_skip_unavailable), 2000)
            return
        }
        if (intro) {
            VideoPlay.introSkipSeconds = seconds
            VideoPlay.introSkipped = false
        } else {
            VideoPlay.outroSkipSeconds = seconds
            VideoPlay.outroSkipped = false
        }
        updateSkipButtons()
        showOverlayTip(context.getString(R.string.video_seconds_format, seconds), 2000)
    }

    private fun clearSkipPosition(intro: Boolean) {
        if (intro) {
            VideoPlay.introSkipSeconds = 0
            VideoPlay.introSkipped = false
        } else {
            VideoPlay.outroSkipSeconds = 0
            VideoPlay.outroSkipped = false
        }
        updateSkipButtons()
    }

    /** 片头跳过：跳转到片头结束点 */
    fun skipIntro() {
        if (VideoPlay.introSkipSeconds <= 0) {
            showOverlayTip(context.getString(R.string.video_intro_skip_disabled), 2000)
            return
        }
        val target = VideoPlay.introSkipSeconds * 1000L
        if (!mHadPlay || !validSkipRange()) {
            showOverlayTip(context.getString(R.string.video_skip_unavailable), 2000)
            return
        }
        if (getCurrentPositionWhenPlaying() >= target) return
        VideoPlay.introSkipped = true
        seekTo(target)
        showOverlayTip(context.getString(R.string.video_intro_skipped), 2000)
    }

    /** 片尾跳过：跳转到结尾 */
    fun skipOutro() {
        if (VideoPlay.outroSkipSeconds <= 0) {
            showOverlayTip(context.getString(R.string.video_outro_skip_disabled), 2000)
            return
        }
        if (!mHadPlay || outroSkipped || !validSkipRange()) {
            showOverlayTip(context.getString(R.string.video_skip_unavailable), 2000)
            return
        }
        VideoPlay.outroSkipped = true
        showOverlayTip(context.getString(R.string.video_outro_skipped), 2000)
        // Use the same completion callback as natural playback end (including RSS queues).
        onAutoCompletion()
    }

    /** 检查片头/片尾自动跳过（由进度监听器驱动） */
    fun checkIntroOutroSkip() {
        updateSkipButtons()
        refreshPlayerFavoriteState()
        if (mCurrentState != CURRENT_STATE_PLAYING || isChanging || mChangePosition ||
            !validSkipRange()
        ) return
        val current = getCurrentPositionWhenPlaying()
        val duration = getDuration()
        if (current < 0 || current >= duration) return
        // Once the intro has been passed, seeking back must not trigger it again.
        if (!introSkipped && current >= VideoPlay.introSkipSeconds * 1000L) {
            VideoPlay.introSkipped = true
        }
        // 片头跳过：播放位置处于片头区间时，自动跳转到片头结束点
        if (!introSkipped && VideoPlay.introSkipSeconds > 0 &&
            current < VideoPlay.introSkipSeconds * 1000L
        ) {
            skipIntro()
            return
        }
        // 片尾跳过：播放位置进入片尾区间时，自动跳转到结尾
        if (!outroSkipped && VideoPlay.outroSkipSeconds > 0 &&
            current >= duration - VideoPlay.outroSkipSeconds * 1000L
        ) {
            skipOutro()
        }
    }

    /** 新视频加载时重置跳过标记（仅换集时调用，全屏切换不调用） */
    fun resetSkipFlags() {
        VideoPlay.introSkipped = false
        VideoPlay.outroSkipped = false
    }

    /** R3：设置视频清晰度（0=自动, 1=1080p, 2=720p, 3=480p, 4=360p） */
    fun setVideoQuality(quality: Int) {
        VideoPlay.videoQuality = quality
        getGSYVideoManager().setVideoQuality(quality)
        showOverlayTip(
            context.getString(R.string.video_quality_label, qualityLabel(quality)),
            2000
        )
    }

    private fun qualityLabel(quality: Int): String = when (quality) {
        1 -> context.getString(R.string.video_quality_1080p)
        2 -> context.getString(R.string.video_quality_720p)
        3 -> context.getString(R.string.video_quality_480p)
        4 -> context.getString(R.string.video_quality_360p)
        else -> context.getString(R.string.video_quality_auto)
    }

    /** R3：截取当前视频帧并保存到相册 */
    fun screenshot() {
        taskShotPic { bitmap ->
            if (bitmap == null) {
                showOverlayTip(context.getString(R.string.video_screenshot_failed), 2000)
                return@taskShotPic
            }
            post {
                val file = File(
                    context.cacheDir,
                    "video_screenshot_${System.currentTimeMillis()}.png"
                )
                try {
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        saveToAlbumQ(file)
                    } else {
                        MediaScannerConnection.scanFile(
                            context,
                            arrayOf(file.absolutePath),
                            null,
                            MediaScannerConnection.OnScanCompletedListener { _, uri ->
                                showOverlayTip(
                                    if (uri != null)
                                        context.getString(R.string.video_screenshot_saved)
                                    else
                                        context.getString(R.string.video_screenshot_failed),
                                    2000
                                )
                            }
                        )
                    }
                } catch (e: Exception) {
                    AppLog.put("GSY VideoPlayer screenshot failed: ${e.message}")
                    showOverlayTip(context.getString(R.string.video_screenshot_failed), 2000)
                }
            }
        }
    }

    /** API 29+：通过 MediaStore 保存到相册（指定相册目录） */
    private fun saveToAlbumQ(file: File) {
        val name = "阅读视频截图_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/LegadoVideo"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            showOverlayTip(context.getString(R.string.video_screenshot_failed), 2000)
            return
        }
        try {
            resolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: run {
                showOverlayTip(context.getString(R.string.video_screenshot_failed), 2000)
                return
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            showOverlayTip(context.getString(R.string.video_screenshot_saved), 2000)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            AppLog.put("GSY VideoPlayer saveToAlbumQ failed: ${e.message}")
            showOverlayTip(context.getString(R.string.video_screenshot_failed), 2000)
        }
    }

    /** R3 阶段2：供外部读取当前静音状态 */
    val isMutedPublic: Boolean get() = isMuted

    /**
     * 文章模式下阻止 GSY 调用 parent.requestDisallowInterceptTouchEvent(true)
     *
     * GSY 在 ACTION_DOWN 时会调用 parent.requestDisallowInterceptTouchEvent(true)
     * 确保自己能持续接收触摸事件（进度条/亮度/音量）。但这会阻止 ViewPager2
     * 的 onInterceptTouchEvent 被调用，导致垂直滑动无法切换文章。
     *
     * 文章模式下（rssArticles 不为空且 size > 1），我们忽略 GSY 的 true 调用，
     * 让 ViewPager2 始终能通过 onInterceptTouchEvent 检测垂直滑动并拦截。
     * ViewPager2 方向为垂直，水平滑动不会被拦截，GSY 的进度条功能不受影响。
     *
     * 非文章模式下保持原有行为。
     */
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (disallowIntercept &&
            !VideoPlay.rssArticles.isNullOrEmpty() &&
            VideoPlay.rssArticles!!.size > 1
        ) {
            // 文章模式：忽略 GSY 的 requestDisallowInterceptTouchEvent(true)
            return
        }
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    /** R3 阶段2：切换静音状态，更新播放器+图标 */
    fun toggleMute() {
        isMuted = !isMuted
        getGSYVideoManager().player?.setNeedMute(isMuted)
        updateMuteIcon()
    }

    /** R3 阶段5：供设置面板调用画面比例选择 */
    internal fun showRatioDialogPublic() {
        showRatioDialog()
    }

    /** R3 阶段5：供设置面板调用音轨选择 */
    internal fun showAudioTrackDialogPublic() {
        showAudioTrackDialog()
    }

    private var tipView: TextView? = null
    private var isChanging = false
    private var isLongPressSpeed = false
    private var tvRatio: TextView? = null
    private var currentRatioIndex = 0 // 0:默认 1:16:9 2:4:3 3:填充 4:原始

    private var mParser: BaseDanmakuParser? = null //解析器对象
    private var mDanmakuView: DanmakuView? = null //弹幕view
    private var mDanmakuContext: DanmakuContext? = null
    var mToggleDanmaku: TextView? = null //弹幕开关
    private var mDanmakuStartSeekPosition: Long = -1


    override fun getLayoutId(): Int {
        return if (mIfCurrentIsFullscreen)
            R.layout.video_layout_controller_full
        else R.layout.video_layout_controller
    }

    override fun getFullWindowPlayer(): VideoPlayer? {
        val activity = CommonUtil.scanForActivity(context) ?: return null
        val vp = activity.findViewById<View?>(Window.ID_ANDROID_CONTENT) as ViewGroup
        val full = vp.findViewById<View?>(fullId)
        var gsyVideoPlayer: VideoPlayer? = null
        if (full != null) {
            gsyVideoPlayer = full as VideoPlayer
        }
        return gsyVideoPlayer
    }
    override fun getSmallWindowPlayer(): VideoPlayer? = null

    override fun getCurrentPlayer(): VideoPlayer {
        val fullVideoPlayer = getFullWindowPlayer()
        if (fullVideoPlayer != null) {
            return fullVideoPlayer
        }
        val smallVideoPlayer = getSmallWindowPlayer()
        if (smallVideoPlayer != null) {
            return smallVideoPlayer
        }
        return this
    }

    fun getLockCurScreen() = mLockCurScreen

    public override fun lockTouchLogic() {
        super.lockTouchLogic()
        rightLock?.setImageResource(if (mLockCurScreen) R.drawable.lock else R.drawable.unlock)
        syncCenterControls()
    }

    private fun syncCenterControls() {
        updateSkipButtons()
        refreshPlayerFavoriteState()
        val controls = findViewById<ViewGroup>(R.id.center_controls) ?: return
        val show = !mLockCurScreen && mStartButton?.visibility == VISIBLE
        controls.visibility = if (show) VISIBLE else INVISIBLE
        findViewById<View>(R.id.right_control_container)?.visibility =
            if (mIfCurrentIsFullscreen && (show || mLockCurScreen)) VISIBLE else GONE
        rightLock?.visibility = if (mIfCurrentIsFullscreen) VISIBLE else GONE
        rightRotate?.visibility = if (!mIfCurrentIsFullscreen) GONE else if (mLockCurScreen) INVISIBLE else VISIBLE
        for (index in 0 until controls.childCount) {
            val child = controls.getChildAt(index)
            val enabled = !mLockCurScreen
            child.isEnabled = enabled
            child.alpha = if (enabled) 1f else 0f
        }
        refreshEpisodeNavigation()
    }

    fun refreshEpisodeNavigation() {
        val bookEpisodes = VideoPlay.episodes
        val count = if (!bookEpisodes.isNullOrEmpty()) bookEpisodes.size
            else VideoPlay.rssEpisodes?.size ?: 0
        val index = if (!bookEpisodes.isNullOrEmpty()) VideoPlay.chapterInVolumeIndex
            else VideoPlay.rssEpisodeIndex
        episodeList?.visibility = if (count > 0) VISIBLE else GONE
        listOf(prevEpisode to (index > 0 && index < count),
            btnNext to (index >= 0 && index < count - 1)).forEach { (button, available) ->
            button?.apply {
                visibility = if (count > 0) VISIBLE else GONE
                isEnabled = available && !mLockCurScreen
                imageTintList = ColorStateList.valueOf(
                    if (available) android.graphics.Color.WHITE else android.graphics.Color.GRAY
                )
            }
        }
    }

    override fun setViewShowState(view: View?, visibility: Int) {
        super.setViewShowState(view, visibility)
        if (view === mStartButton) syncCenterControls()
    }

    /**
     * F2-Bug3 标志：GSY 原始控件是否应保持可见
     *
     * 根因：GSY 的 changeUiToPlayingBufferingClear()（缓冲结束时调用）和
     * changeUiToClear()（被 changeUiToPlayingClear/changeUiToPauseClear 调用）
     * 会将 mTopContainer/mBottomContainer/mStartButton 设为 INVISIBLE，
     * 覆盖我们通过 setGsyControlVisibility(true) 设置的 VISIBLE 状态。
     *
     * m3u8 流媒体播放时缓冲事件频繁，每次缓冲结束后 GSY 都会隐藏控件，
     * 导致用户点击显示控件后很快又被 GSY 内部逻辑隐藏。
     *
     * 修复：此标志在 setGsyControlVisibility 中设置，override 的 changeUiToClear() 和
     * changeUiToPlayingBufferingClear() 检查此标志，为 true 时在 super 调用后恢复控件可见性。
     */
    private var gsyControlsVisible = false

    /**
     * R3 F2 修复：供 VideoFragment 调用，同步切换 GSY 原始控件显隐
     *
     * F2 触摸事件修复后，VideoFragment 的 OnTouchListener 替换了 GSY 的 OnTouchListener，
     * 导致 GSY 内部的 onClickUiToggle 不再被触发，GSY 原始控件（底部播放条/倍速/静音/设置/
     * 暂停播放按钮）隐藏后无法通过点击重新显示。
     *
     * 此方法供 VideoFragment 在 applyState 中调用，同步切换 GSY 原始控件与我们自定义控件层。
     *
     * 根因修复（F2-Bug2）：直接设置 visibility=VISIBLE 不够——GSY 有内部 UI 状态机，
     * hideAllWidget() 将 UI 状态设为"hide"后，GSY 后续事件（缓冲/seek/播放状态变化）会
     * 通过 changeUiToXXX 覆盖我们的 visibility 设置，导致控件重新隐藏。
     * 正确做法：调用 GSY 标准 changeUiToPlayingShow()/changeUiToPauseShow() 切换 UI 状态。
     *
     * 根因修复（F2-Bug3）：即使调用了 changeUiToPlayingShow()，m3u8 流媒体缓冲结束时
     * GSY 会调用 changeUiToPlayingBufferingClear() 将控件设为 INVISIBLE。
     * 通过 gsyControlsVisible 标志 + override changeUiToClear/changeUiToPlayingBufferingClear
     * 确保 GSY 内部状态变化不覆盖我们的显隐设置。
     *
     * 注意：不启动 GSY 的 dismissControlViewTimer，使用 VideoFragment 的 scheduleAutoHide
     * 统一管理自动隐藏，避免两套计时器不同步。
     *
     * @param show true=显示GSY原始控件，false=隐藏GSY原始控件
     */
    /**
     * FongMi 风格：切换屏幕旋转方向（横屏/竖屏）
     */
    fun toggleScreenRotation() {
        val activity = CommonUtil.scanForActivity(context) ?: return
        val current = activity.requestedOrientation
        val newOrientation = if (current == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        activity.requestedOrientation = newOrientation
    }

    /**
     * FongMi 风格：显示播放信息（当前倍速、清晰度、镜像等状态）
     */
    fun showPlayInfo() {
        val speedLabel = if (playSpeed != 1.0f) "${playSpeed}X" else "1.0X"
        val qualityLabel = qualityLabel(VideoPlay.videoQuality)
        val mirrorLabel = if (isMirrored) "已开启" else "未开启"
        val timerLabel = when (sleepTimerMinutes) {
            0 -> context.getString(R.string.video_sleep_timer_off)
            -1 -> context.getString(R.string.video_sleep_timer_until_end)
            else -> context.getString(R.string.video_sleep_timer_set, sleepTimerMinutes)
        }
        val info = buildString {
            append(context.getString(R.string.video_speed)).append("：").append(speedLabel).append("\n")
            append(context.getString(R.string.video_quality)).append("：").append(qualityLabel).append("\n")
            append(context.getString(R.string.video_mirror)).append("：").append(mirrorLabel).append("\n")
            append(context.getString(R.string.video_sleep_timer)).append("：").append(timerLabel)
        }
        context.alert(title = context.getString(R.string.video_play_info)) {
            setMessage(info)
        }
    }

    fun setGsyControlVisibility(show: Boolean) {
        if (mLockCurScreen) {
            hideAllWidget()
            return
        }
        gsyControlsVisible = show  // F2-Bug3：设置标志供 override 方法检查
        // FongMi 风格：右侧竖排控制栏跟随整体显隐
        findViewById<View?>(R.id.right_control_container)?.visibility =
            if (mIfCurrentIsFullscreen && show) VISIBLE else GONE
        // FongMi 风格：浮动 widget 容器跟随整体显隐
        toggleWidgetContainer(show)
        if (show) {
            // 调用 GSY 标准 UI 切换方法，正确设置 UI 状态并显示所有控件
            // 直接设置 visibility 不够：GSY 的 UI 状态机仍是"hide"状态，
            // 后续事件（缓冲/seek/播放状态变化）会通过 changeUiToXXX 覆盖
            when (mCurrentState) {
                CURRENT_STATE_PAUSE -> {
                    changeUiToPauseShow()
                }
                CURRENT_STATE_PLAYING -> {
                    changeUiToPlayingShow()
                }
                else -> {
                    // 其他状态（PREPARING/NORMAL/ERROR等）直接设置 visibility
                    mTopContainer?.visibility = VISIBLE
                    mBottomContainer?.visibility = VISIBLE
                    mStartButton?.visibility = VISIBLE
                }
            }
            // 取消 GSY 自带的 dismissControlViewTimer
            // changeUiToPlayingShow/changeUiToPauseShow 会启动 dismissControlViewTimer，
            // 我们用自己的 scheduleAutoHide 统一管理自动隐藏
            cancelDismissControlViewTimer()
        } else {
            hideAllWidget()  // 隐藏所有 GSY 原始控件（内部会 cancelDismissControlViewTimer）
        }
        syncCenterControls()
    }

    /**
     * F2-Bug3 修复：override changeUiToPlayingBufferingClear
     *
     * GSY 在 m3u8 流媒体缓冲结束时调用此方法，将 mTopContainer/mBottomContainer/mStartButton
     * 设为 INVISIBLE。当 gsyControlsVisible=true 时（用户要求控件可见），在 super 调用后
     * 恢复这三个容器的可见性，防止 GSY 内部缓冲状态变化覆盖用户的显隐设置。
     *
     * 同帧内先隐藏再恢复，不会产生视觉闪烁。
     */
    override fun changeUiToPlayingBufferingClear() {
        super.changeUiToPlayingBufferingClear()
        if (gsyControlsVisible && !mLockCurScreen) {
            // 缓冲结束后恢复控件可见性，防止 GSY 内部逻辑覆盖用户的"显示控件"设置
            mTopContainer?.visibility = VISIBLE
            mBottomContainer?.visibility = VISIBLE
            mStartButton?.visibility = VISIBLE
            findViewById<View?>(R.id.right_control_container)?.visibility =
                if (mIfCurrentIsFullscreen) VISIBLE else GONE
        }
        syncCenterControls()
    }

    /**
     * F2-Bug3 修复：override changeUiToClear
     *
     * changeUiToClear 被 changeUiToPlayingClear() 和 changeUiToPauseClear() 调用，
     * 将 mTopContainer/mBottomContainer/mStartButton 设为 INVISIBLE。
     * changeUiToPlayingClear/PauseClear 由 GSY 的 dismissControlViewTimer 触发
     * （我们通过 cancelDismissControlViewTimer 取消），但作为安全兜底，
     * 当 gsyControlsVisible=true 时仍恢复控件可见性。
     */
    override fun changeUiToClear() {
        super.changeUiToClear()
        if (gsyControlsVisible && !mLockCurScreen) {
            // 缓冲结束后恢复控件可见性，防止 GSY 内部逻辑覆盖用户的"显示控件"设置
            mTopContainer?.visibility = VISIBLE
            mBottomContainer?.visibility = VISIBLE
            mStartButton?.visibility = VISIBLE
            findViewById<View?>(R.id.right_control_container)?.visibility =
                if (mIfCurrentIsFullscreen) VISIBLE else GONE
        } else {
            // FongMi 风格控件跟随 GSY 控件隐藏
            findViewById<View?>(R.id.right_control_container)?.visibility = GONE
            hideFongMiWidgets()
        }
        syncCenterControls()
    }

    /** 隐藏所有 FongMi 风格浮动 widget（不含 widget_container 本身） */
    private fun hideFongMiWidgets() {
        widgetSeek?.visibility = GONE
        widgetBright?.visibility = GONE
        widgetVolume?.visibility = GONE
        widgetSpeed?.visibility = GONE
    }

    /** 显示/隐藏 widget_container（跟随控制栏显隐） */
    private fun toggleWidgetContainer(show: Boolean) {
        widgetContainer?.visibility = if (show) VISIBLE else GONE
    }

    /**
     * FongMi 风格：单点切换控制栏显隐时同步管理右侧竖排控制栏与浮动 widget 容器。
     * GSY 的 onClickUiToggle 仅管理 top/bottom/start 容器，此处补充 FongMi 控件。
     */
    override fun onClickUiToggle(e: MotionEvent) {
        super.onClickUiToggle(e)
        val controlsVisible = mTopContainer?.visibility == VISIBLE || mBottomContainer?.visibility == VISIBLE
        findViewById<View?>(R.id.right_control_container)?.visibility =
            if (mIfCurrentIsFullscreen && controlsVisible) VISIBLE else GONE
        toggleWidgetContainer(controlsVisible)
        if (!controlsVisible) {
            hideFongMiWidgets()
        }
        syncCenterControls()
    }

    /** Keep GSY's gesture dialog as the single volume indicator. */
    override fun showVolumeDialog(percent: Float, pixels: Int) {
        widgetVolume?.visibility = GONE
        super.showVolumeDialog(percent, pixels)
    }

    override fun dismissVolumeDialog() {
        super.dismissVolumeDialog()
        widgetVolume?.visibility = GONE
    }

    /** Keep GSY's gesture dialog as the single brightness indicator. */
    override fun showBrightnessDialog(percent: Float) {
        widgetBright?.visibility = GONE
        super.showBrightnessDialog(percent)
    }

    override fun dismissBrightnessDialog() {
        super.dismissBrightnessDialog()
        widgetBright?.visibility = GONE
    }

    /** 隐藏所有 widget（GSY 原始 + FongMi 风格） */
    override fun hideAllWidget() {
        super.hideAllWidget()
        hideFongMiWidgets()
        findViewById<View>(R.id.right_control_container)?.visibility = GONE
        syncCenterControls()
    }

    override fun init(context: Context) {
        super.init(context)
        initView()
        // video-player-theme-unify：初始化即应用主题高亮（控制条深色悬浮层保持）
        applyThemeColors()
        post {
            gestureDetector = GestureDetector(
                getContext().applicationContext,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        touchDoubleUp(e)
                        return super.onDoubleTap(e)
                    }

                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        if (!mChangePosition && !mChangeVolume && !mBrightness && mCurrentState != CURRENT_STATE_ERROR
                        ) {
                            onClickUiToggle(e)
                        }
                        return super.onSingleTapConfirmed(e)
                    }

                    override fun onLongPress(e: MotionEvent) {
                        if (mCurrentState == CURRENT_STATE_PLAYING) {
                            val speed = VideoPlay.longPressSpeed / 10.0f
                            setVideoSpeed(speed)
                            showOverlayTip(context.getString(R.string.video_speed_long_press, speed))
                            widgetSpeed?.visibility = VISIBLE
                            isLongPressSpeed = true
                        }
                        super.onLongPress(e)
                    }
                }
            )
            mLockClickListener = LockClickListener { view, lock ->
                VideoPlay.lockCurScreen = lock
            }
        }
    }
    override fun touchSurfaceUp(){
        if (mChangePosition) VideoPlay.introSkipped = true
        if (isLongPressSpeed) {
            isLongPressSpeed = false
            setVideoSpeed(playSpeed)
            showOverlayTip()
            widgetSpeed?.visibility = GONE
            val time = getCurrentPositionWhenPlaying()
            resolveDanmakuStart(time)
        }
        // 手势结束：隐藏 seek widget
        if (!mChangePosition && !mChangeVolume && !mBrightness) {
            widgetSeek?.visibility = GONE
        }
        super.touchSurfaceUp()
    }

    /** FongMi 风格：滑动进度时显示 seek widget */
    override fun touchSurfaceMove(deltaX: Float, deltaY: Float, deltaZ: Float) {
        super.touchSurfaceMove(deltaX, deltaY, deltaZ)
        if (mChangePosition) {
            val targetTime = (mDownPosition + deltaX * mSeekRatio / CommonUtil.getScreenWidth(context)).toLong()
            widgetSeek?.visibility = VISIBLE
            widgetSeekTime?.text = formatTimeText(targetTime)
        }
    }

    /** 格式化时间文本（秒 → mm:ss） */
    private fun formatTimeText(seconds: Long): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return String.format("%02d:%02d", mins, secs)
    }

    /** video-gesture-overhaul: 改为 internal 供 VideoFragment 访问（长按倍速设置） */
    internal fun setVideoSpeed(speed: Float) {
        setSpeed(speed, true)
        playbackSpeed?.text = "${speed}X"
        if (mDanmakuView != null&& !mDanmakuView!!.isPaused) {
            mDanmakuContext!!.setScrollSpeedFactor(VideoPlay.danmakuSpeed - (speed - 1f) / 6f)
            mDanmakuView!!.invalidate()
        }
    }

    private fun updateMuteIcon() {
        ivMute?.setImageResource(
            if (isMuted) R.drawable.ic_volume_off
            else R.drawable.ic_volume_up
        )
    }

    override fun onPrepared() {
        // 全屏是 GSY 反射克隆的同类新实例，cloneParams 会把 mStartAfterPrepared 一并复制。
        // 一旦其为 false，GSYVideoView.onPrepared() 末尾会主动 setStateAndUi(AUTO_COMPLETE)
        // 并 onVideoPause()，表现就是「换集后链接已切换但画面暂停，需要手动点播放」。
        // 自动播放意图明确时在 super 调用前改回 true，让 onPrepared 走 startAfterPrepared 分支。
        if (VideoPlay.autoPlay && !isStartAfterPrepared()) {
            setStartAfterPrepared(true)
        }
        super.onPrepared()
        onPrepareDanmaku(this)
        if (VideoPlay.autoPlay) {
            postDelayed({
                if (currentState != GSYVideoView.CURRENT_STATE_PLAYING &&
                    currentState != GSYVideoView.CURRENT_STATE_PREPAREING
                ) {
                    startAfterPrepared()
                }
            }, 400L)
        }
        // R3：片头/片尾跳过标记仅换集时重置，全屏切换时不重置（isFullscreenSwitch=true）
        if (!isFullscreenSwitch) {
            resetSkipFlags()
        }
        isFullscreenSwitch = false
        // R3：应用用户配置的清晰度限制
        getGSYVideoManager().setVideoQuality(VideoPlay.videoQuality)
        // R3：全屏切换后新实例需重建定时关闭定时器（基于 VideoPlay 持久化状态计算剩余时间）
        if (VideoPlay.sleepTimerMinutes != 0) {
            setupSleepTimer()
        }
        // V-01 修复：换集时跟随用户当前静音状态（isMuted），而非每次强制 muteOnStart
        // 首次播放时 isMuted 已在 initView 初始化为 VideoPlay.muteOnStart
        getGSYVideoManager().player?.setNeedMute(isMuted)
        // video-player-image-enhance A1.2: 播放准备完成后应用画质增强滤镜
        //（K2 实证：GSY 播放状态变化会重置渲染视图，必须在此重新应用，切集/重播均会触发本回调）
        post { ImageEnhanceController.apply(this) }
        // video-player-image-enhance B2.3: 锐化/降噪效果链注入（每次播放管线构建时生效，主线程回调安全）
        // 语义：仅增强开启（效果链非空）时才真正注入；增强关闭时为 no-op，
        // 绝不调用 setVideoEffects(空列表)——那会在 media3 1.10.1 激活 GL VideoGraph 管线（7001 规避）
        post { ImageEnhanceController.applyEffectsToPlayer() }
    }
    private fun onPrepareDanmaku(gsyVideoPlayer: VideoPlayer) {
        val view = gsyVideoPlayer.mDanmakuView
        val par = gsyVideoPlayer.mParser
        val con = gsyVideoPlayer.mDanmakuContext
        if ( view != null && !view.isPrepared && par != null) {
            view.prepare(par, con)
        }
    }

    override fun onVideoPause() {
        super.onVideoPause()
        danmakuOnPause()
    }
    fun danmakuOnPause() {
        if (mDanmakuView != null && mDanmakuView!!.isPrepared) {
            mDanmakuView!!.pause()
        }
    }

    override fun onVideoResume(isResume: Boolean) {
        super.onVideoResume(isResume)
        danmakuOnResume()
    }
    fun danmakuOnResume() {
        if (mDanmakuView != null && mDanmakuView!!.isPrepared && mDanmakuView!!.isPaused) {
            mDanmakuView!!.resume()
        }
    }

    override fun clickStartIcon() {
        super.clickStartIcon()
        if (mCurrentState == CURRENT_STATE_PLAYING) {
            danmakuOnResume()
        } else if (mCurrentState == CURRENT_STATE_PAUSE) {
            danmakuOnPause()
        }
    }

    override fun onAutoCompletion() { //播放完成
        if (mCurrentState == CURRENT_STATE_AUTO_COMPLETE) return
        super.onAutoCompletion()
    }

    override fun onCompletion() {
        super.onCompletion()
        releaseDanmaku(this)
        // R3：定时关闭为"播放完毕"模式时，播放结束提示
        if (sleepTimerMinutes == -1) {
            showOverlayTip(context.getString(R.string.video_sleep_timer_finished), 3000)
        }
    }
    fun releaseDanmaku(gsyVideoPlayer: VideoPlayer) {
        gsyVideoPlayer.mDanmakuView?.release()
    }


    override fun onStartTrackingTouch(seekBar: SeekBar) {
        VideoPlay.introSkipped = true
        super.onStartTrackingTouch(seekBar)
    }

    override fun onSeekComplete() {
        VideoPlay.introSkipped = true
        super.onSeekComplete()
        val time = mProgressBar.progress * getDuration() / 100
        //如果已经初始化过的，直接seek到对于位置
        if (mHadPlay && mDanmakuView != null && mDanmakuView!!.isPrepared) {
            resolveDanmakuSeek(time)
        } else if (mHadPlay && mDanmakuView != null && !mDanmakuView!!.isPrepared) {
            //如果没有初始化过的，记录位置等待
            mDanmakuStartSeekPosition = time
        }
    }


    fun showOverlayTip(message: String? = null, delay: Long = 0) {
        tipView?.apply {
            message?.also {
                text = it
                visibility = VISIBLE
                alpha = 1f
                if (delay > 0) {
                    postDelayed({
                        alpha = 0f
                    }, delay)
                }
            } ?: run {
                visibility = INVISIBLE
                alpha = 0f
            }
        }
    }

    /**
     * video-player-theme-unify：主题化播放器控件
     *
     * 控制条保持深色半透明悬浮层（视频站标准，日夜间可读），仅让进度条/缓冲/缩略图
     * 高亮元素动态取 ThemeStore.accentColor()，主题切换经 init / RECREATE /
     * onConfigurationChanged 时机触发（页面 recreateOnThemeChange=false，静态色不自动刷新）。
     *
     * 说明：GSY 库内置 video_seek_progress 的 progress 层为 clip(shape solid style_color=#005fff)，
     * 该结构下仅靠 progressTintList 不生效（进度条仍显库默认蓝色、不随主题），故此处动态重建
     * LayerDrawable + ClipDrawable 并 setProgressDrawable/setThumb，100% 取主题主色。
     */
    fun applyThemeColors() {
        val accent = ThemeStore.accentColor(context)
        val buffer = ColorUtils.setAlphaComponent(accent, 0x66)
        val secondary = ColorUtils.setAlphaComponent(accent, 0xB3)
        // 主控制条进度条：动态重建 drawable，摆脱 GSY 库硬编码蓝色
        mProgressBar?.let { seekBar ->
            seekBar.progressDrawable = buildVideoSeekDrawable(accent, buffer)
            seekBar.thumb = buildVideoThumbDrawable(accent)
        }
        // 底部迷你进度条：播放=主题色、缓冲=主题色半透明
        mBottomProgressBar?.let { seekBar ->
            seekBar.progressDrawable = buildVideoSeekDrawable(accent, secondary)
        }
        // tint 兜底（部分设备 layer-list 走 tint 路径时生效）
        mProgressBar?.progressTintList = ColorStateList.valueOf(accent)
        mProgressBar?.secondaryProgressTintList = ColorStateList.valueOf(buffer)
        mProgressBar?.thumbTintList = ColorStateList.valueOf(accent)
        mBottomProgressBar?.progressTintList = ColorStateList.valueOf(accent)
    }

    private fun buildVideoSeekDrawable(progressColor: Int, bufferColor: Int): Drawable {
        val radius = 2f * context.resources.displayMetrics.density
        val background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(0x4D999999)
        }
        val bufferClip = ClipDrawable(
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor(bufferColor)
            },
            Gravity.START, ClipDrawable.HORIZONTAL
        )
        val progressClip = ClipDrawable(
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor(progressColor)
            },
            Gravity.START, ClipDrawable.HORIZONTAL
        )
        return LayerDrawable(arrayOf(background, bufferClip, progressClip)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.secondaryProgress)
            setId(2, android.R.id.progress)
        }
    }

    private fun buildVideoThumbDrawable(color: Int): Drawable {
        val size = (14 * context.resources.displayMetrics.density).toInt()
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setSize(size, size)
        }
    }

    private fun initView() {
        isNeedLockFull = true //使用锁定按钮
        setEnlargeImageRes(R.drawable.ic_player_fullscreen_normal)
        setShrinkImageRes(R.drawable.ic_player_fullscreen_exit_bold)
        mFullscreenButton?.setImageResource(
            if (mIfCurrentIsFullscreen) R.drawable.ic_player_fullscreen_exit_bold
            else R.drawable.ic_player_fullscreen_normal
        )
        playbackSpeed = findViewById(R.id.playback_speed)
        playbackSpeed?.text = "${playSpeed.takeIf { it > 0f } ?: 1.0f}X"
        playbackSpeed?.setOnClickListener {
            if (mHadPlay && !isChanging) {
                showSpeedDialog()
            }
        }
        tipView = findViewById(R.id.tip_view)
        if (mIfCurrentIsFullscreen && !VideoPlay.fullBottomProgressBar) {
            mBottomProgressBar = null
        }
        //切换选集
        episodeList = findViewById(R.id.episode_list)
        btnNext = findViewById(R.id.next)
        //静音按钮
        ivMute = findViewById(R.id.iv_mute)
        isMuted = VideoPlay.muteOnStart
        updateMuteIcon()
        applyMirror()
        // R3：片头/片尾自动跳过（进度监听器驱动，仅在播放中触发）
        setGSYVideoProgressListener { _, _, _, _ ->
            getCurrentPlayer().apply {
                checkIntroOutroSkip()
                refreshEpisodeNavigation()
            }
        }
        ivMute?.setOnClickListener {
            isMuted = !isMuted
            getGSYVideoManager().player?.setNeedMute(isMuted)
            updateMuteIcon()
        }
        //画面比例按钮
        tvRatio = findViewById(R.id.tv_ratio)
        updateRatioLabel()
        tvRatio?.setOnClickListener {
            if (mHadPlay && !isChanging) {
                showRatioDialog()
            }
        }
        // R3：上一集
        prevEpisode = findViewById(R.id.prev_episode)
        prevEpisode?.setOnClickListener {
            refreshEpisodeNavigation()
            if (prevEpisode?.isEnabled != true) return@setOnClickListener
            if (!VideoPlay.episodes.isNullOrEmpty()) {
                VideoPlay.upDurIndex(-1, this)
            } else {
                VideoPlay.upRssEpisodeIndex(-1, this)
            }
        }
        // Tapping records the current position for the current video.
        introSkip = findViewById(R.id.intro_skip)
        introSkip?.setOnClickListener { markSkipPosition(true) }
        introSkip?.setOnLongClickListener {
            clearSkipPosition(true)
            true
        }
        outroSkip = findViewById(R.id.outro_skip)
        outroSkip?.setOnClickListener { markSkipPosition(false) }
        outroSkip?.setOnLongClickListener {
            clearSkipPosition(false)
            true
        }
        updateSkipButtons()
        // R3：清晰度切换
        videoQualityBtn = findViewById(R.id.video_quality)
        videoQualityBtn?.setOnClickListener {
            (CommonUtil.scanForActivity(context) as? VideoPlayerActivity)?.showPlayerRoutes(this)
        }
        // Center lock control and side rotation control.
        rightLock = findViewById(R.id.right_lock)
        rightLock?.setOnClickListener {
            lockTouchLogic()
        }
        rightRotate = findViewById(R.id.right_rotate)
        rightRotate?.setOnClickListener { toggleScreenRotation() }
        val activity = CommonUtil.scanForActivity(context) as? VideoPlayerActivity
        findViewById<View>(R.id.player_float)?.setOnClickListener { activity?.startFloatingWindow() }
        findViewById<View>(R.id.player_favorite)?.setOnClickListener {
            activity?.onPlayerFavoriteClicked()
        }
        findViewById<View>(R.id.player_menu)?.setOnClickListener { anchor ->
            activity?.showPlayerMenu(anchor)
        }
        refreshPlayerFavoriteState()
        mToggleDanmaku = findViewById(R.id.toggle_danmaku)
        mToggleDanmaku?.setOnClickListener { toggleDanmakuShow() }
        updateDanmakuButton()
        // Normalize compound icons so all captions share the same baseline.
        listOf(episodeList, introSkip, outroSkip, videoQualityBtn, tvRatio, playbackSpeed)
            .forEach { label ->
                label?.compoundDrawables?.get(1)?.let { icon ->
                    val size = (24 * resources.displayMetrics.density).toInt()
                    icon.setBounds(0, 0, size, size)
                    label.setCompoundDrawables(null, icon, null, null)
                }
            }
        // FongMi 风格：浮动 widget 视图
        widgetContainer = findViewById(R.id.widget_container)
        widgetSeek = findViewById(R.id.widget_seek)
        widgetSeekTime = findViewById(R.id.widget_seek_time)
        widgetBright = findViewById(R.id.widget_bright)
        widgetVolume = findViewById(R.id.widget_volume)
        widgetSpeed = findViewById(R.id.widget_speed)
        widgetError = findViewById(R.id.widget_error)
        refreshEpisodeNavigation()
        episodeList?.setOnClickListener {
            if (mHadPlay && !isChanging) {
                showEpisodeDialog()
            }
        }
        btnNext?.setOnClickListener {
            refreshEpisodeNavigation()
            if (btnNext?.isEnabled != true) return@setOnClickListener
            if (!VideoPlay.episodes.isNullOrEmpty()) {
                VideoPlay.upDurIndex(1, this)
            } else {
                VideoPlay.upRssEpisodeIndex(1, this)
            }
        }
    }


    override fun setUp(url: String?, cacheWithPlay: Boolean, cachePath: File?, title: String?): Boolean {
        initDanmaku()
        // GSY 内部会向 mapHeadData 写入/清理，传入 emptyMap() 等不可变实现会抛
        // UnsupportedOperationException（EmptyMap.clear）；统一转成可变副本再交给播放器。
        mapHeadData = mapHeadData?.toMutableMap() ?: mutableMapOf()
        return super.setUp(url, cacheWithPlay, cachePath, title)
    }

    private fun initDanmaku() {
        val danmakuFile = VideoPlay.danmakuFile
        val danmakuStr = VideoPlay.danmakuStr
        if (danmakuFile == null && danmakuStr.isNullOrBlank()) {
            updateDanmakuButton()
            // LogVar 弹幕服务器异步加载（书源/py/jar/订阅源统一入口，见 VideoPlay.loadDanmakuFromServer）；
            // 未配置服务器/无检索上下文时静默跳过；就绪回调在主线程
            VideoPlay.loadDanmakuFromServer { prepareDanmakuFromServer() }
            return
        }
        setupDanmaku(danmakuFile, danmakuStr)
    }

    /**
     * LogVar 弹幕异步就绪（主线程回调）：
     * 记录当前播放位置（弹幕 prepare 后经既有 prepared 回调 seek 对齐），补建弹幕视图并立即 prepare，
     * 最后广播 VIDEO_DANMAKU_READY 让沉浸式悬浮层同步弹幕开关入口可见性。
     * 视图已存在时（数据先行到达的竞态）维持现状，仅广播刷新入口。
     */
    private fun prepareDanmakuFromServer() {
        if (mDanmakuView == null) {
            mDanmakuStartSeekPosition = if (mHadPlay) getCurrentPositionWhenPlaying() else -1L
            setupDanmaku(null, VideoPlay.danmakuStr)
            onPrepareDanmaku(this)
        }
        postEvent(EventBus.VIDEO_DANMAKU_READY, "")
    }

    /** 弹幕视图/开关/上下文/解析器初始化（initDanmaku 数据分支与 LogVar 异步就绪共用） */
    private fun setupDanmaku(danmakuFile: File?, danmakuStr: String?) {
        mDanmakuView = findViewById<DanmakuView>(R.id.danmaku_view)?.also {
            it.visibility = VISIBLE
        }
        //弹幕开关
        mToggleDanmaku = findViewById<TextView>(R.id.toggle_danmaku)?.also {
            it.visibility = VISIBLE
            it.setOnClickListener { //按钮事件
                VideoPlay.danmakuShow = !VideoPlay.danmakuShow
                resolveDanmakuShow()
            }
        }
        if (mDanmakuView != null) {
            // 设置最大显示行数
            val maxLinesPair = HashMap<Int?, Int?>()
            maxLinesPair[BaseDanmaku.TYPE_SCROLL_RL] = 5 // 滚动弹幕最大显示5行
            // 设置是否禁止重叠
            val overlappingEnablePair = HashMap<Int?, Boolean?>()
            overlappingEnablePair[BaseDanmaku.TYPE_SCROLL_RL] = true
            overlappingEnablePair[BaseDanmaku.TYPE_FIX_TOP] = true
            val danmakuAdapter = DanmakuAdapter(mDanmakuView)
            mDanmakuContext = DanmakuContext.create() //初始化上下文
            mDanmakuContext!!.setDanmakuStyle(IDisplayer.DANMAKU_STYLE_STROKEN, 3f) //设置弹幕类型
                .setDuplicateMergingEnabled(false) //设置是否合并重复弹幕
                .setScrollSpeedFactor(VideoPlay.danmakuSpeed) //设置弹幕滚动速度
                .setScaleTextSize(1.0f) //设置弹幕字体大小
                .setCacheStuffer(SpannedCacheStuffer(), danmakuAdapter) //设置缓存绘制填充器 图文混排使用SpannedCacheStuffer
                .setMaximumLines(maxLinesPair) //设置最大行数
                .preventOverlapping(overlappingEnablePair) //设置是否禁止重叠
            mParser = createParser(danmakuFile, danmakuStr) //加载弹幕资源文件
            mDanmakuView!!.setCallback(object : DrawHandler.Callback {
                override fun updateTimer(timer: DanmakuTimer?) {}
                override fun drawingFinished() {}
                override fun danmakuShown(danmaku: BaseDanmaku?) {}
                override fun prepared() {
                    if (mDanmakuView != null) {
                        mDanmakuView!!.start()
                        if (mDanmakuStartSeekPosition != -1L) {
                            resolveDanmakuSeek(mDanmakuStartSeekPosition)
                            mDanmakuStartSeekPosition = -1L
                        }
                        resolveDanmakuShow()
                    }
                }
            })
            mDanmakuView!!.enableDanmakuDrawingCache(true)
        }
    }

    /**
     * 弹幕是否可用（本集有弹幕数据且视图已初始化）
     *
     * `initDanmaku()` 在无弹幕数据时早退、不创建 `mDanmakuView`，故以该字段作为可用性判据；
     * 沉浸式悬浮层据此决定是否展示弹幕开关入口（与传统模式 GSY 控制条 `toggle_danmaku`
     * 在无数据时置 `GONE` 的口径一致）。
     */
    val isDanmakuAvailable: Boolean
        get() = mDanmakuView != null

    /** 当前是否显示弹幕（与传统模式 `toggle_danmaku` 共用 `VideoPlay.danmakuShow` 状态源） */
    val isDanmakuShowing: Boolean
        get() = VideoPlay.danmakuShow

    /**
     * 翻转弹幕显隐（沉浸式悬浮层开关入口）
     *
     * 与 `toggle_danmaku` 的点击逻辑完全一致：改全局状态 + 走同一 `resolveDanmakuShow()` 落显隐，
     * 保证两种布局模式的弹幕状态互通，不产生第二套状态源。
     */
    fun toggleDanmakuShow() {
        VideoPlay.danmakuShow = !VideoPlay.danmakuShow
        resolveDanmakuShow()
    }

    /**
     * 弹幕偏移
     */
    private fun resolveDanmakuSeek(time: Long) {
        if (mHadPlay && mDanmakuView != null && mDanmakuView!!.isPrepared) {
            mDanmakuView!!.seekTo(time)
        }
    }

    private fun resolveDanmakuStart(time: Long) {
        if (mHadPlay && mDanmakuView != null && mDanmakuView!!.isPrepared) {
            mDanmakuView!!.seekTo(time)
        }
    }


    private fun resolveDanmakuShow() {
        post {
            val danmakuView = mDanmakuView
            if (VideoPlay.danmakuShow) {
                if (danmakuView != null && !danmakuView.isShown) danmakuView.show()
            } else {
                if (danmakuView?.isShown == true) danmakuView.hide()
            }
            updateDanmakuButton()
        }
    }

    private fun updateDanmakuButton() {
        mToggleDanmaku?.apply {
            text = ""
            isSelected = VideoPlay.danmakuShow
            alpha = if (isSelected) 1f else 0.55f
            contentDescription = context.getString(
                if (isSelected) R.string.video_danmaku_off else R.string.video_danmaku_on
            )
        }
    }

    /** Call after asynchronous favorite/bookshelf updates, including while paused. */
    fun refreshPlayerFavoriteState() {
        findViewById<ImageView>(R.id.player_favorite)?.apply {
            val book = VideoPlay.book != null
            val activity = CommonUtil.scanForActivity(context) as? VideoPlayerActivity
            visibility = if (book || activity?.canShelfExternalVideo() == true || VideoPlay.canFavoriteCurrent()) VISIBLE else GONE
            isSelected = activity?.isPlayerFavorite() ?: (if (book) VideoPlay.inBookshelf else VideoPlay.rssStar != null)
            setImageResource(if (isSelected) R.drawable.ic_star else R.drawable.ic_star_border)
            imageTintList = ColorStateList.valueOf(
                if (isSelected) ThemeStore.accentColor(context) else android.graphics.Color.WHITE
            )
            contentDescription = context.getString(
                if (book) R.string.add_to_bookshelf else R.string.favorite
            )
        }
    }

    private fun updateRatioLabel() {
        tvRatio?.setText(when (currentRatioIndex) {
            1 -> R.string.video_ratio_16_9
            2 -> R.string.video_ratio_4_3
            3 -> R.string.video_ratio_fill
            else -> R.string.video_quality_auto
        })
    }

    /**
     * 创建解析器对象，解析输入流
     *
     * @param stream
     * @return
     */
    private fun createParser(danmakuFile: File?, danmakuStr: String?): BaseDanmakuParser {
        val loader = DanmakuLoaderFactory.create(DanmakuLoaderFactory.TAG_BILI)
        try {
            if (danmakuFile != null) {
                loader.load(FileInputStream(danmakuFile))
            } else if (danmakuStr != null) {
                if (danmakuStr.startsWith("http",true)) {
                    loader.load(danmakuStr)
                } else {
                    loader.load(danmakuStr.byteInputStream())
                }
            }
        } catch (e: IllegalDataException) {
            AppLog.put("VideoPlayer: release", e)
        }
        val parser: BaseDanmakuParser = BiliDanmukuParser()
        val dataSource = loader.dataSource
        parser.load(dataSource)
        return parser
    }

    private fun showEpisodeDialog() {
        if (mHadPlay && VideoPlay.episodes.isNullOrEmpty()) {
            val episodes = VideoPlay.rssEpisodes?.takeIf { it.isNotEmpty() } ?: return
            isChanging = true
            context.alert(title = context.getString(R.string.video_section_episodes)) {
                singleChoiceItems(episodes.map { it.title }.toTypedArray(), VideoPlay.rssEpisodeIndex) { dialog, which ->
                    if (which != VideoPlay.rssEpisodeIndex) {
                        VideoPlay.rssEpisodeIndex = which
                        VideoPlay.playRssEpisode(this@VideoPlayer, episodes[which])
                        postEvent(EventBus.UP_VIDEO_INFO, arrayListOf(1))
                    }
                    dialog.dismiss()
                }
                onDismiss { isChanging = false }
            }
            return
        }
        if (!mHadPlay || VideoPlay.episodes.isNullOrEmpty()) {
            return
        }
        isChanging = true
        val choiceEpisodeDialog = ChoiceEpisodeDialog(mContext)
        choiceEpisodeDialog.initList(VideoPlay.episodes!!, object :
            ChoiceEpisodeDialog.OnListItemClickListener {
            override fun onItemClick(position: Int) {
                VideoPlay.chapterInVolumeIndex = position
                VideoPlay.saveRead(0)
                VideoPlay.startPlay(this@VideoPlayer)
            }

            override fun finishDialog() {
                isChanging = false
            }
        }, VideoPlay.chapterInVolumeIndex)
        choiceEpisodeDialog.show()
    }

    private fun showSpeedDialog() {
        if (!mHadPlay) {
            return
        }
        isChanging = true
        val choiceSpeedDialog = ChoiceSpeedDialog(mContext)
        choiceSpeedDialog.initList(listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f, 5.0f, 10.0f, 15.0f).reversed(), object :
            ChoiceSpeedDialog.OnListItemClickListener {
            @SuppressLint("SetTextI18n")
            override fun onItemClick(value: Float) {
                playSpeed = value
                setVideoSpeed(playSpeed)
                if (playSpeed != 1.0f) {
                    showOverlayTip(context.getString(R.string.video_speed_playing, playSpeed), 2000)
                }
            }

            override fun finishDialog() {
                isChanging = false
            }
        }, currentSpeed = playSpeed)
        choiceSpeedDialog.show()
    }

    private fun showRatioDialog() {
        isChanging = true
        val ratios = arrayOf(
            context.getString(R.string.video_ratio_default),
            context.getString(R.string.video_ratio_16_9),
            context.getString(R.string.video_ratio_4_3),
            context.getString(R.string.video_ratio_fill)
        )
        // video-player-theme-unify：改 alert() 扩展走项目主题，替代原生 AlertDialog
        context.alert(title = context.getString(R.string.video_ratio)) {
            singleChoiceItems(ratios, currentRatioIndex) { dialog, which ->
                currentRatioIndex = which
                applyRatio(which)
                updateRatioLabel()
                dialog.dismiss()
            }
            onDismiss { isChanging = false }
        }
    }

    /** R3：清晰度选择对话框 */
    private fun showQualityDialog() {
        isChanging = true
        val qualities = arrayOf(
            context.getString(R.string.video_quality_auto),
            context.getString(R.string.video_quality_1080p),
            context.getString(R.string.video_quality_720p),
            context.getString(R.string.video_quality_480p),
            context.getString(R.string.video_quality_360p)
        )
        context.alert(title = context.getString(R.string.video_quality)) {
            singleChoiceItems(qualities, VideoPlay.videoQuality.coerceIn(0, 4)) { dialog, which ->
                setVideoQuality(which)
                dialog.dismiss()
            }
            onDismiss { isChanging = false }
        }
    }

    /** R3：定时关闭选择对话框 */
    private fun showSleepTimerDialog() {
        isChanging = true
        val values = intArrayOf(0, 15, 30, 60, 90, -1)
        val options = values.map {
            when (it) {
                0 -> context.getString(R.string.video_sleep_timer_off)
                -1 -> context.getString(R.string.video_sleep_timer_until_end)
                else -> context.getString(R.string.video_sleep_timer_set, it)
            }
        }.toTypedArray()
        val currentIndex = values.indexOf(sleepTimerMinutes).coerceAtLeast(0)
        context.alert(title = context.getString(R.string.video_sleep_timer)) {
            singleChoiceItems(options, currentIndex) { dialog, which ->
                setSleepTimer(values[which])
                dialog.dismiss()
            }
            onDismiss { isChanging = false }
        }
    }

    private fun applyRatio(index: Int) {
        val showType = when (index) {
            1 -> GSYVideoType.SCREEN_TYPE_16_9     // 16:9
            2 -> GSYVideoType.SCREEN_TYPE_4_3      // 4:3
            3 -> GSYVideoType.SCREEN_TYPE_FULL     // 填充
            else -> GSYVideoType.SCREEN_TYPE_DEFAULT  // 默认
        }
        GSYVideoType.setShowType(showType)
        // 触发重新测量以应用新的比例
        mTextureView?.requestLayout()
        val labels = arrayOf(
            context.getString(R.string.video_ratio_default),
            context.getString(R.string.video_ratio_16_9),
            context.getString(R.string.video_ratio_4_3),
            context.getString(R.string.video_ratio_fill)
        )
        showOverlayTip(context.getString(R.string.video_ratio_playing, labels[index]), 2000)
    }

    private fun showAudioTrackDialog() {
        val tracks = getGSYVideoManager().getAudioTracks()
        if (tracks.size <= 1) {
            showOverlayTip(context.getString(R.string.video_no_audio_track), 2000)
            return
        }
        isChanging = true
        val labels = tracks.map { it.second }.toTypedArray()
        // video-player-theme-unify：改 alert() 扩展走项目主题，替代原生 AlertDialog
        context.alert(title = context.getString(R.string.video_audio_track)) {
            items(labels.toList()) { dialog, _, which ->
                val trackIndex = tracks[which].first
                getGSYVideoManager().selectAudioTrack(trackIndex)
                showOverlayTip(
                    context.getString(R.string.video_audio_track_playing, labels[which]),
                    2000
                )
                dialog.dismiss()
            }
            onDismiss { isChanging = false }
        }
    }

    override fun updateStartImage() {
        if (mIfCurrentIsFullscreen) {
            if (mStartButton is ImageView) {
                val imageView = mStartButton as ImageView
                when (mCurrentState) {
                    CURRENT_STATE_PLAYING -> {
                        imageView.setImageResource(R.drawable.ic_pause_24dp)
                    }
                    CURRENT_STATE_ERROR -> {
                        imageView.setImageResource(R.drawable.ic_pause_outline_24dp)
                    }
                    else -> {
                        imageView.setImageResource(R.drawable.ic_play_24dp)
                    }
                }
            }
        } else {
            super.updateStartImage()
        }
    }

    override fun onError(what: Int, extra: Int) {
        super.onError(what, extra)
        VideoPlay.saveRead()
        mSeekOnStart = VideoPlay.durChapterPos.toLong()
    }


    /**
     * 处理播放器在全屏切换时，弹幕显示的逻辑
     * 需要格外注意的是，因为全屏和小屏，是切换了播放器，所以需要同步之间的弹幕状态
     */
    override fun cloneParams(from: GSYBaseVideoPlayer, to: GSYBaseVideoPlayer) {
        super.cloneParams(from, to)
        // Keep pending history restoration when switching windows before preparation completes.
        to.seekOnStart = from.seekOnStart
    }

    override fun startWindowFullscreen(
        context: Context?,
        actionBar: Boolean,
        statusBar: Boolean
    ): VideoPlayer? {
        val gsyBaseVideoPlayer = super.startWindowFullscreen(context, actionBar, statusBar)
        if (gsyBaseVideoPlayer != null) {
            val gsyVideoPlayer = gsyBaseVideoPlayer as VideoPlayer
            gsyVideoPlayer.playSpeed = playSpeed
            gsyVideoPlayer.playbackSpeed?.text = "${playSpeed}X"
            gsyVideoPlayer.currentRatioIndex = currentRatioIndex
            gsyVideoPlayer.updateRatioLabel()
            gsyVideoPlayer.isMuted = isMuted
            gsyVideoPlayer.updateMuteIcon()
            gsyVideoPlayer.refreshPlayerFavoriteState()
            gsyVideoPlayer.refreshEpisodeNavigation()
            // video-player-theme-unify：全屏新建实例同步应用主题高亮
            gsyVideoPlayer.applyThemeColors()
            // 全屏切换标记：新实例 onPrepared 时据此跳过跳过标记重置、重建定时器
            gsyVideoPlayer.isFullscreenSwitch = true
            //对弹幕设置偏移记录
//            gsyVideoPlayer.mDanmakuView = this.mDanmakuView
            gsyVideoPlayer.mDanmakuStartSeekPosition = this.getCurrentPositionWhenPlaying()
            onPrepareDanmaku(gsyVideoPlayer)
        }
        return gsyBaseVideoPlayer
    }

    /**
     * 处理播放器在退出全屏时，弹幕显示的逻辑
     * 需要格外注意的是，因为全屏和小屏，是切换了播放器，所以需要同步之间的弹幕状态
     */
    override fun resolveNormalVideoShow(
        oldF: View?,
        vp: ViewGroup?,
        gsyVideoPlayer: GSYVideoPlayer?
    ) {
        super.resolveNormalVideoShow(oldF, vp, gsyVideoPlayer)
        // video-player-theme-unify：退出全屏后小屏播放器保持主题高亮
        applyThemeColors()
        if (gsyVideoPlayer != null) {
            val videoPlayer = gsyVideoPlayer as VideoPlayer
            playSpeed = videoPlayer.playSpeed
            playbackSpeed?.text = "${playSpeed}X"
            currentRatioIndex = videoPlayer.currentRatioIndex
            updateRatioLabel()
            isMuted = videoPlayer.isMuted
            updateMuteIcon()
            if (mDanmakuView != null && mDanmakuView!!.isPrepared) {
                resolveDanmakuSeek(videoPlayer.getCurrentPositionWhenPlaying())
                resolveDanmakuShow()
                releaseDanmaku(videoPlayer)
            }
        }
        (CommonUtil.scanForActivity(context) as? VideoPlayerActivity)?.onFullscreenRestored(this)
    }

    fun restoreFromWindowFullscreen() {
        if (getFullWindowPlayer() != null) {
            backToNormal()
        }
    }

    override fun onBackFullscreen() {
        val activity = CommonUtil.scanForActivity(context) as? VideoPlayerActivity
        if (activity?.isFullScreen == true) {
            activity.exitFullScreen()
        } else {
            super.onBackFullscreen()
        }
    }

    override fun release() {
        super.release()
        releaseDanmaku(this)
    }

    /**********以下重载GSYVideoPlayer的GSYVideoViewBridge相关实现***********/
    override fun getGSYVideoManager(): ExoVideoManager {
        return VideoPlay.videoManager.apply { initContext(context.applicationContext) }
    }
    public override fun backFromFull(context: Context?): Boolean {
        return VideoPlay.backFromWindowFull(context)
    }
    override fun releaseVideos() {
        VideoPlay.releaseAllVideos()
    }

    override fun getFullId(): Int {
        return ExoVideoManager.FULLSCREEN_ID
    }

    override fun getSmallId(): Int {
        return ExoVideoManager.SMALL_ID
    }
    override fun setDisplay(surface: Surface?) {
        if (surface != null && mTextureView.getShowView() is SurfaceView) {
            val surfaceView = (mTextureView.getShowView() as SurfaceView?)
            gsyVideoManager.setDisplayNew(surfaceView)
        } else if (surface != null) {
            gsyVideoManager.setDisplay(surface)
        } else {
            gsyVideoManager.setDisplayNew(null)
        }
    }
    fun nextUI() { resetProgressAndTime() }


    //播放器转移
    fun setSurfaceToPlay() {
        addTextureView()
        gsyVideoManager.setListener(this)
        checkoutState()
    }

    var needDestroy: Boolean = true
    override fun onSurfaceAvailable(surface: Surface?) {
        super.onSurfaceAvailable(surface)
        // Surface 就绪后应用画面镜像（TextureView 此时已存在）
        applyMirror()
    }
    override fun onSurfaceDestroyed(surface: Surface?): Boolean {
        if (needDestroy) {
            return super.onSurfaceDestroyed(surface)
        } else {
            releaseSurface(surface)
            needDestroy = true
            return true
        }
    }

    fun saveState(): VideoPlayer {
        return this
    }

    fun cloneState(switchVideo: StandardGSYVideoPlayer) {
        cloneParams(switchVideo, this)
    }
}
