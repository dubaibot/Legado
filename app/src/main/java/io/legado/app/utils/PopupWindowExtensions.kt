package io.legado.app.utils

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.widget.PopupWindow
import io.legado.app.lib.theme.popupBackground

fun PopupWindow.applyMd3PopupStyle() {
    setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        elevation = 8f.dpToPx()
    }
    // 运行时取色:XML 里的静态 bg_popup_menu 不随自定义主题,统一在此覆写
    contentView?.let { it.background = it.context.popupBackground }
    isClippingEnabled = true
}
