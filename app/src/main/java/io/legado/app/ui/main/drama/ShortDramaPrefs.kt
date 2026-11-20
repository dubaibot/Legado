package io.legado.app.ui.main.drama

import android.content.Context.MODE_PRIVATE
import androidx.core.content.edit
import splitties.init.appCtx

/** 短剧页播放配置持久化（倍速 / 清屏 / 自动上滑 / 自动续播）。 */
object ShortDramaPrefs {
    private const val PREF = "short_drama"

    const val SPEED = "speed"
    const val CLEAN = "clean_screen"
    const val AUTO_SLIDE = "auto_slide"
    const val AUTO_NEXT = "auto_next"

    private val sp by lazy { appCtx.getSharedPreferences(PREF, MODE_PRIVATE) }

    fun getSpeed(): Float = runCatching { sp.getFloat(SPEED, 1f) }.getOrDefault(1f)

    fun setSpeed(value: Float) = sp.edit { putFloat(SPEED, value) }

    fun getCleanScreen(): Boolean = sp.getBoolean(CLEAN, false)

    fun setCleanScreen(value: Boolean) = sp.edit { putBoolean(CLEAN, value) }

    fun getAutoSlide(): Boolean = runCatching { sp.getBoolean(AUTO_SLIDE, true) }.getOrDefault(true)

    fun setAutoSlide(value: Boolean) = sp.edit { putBoolean(AUTO_SLIDE, value) }

    fun getAutoNext(): Boolean = runCatching { sp.getBoolean(AUTO_NEXT, true) }.getOrDefault(true)

    fun setAutoNext(value: Boolean) = sp.edit { putBoolean(AUTO_NEXT, value) }
}