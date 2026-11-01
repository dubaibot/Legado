package io.legado.app.constant

import androidx.annotation.IntDef

@Suppress("ConstPropertyName")
object EngineType {

    /** JS 引擎 */
    const val js = 0
    /** Python 引擎 */
    const val py = 1

    @Target(AnnotationTarget.VALUE_PARAMETER)
    @Retention(AnnotationRetention.SOURCE)
    @IntDef(js, py)
    annotation class Type
}
