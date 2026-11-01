package io.legado.app.ui.association

import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import io.legado.app.R
import io.legado.app.lib.theme.AppColorScheme
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx

enum class ImportState { NEW, UPDATE, EXIST }

/** 导入确认行状态徽标：色彩区分新增/更新/已有（批量导入几十个源时的快速辨识） */
fun TextView.showImportState(state: ImportState) {
    val scheme = AppColorScheme.current
    val color = when (state) {
        ImportState.NEW -> scheme.primary
        ImportState.UPDATE -> scheme.tertiary
        ImportState.EXIST -> scheme.onSurfaceVariant
    }
    setText(
        when (state) {
            ImportState.NEW -> R.string.import_status_new
            ImportState.UPDATE -> R.string.import_status_update
            ImportState.EXIST -> R.string.import_status_exist
        }
    )
    setTextColor(color)
    background = GradientDrawable().apply {
        cornerRadius = resources.getDimension(R.dimen.radius_full)
        setColor(ColorUtils.withAlpha(color, 0.12f))
    }
    val h = 8.dpToPx()
    val v = 2.dpToPx()
    setPadding(h, v, h, v)
}
