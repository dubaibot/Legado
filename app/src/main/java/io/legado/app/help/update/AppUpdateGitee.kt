package io.legado.app.help.update

import androidx.annotation.Keep
import io.legado.app.help.coroutine.Coroutine
import kotlinx.coroutines.CoroutineScope

/**
 * 历史 Gitee 更新源入口，现统一走 GitHub Releases（dubaibot/Legado）。
 */
@Keep
@Suppress("unused")
object AppUpdateGitee : AppUpdate.AppUpdateInterface {

    override fun check(
        scope: CoroutineScope,
    ): Coroutine<AppUpdate.UpdateInfo> {
        return AppUpdateGitHub.check(scope)
    }
}
