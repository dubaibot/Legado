package io.legado.app.help.config

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.legado.app.utils.getBoolean
import io.legado.app.utils.putBoolean
import io.legado.app.utils.putLong
import io.legado.app.utils.putString
import io.legado.app.utils.remove
import splitties.init.appCtx

@Suppress("ConstPropertyName")
object LocalConfig : SharedPreferences
by appCtx.getSharedPreferences("local", Context.MODE_PRIVATE) {

    private const val versionCodeKey = "appVersionCode"

    /**
     * 本地密码,用来对需要备份的敏感信息加密,如 webdav 配置等
     */
    var password: String?
        get() = getString("password", null)
        set(value) {
            if (value != null) {
                putString("password", value)
            } else {
                remove("password")
            }
        }

    var lastBackup: Long
        get() = getLong("lastBackup", 0)
        set(value) {
            putLong("lastBackup", value)
        }

    var privacyPolicyOk: Boolean
        get() = getBoolean("privacyPolicyOk")
        set(value) {
            putBoolean("privacyPolicyOk", value)
        }

    val readHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "readHelpVersion", "firstRead")

    val backupHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "backupHelpVersion", "firstBackup")

    val readMenuHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "readMenuHelpVersion", "firstReadMenu")

    val bookSourcesHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "bookSourceHelpVersion", "firstOpenBookSources")

    val webDavBookHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "webDavBookHelpVersion", "firstOpenWebDavBook")

    val ruleHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "ruleHelpVersion")

    val needUpHttpTTS: Boolean
        get() = !isLastVersion(6, "httpTtsVersion")

    val needUpTxtTocRule: Boolean
        get() = !isLastVersion(3, "txtTocRuleVersion")

    val needUpRssSources: Boolean
        get() = !isLastVersion(6, "rssSourceVersion")

    val needUpDictRule: Boolean
        get() = !isLastVersion(2, "needUpDictRule")

    var versionCode
        get() = getLong(versionCodeKey, 0)
        set(value) {
            edit { putLong(versionCodeKey, value) }
        }
    var lastCheckUpdate: Long
        get() = getLong("lastCheckUpdate", 0)
        set(value) {
            putLong("lastCheckUpdate", value)
        }

    val isFirstOpenApp: Boolean
        get() {
            val value = getBoolean("firstOpen", true)
            if (value) {
                edit { putBoolean("firstOpen", false) }
            }
            return value
        }

    @Suppress("SameParameterValue")
    private fun isLastVersion(
        lastVersion: Int,
        versionKey: String,
        firstOpenKey: String? = null
    ): Boolean {
        var version = getInt(versionKey, 0)
        if (version == 0 && firstOpenKey != null) {
            if (!getBoolean(firstOpenKey, true)) {
                version = 1
            }
        }
        if (version < lastVersion) {
            edit { putInt(versionKey, lastVersion) }
            return false
        }
        return true
    }

    var bookInfoDeleteAlert: Boolean
        get() = getBoolean("bookInfoDeleteAlert", true)
        set(value) {
            putBoolean("bookInfoDeleteAlert", value)
        }

    var deleteBookOriginal: Boolean
        get() = getBoolean("deleteBookOriginal")
        set(value) {
            putBoolean("deleteBookOriginal", value)
        }

    var appCrash: Boolean
        get() = getBoolean("appCrash")
        set(value) {
            putBoolean("appCrash", value)
        }

    // 发现页快捷分组（逗号分隔分组名，顺序即展示顺序）
    var exploreQuickGroups: String
        get() = getString("exploreQuickGroups", "") ?: ""
        set(value) {
            putString("exploreQuickGroups", value)
        }

    // 发现页大分类展示白名单（key=exploreAdjust_{sourceUrl}，逗号分隔分类名，空为全部自动划分候选）
    fun getExploreAdjust(sourceUrl: String): String {
        return getString("exploreAdjust_$sourceUrl", "") ?: ""
    }

    fun putExploreAdjust(sourceUrl: String, value: String) {
        putString("exploreAdjust_$sourceUrl", value)
    }

    // 发现新版书签显示名（key=exploreSourceName_{sourceUrl}，空为书源名）
    fun getExploreSourceName(sourceUrl: String): String {
        return getString("exploreSourceName_$sourceUrl", "") ?: ""
    }

    fun putExploreSourceName(sourceUrl: String, value: String) {
        if (value.isBlank()) {
            remove("exploreSourceName_$sourceUrl")
        } else {
            putString("exploreSourceName_$sourceUrl", value)
        }
    }

}
