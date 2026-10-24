package io.legado.app.help.config

import android.content.SharedPreferences
import android.os.Build
import io.legado.app.BuildConfig
import io.legado.app.constant.AppConst
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.utils.GSON
import io.legado.app.utils.canvasrecorder.CanvasRecorderFactory
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefLong
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isNightMode
import io.legado.app.utils.parseIpsFromString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefLong
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import io.legado.app.utils.sysConfiguration
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import java.net.InetAddress
import io.legado.app.lib.theme.ThemeRuntimeKeys
import io.legado.app.model.debug.DebugCategory
import io.legado.app.ui.main.ai.AiProviderConfig
import io.legado.app.ui.main.ai.AiModelConfig

@Suppress("MemberVisibilityCanBePrivate", "ConstPropertyName")
object AppConfig : SharedPreferences.OnSharedPreferenceChangeListener {
    val isCronet = appCtx.getPrefBoolean(PreferKey.cronet)
    var useAntiAlias = appCtx.getPrefBoolean(PreferKey.antiAlias)
    var userAgent: String = getPrefUserAgent()
    var customHosts = appCtx.getPrefString(PreferKey.customHosts)
    var editTheme = appCtx.getPrefInt(PreferKey.editTheme, 0)
    var editThemeDark = appCtx.getPrefInt(PreferKey.editThemeDark, 0)
    var editTemeAuto = appCtx.getPrefBoolean(PreferKey.editTemeAuto)
    var isEInkMode = appCtx.getPrefString(PreferKey.themeMode) == "3"
    var clickActionTL = appCtx.getPrefInt(PreferKey.clickActionTL, 2)
    var clickActionTC = appCtx.getPrefInt(PreferKey.clickActionTC, 2)
    var clickActionTR = appCtx.getPrefInt(PreferKey.clickActionTR, 1)
    var clickActionML = appCtx.getPrefInt(PreferKey.clickActionML, 2)
    var clickActionMC = appCtx.getPrefInt(PreferKey.clickActionMC, 0)
    var clickActionMR = appCtx.getPrefInt(PreferKey.clickActionMR, 1)
    var clickActionBL = appCtx.getPrefInt(PreferKey.clickActionBL, 2)
    var clickActionBC = appCtx.getPrefInt(PreferKey.clickActionBC, 1)
    var clickActionBR = appCtx.getPrefInt(PreferKey.clickActionBR, 1)
    var themeMode = appCtx.getPrefString(PreferKey.themeMode, "0")
    var useDefaultCover = appCtx.getPrefBoolean(PreferKey.useDefaultCover, false)
    var optimizeRender = CanvasRecorderFactory.isSupport
            && appCtx.getPrefBoolean(PreferKey.optimizeRender, false)
    var recordLog = appCtx.getPrefBoolean(PreferKey.recordLog)
    var editFontScale = appCtx.getPrefInt(PreferKey.editFontScale, 16)
    var editNonPrintable = appCtx.getPrefInt(PreferKey.editNonPrintable, 0)
    var editAutoWrap = appCtx.getPrefBoolean(PreferKey.editAutoWrap, true)
    var editAutoComplete = appCtx.getPrefBoolean(PreferKey.editAutoComplete, true)
    var showBoardLine = appCtx.getPrefInt(PreferKey.showBoardLine, 1)
    var adaptSpecialStyle = appCtx.getPrefBoolean(PreferKey.adaptSpecialStyle, true)

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PreferKey.editFontScale -> editFontScale = appCtx.getPrefInt(PreferKey.editFontScale, 16)
            PreferKey.editNonPrintable -> editNonPrintable = appCtx.getPrefInt(PreferKey.editNonPrintable, 0)
            PreferKey.editAutoWrap -> editAutoWrap = appCtx.getPrefBoolean(PreferKey.editAutoWrap, true)
            PreferKey.editAutoComplete -> editAutoComplete = appCtx.getPrefBoolean(PreferKey.editAutoComplete, true)
            PreferKey.showBoardLine -> showBoardLine = appCtx.getPrefInt(PreferKey.showBoardLine, 1)
            PreferKey.adaptSpecialStyle -> adaptSpecialStyle = appCtx.getPrefBoolean(PreferKey.adaptSpecialStyle, true)

            PreferKey.themeMode -> {
                themeMode = appCtx.getPrefString(PreferKey.themeMode, "0")
                isEInkMode = themeMode == "3"
            }

            PreferKey.clickActionTL -> clickActionTL =
                appCtx.getPrefInt(PreferKey.clickActionTL, 2)

            PreferKey.clickActionTC -> clickActionTC =
                appCtx.getPrefInt(PreferKey.clickActionTC, 2)

            PreferKey.clickActionTR -> clickActionTR =
                appCtx.getPrefInt(PreferKey.clickActionTR, 1)

            PreferKey.clickActionML -> clickActionML =
                appCtx.getPrefInt(PreferKey.clickActionML, 2)

            PreferKey.clickActionMC -> clickActionMC =
                appCtx.getPrefInt(PreferKey.clickActionMC, 0)

            PreferKey.clickActionMR -> clickActionMR =
                appCtx.getPrefInt(PreferKey.clickActionMR, 1)

            PreferKey.clickActionBL -> clickActionBL =
                appCtx.getPrefInt(PreferKey.clickActionBL, 2)

            PreferKey.clickActionBC -> clickActionBC =
                appCtx.getPrefInt(PreferKey.clickActionBC, 1)

            PreferKey.clickActionBR -> clickActionBR =
                appCtx.getPrefInt(PreferKey.clickActionBR, 1)

            PreferKey.readBodyToLh -> ReadBookConfig.readBodyToLh =
                appCtx.getPrefBoolean(PreferKey.readBodyToLh, true)

            PreferKey.useZhLayout -> ReadBookConfig.useZhLayout =
                appCtx.getPrefBoolean(PreferKey.useZhLayout)

            PreferKey.userAgent -> userAgent = getPrefUserAgent()

            PreferKey.customHosts -> {
                customHosts = appCtx.getPrefString(PreferKey.customHosts)
                _hostMap = null
                _addressCache = null
            }

            PreferKey.editTheme -> editTheme = appCtx.getPrefInt(PreferKey.editTheme, 0)

            PreferKey.editThemeDark -> editThemeDark = appCtx.getPrefInt(PreferKey.editThemeDark, 0)

            PreferKey.editTemeAuto -> editTemeAuto = appCtx.getPrefBoolean(PreferKey.editTemeAuto)

            PreferKey.antiAlias -> useAntiAlias = appCtx.getPrefBoolean(PreferKey.antiAlias)

            PreferKey.useDefaultCover -> useDefaultCover =
                appCtx.getPrefBoolean(PreferKey.useDefaultCover, false)

            PreferKey.optimizeRender -> optimizeRender = CanvasRecorderFactory.isSupport
                    && appCtx.getPrefBoolean(PreferKey.optimizeRender, false)

            PreferKey.recordLog -> recordLog = appCtx.getPrefBoolean(PreferKey.recordLog)

        }
    }

    //dns配置
    private var _hostMap: Map<String, Any?>? = null
    val hostMap: Map<String, Any?>
        get() = _hostMap ?: run {
            val cache = GSON.fromJsonObject<Map<String, Any?>>(customHosts).getOrNull() ?: emptyMap()
            _hostMap = cache
            cache
        }
    private var _addressCache: Map<String, List<InetAddress>>? = null
    val addressCache: Map<String, List<InetAddress>>
        get() = _addressCache ?: run {
            val cache = hostMap.mapNotNull { (host, ipValue) ->
                val addresses = when (ipValue) {
                    is String -> ipValue.parseIpsFromString()
                    is List<*> -> ipValue.parseIpsFromList()
                    else -> null
                }
                addresses?.let { host to it }
            }.toMap()
            _addressCache = cache
            cache
        }
    private fun List<*>.parseIpsFromList(): List<InetAddress> =
        mapNotNull { element ->
            (element as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?.runCatching { InetAddress.getByName(this) }
                ?.getOrNull()
        }

    var isNightTheme: Boolean
        get() = when (themeMode) {
            "1" -> false
            "2" -> true
            "3" -> false
            else -> sysConfiguration.isNightMode
        }
        set(value) {
            if (isNightTheme != value) {
                if (value) {
                    appCtx.putPrefString(PreferKey.themeMode, "2")
                } else {
                    appCtx.putPrefString(PreferKey.themeMode, "1")
                }
            }
        }
    var showBookname: Int
        get() = appCtx.getPrefInt(PreferKey.showBooknameLayout, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.showBooknameLayout, value)
        }
    var bookshelfMargin: Int
        get() = appCtx.getPrefInt(PreferKey.bookshelfMargin, 12)
        set(value) {
            appCtx.putPrefInt(PreferKey.bookshelfMargin, value)
        }

    var showUnread: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showUnread, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.showUnread, value)
        }

    var showLastUpdateTime: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showLastUpdateTime, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.showLastUpdateTime, value)
        }

    var showWaitUpCount: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showWaitUpCount, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.showWaitUpCount, value)
        }

    var readBrightness: Int
        get() = if (isNightTheme) {
            appCtx.getPrefInt(PreferKey.nightBrightness, 100)
        } else {
            appCtx.getPrefInt(PreferKey.brightness, 100)
        }
        set(value) {
            if (isNightTheme) {
                appCtx.putPrefInt(PreferKey.nightBrightness, value)
            } else {
                appCtx.putPrefInt(PreferKey.brightness, value)
            }
        }

    val textSelectAble: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.textSelectAble, true)

    val isTransparentStatusBar: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.transparentStatusBar, true)

    val immNavigationBar: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.immNavigationBar, true)

    val screenOrientation: String?
        get() = appCtx.getPrefString(PreferKey.screenOrientation)

    var bookGroupStyle: Int
        get() = appCtx.getPrefInt(PreferKey.bookGroupStyle, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.bookGroupStyle, value)
        }

    var bookshelfLayout: Int
        get() = appCtx.getPrefInt(PreferKey.bookshelfLayout, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.bookshelfLayout, value)
        }

    var saveTabPosition: Int
        get() = appCtx.getPrefInt(PreferKey.saveTabPosition, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.saveTabPosition, value)
        }

    var bookExportFileName: String?
        get() = appCtx.getPrefString(PreferKey.bookExportFileName)
        set(value) {
            appCtx.putPrefString(PreferKey.bookExportFileName, value)
        }

    // 保存 自定义导出章节模式 文件名js表达式
    var episodeExportFileName: String?
        get() = appCtx.getPrefString(PreferKey.episodeExportFileName, "")
        set(value) {
            appCtx.putPrefString(PreferKey.episodeExportFileName, value)
        }

    var bookImportFileName: String?
        get() = appCtx.getPrefString(PreferKey.bookImportFileName)
        set(value) {
            appCtx.putPrefString(PreferKey.bookImportFileName, value)
        }

    var backupPath: String?
        get() = appCtx.getPrefString(PreferKey.backupPath)
        set(value) {
            if (value.isNullOrEmpty()) {
                appCtx.removePref(PreferKey.backupPath)
            } else {
                appCtx.putPrefString(PreferKey.backupPath, value)
            }
        }

    // 书籍保存位置
    var defaultBookTreeUri: String?
        get() = appCtx.getPrefString(PreferKey.defaultBookTreeUri)
        set(value) {
            if (value.isNullOrEmpty()) {
                appCtx.removePref(PreferKey.defaultBookTreeUri)
            } else {
                appCtx.putPrefString(PreferKey.defaultBookTreeUri, value)
            }
        }

    val showDiscovery: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showDiscovery, true)

    val showRSS: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showRss, true)

    val autoRefreshBook: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.autoRefresh)

    val onlyUpdateRead: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.onlyUpdateRead)

    var enableReview: Boolean
        get() = BuildConfig.DEBUG && appCtx.getPrefBoolean(PreferKey.enableReview, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.enableReview, value)
        }

    var threadCount: Int
        get() = appCtx.getPrefInt(PreferKey.threadCount, 32)
        set(value) {
            appCtx.putPrefInt(PreferKey.threadCount, value)
        }

    var updateCacheThreadCount: Int
        get() = appCtx.getPrefInt(PreferKey.updateCacheThreadCount, 16)
        set(value) {
            appCtx.putPrefInt(PreferKey.updateCacheThreadCount, value.coerceIn(1, 256))
        }

    var remoteServerId: Long
        get() = appCtx.getPrefLong(PreferKey.remoteServerId)
        set(value) {
            appCtx.putPrefLong(PreferKey.remoteServerId, value)
        }

    // 添加本地选择的目录
    var importBookPath: String?
        get() = appCtx.getPrefString("importBookPath")
        set(value) {
            if (value == null) {
                appCtx.removePref("importBookPath")
            } else {
                appCtx.putPrefString("importBookPath", value)
            }
        }

    var ttsFlowSys: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.ttsFollowSys, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.ttsFollowSys, value)
        }

    val noAnimScrollPage: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.noAnimScrollPage, false)

    const val defaultSpeechRate = 5

    var ttsSpeechRate: Int
        get() = appCtx.getPrefInt(PreferKey.ttsSpeechRate, defaultSpeechRate)
        set(value) {
            appCtx.putPrefInt(PreferKey.ttsSpeechRate, value)
        }

    var ttsTimer: Int
        get() = appCtx.getPrefInt(PreferKey.ttsTimer, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.ttsTimer, value)
        }

    val speechRatePlay: Int get() = if (ttsFlowSys) defaultSpeechRate else ttsSpeechRate

    var chineseConverterType: Int
        get() = appCtx.getPrefInt(PreferKey.chineseConverterType)
        set(value) {
            appCtx.putPrefInt(PreferKey.chineseConverterType, value)
        }

    var systemTypefaces: Int
        get() = appCtx.getPrefInt(PreferKey.systemTypefaces)
        set(value) {
            appCtx.putPrefInt(PreferKey.systemTypefaces, value)
        }

    var elevation: Int
        get() = if (isEInkMode) 0 else appCtx.getPrefInt(
            PreferKey.barElevation,
            AppConst.sysElevation
        )
        set(value) {
            appCtx.putPrefInt(PreferKey.barElevation, value)
        }

    var readUrlInBrowser: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.readUrlOpenInBrowser)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.readUrlOpenInBrowser, value)
        }

    var imageArticlePrefetch: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.imageArticlePrefetch, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.imageArticlePrefetch, value)
        }

    var exportCharset: String
        get() {
            val c = appCtx.getPrefString(PreferKey.exportCharset)
            if (c.isNullOrBlank()) {
                return "UTF-8"
            }
            return c
        }
        set(value) {
            appCtx.putPrefString(PreferKey.exportCharset, value)
        }

    var exportUseReplace: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.exportUseReplace, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.exportUseReplace, value)
        }

    var exportToWebDav: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.exportToWebDav)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.exportToWebDav, value)
        }
    var exportNoChapterName: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.exportNoChapterName)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.exportNoChapterName, value)
        }

    // 是否启用自定义导出 default->false
    var enableCustomExport: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.enableCustomExport, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.enableCustomExport, value)
        }

    var exportType: Int
        get() = appCtx.getPrefInt(PreferKey.exportType)
        set(value) {
            appCtx.putPrefInt(PreferKey.exportType, value)
        }
    var exportPictureFile: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.exportPictureFile, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.exportPictureFile, value)
        }

    var parallelExportBook: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.parallelExportBook, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.parallelExportBook, value)
        }

    var changeSourceCheckAuthor: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.changeSourceCheckAuthor)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.changeSourceCheckAuthor, value)
        }

    var ttsEngine: String?
        get() = appCtx.getPrefString(PreferKey.ttsEngine)
        set(value) {
            appCtx.putPrefString(PreferKey.ttsEngine, value)
        }

    var readAloudScenarioMode: Int
        get() = appCtx.getPrefInt(PreferKey.readAloudScenarioMode, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.readAloudScenarioMode, value.coerceIn(0, 1))
        }

    val readAloudMultiRole: Boolean
        get() = readAloudScenarioMode == 1

    var multiRoleTtsEngineId: String?
        get() = appCtx.getPrefString(PreferKey.multiRoleTtsEngineId)
        set(value) {
            appCtx.putPrefString(PreferKey.multiRoleTtsEngineId, value)
        }

    var defaultNarratorTtsEngineId: String?
        get() = appCtx.getPrefString(PreferKey.defaultNarratorTtsEngineId)
        set(value) {
            appCtx.putPrefString(PreferKey.defaultNarratorTtsEngineId, value)
        }

    var defaultNarratorTtsVoiceId: String?
        get() = appCtx.getPrefString(PreferKey.defaultNarratorTtsVoiceId)
        set(value) {
            appCtx.putPrefString(PreferKey.defaultNarratorTtsVoiceId, value)
        }

    var defaultDialogueMaleTtsVoiceId: String?
        get() = appCtx.getPrefString(PreferKey.defaultDialogueMaleTtsVoiceId)
        set(value) {
            appCtx.putPrefString(PreferKey.defaultDialogueMaleTtsVoiceId, value)
        }

    var defaultDialogueFemaleTtsVoiceId: String?
        get() = appCtx.getPrefString(PreferKey.defaultDialogueFemaleTtsVoiceId)
        set(value) {
            appCtx.putPrefString(PreferKey.defaultDialogueFemaleTtsVoiceId, value)
        }

    var webPort: Int
        get() = appCtx.getPrefInt(PreferKey.webPort, 1122)
        set(value) {
            appCtx.putPrefInt(PreferKey.webPort, value)
        }

    var tocUiUseReplace: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.tocUiUseReplace)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.tocUiUseReplace, value)
        }

    var tocCountWords: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.tocCountWords, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.tocCountWords, value)
        }

    var enableReadRecord: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.enableReadRecord, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.enableReadRecord, value)
        }

    val autoChangeSource: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.autoChangeSource, true)

    var changeSourceLoadInfo: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.changeSourceLoadInfo)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.changeSourceLoadInfo, value)
        }

    var changeSourceLoadToc: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.changeSourceLoadToc)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.changeSourceLoadToc, value)
        }

    var changeSourceLoadWordCount: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.changeSourceLoadWordCount)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.changeSourceLoadWordCount, value)
        }

    var openBookInfoByClickTitle: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.openBookInfoByClickTitle, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.openBookInfoByClickTitle, value)
        }

    var showBookshelfFastScroller: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showBookshelfFastScroller, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.showBookshelfFastScroller, value)
        }

    var contentSelectSpeakMod: Int
        get() = appCtx.getPrefInt(PreferKey.contentSelectSpeakMod)
        set(value) {
            appCtx.putPrefInt(PreferKey.contentSelectSpeakMod, value)
        }

    var batchChangeSourceDelay: Int
        get() = appCtx.getPrefInt(PreferKey.batchChangeSourceDelay)
        set(value) {
            appCtx.putPrefInt(PreferKey.batchChangeSourceDelay, value)
        }

    val importKeepName get() = appCtx.getPrefBoolean(PreferKey.importKeepName)
    val importKeepGroup get() = appCtx.getPrefBoolean(PreferKey.importKeepGroup)
    var importKeepEnable: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.importKeepEnable, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.importKeepEnable, value)
        }
    var importShowComment: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.importShowComment, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.importShowComment, value)
        }

    val clickImgWay: String?
        get() = appCtx.getPrefString(PreferKey.clickImgWay)

    var preDownloadNum
        get() = appCtx.getPrefInt(PreferKey.preDownloadNum, 2)
        set(value) {
            appCtx.putPrefInt(PreferKey.preDownloadNum, value)
        }

    val syncBookProgress get() = appCtx.getPrefBoolean(PreferKey.syncBookProgress, true)

    val syncBookProgressPlus get() = appCtx.getPrefBoolean(PreferKey.syncBookProgressPlus, false)

    val mediaButtonOnExit get() = appCtx.getPrefBoolean("mediaButtonOnExit", true)

    val readAloudByMediaButton
        get() = appCtx.getPrefBoolean(PreferKey.readAloudByMediaButton, false)

    val replaceEnableDefault get() = appCtx.getPrefBoolean(PreferKey.replaceEnableDefault, true)

    val webDavDir get() = appCtx.getPrefString(PreferKey.webDavDir, "legado")

    val webDavDeviceName get() = appCtx.getPrefString(PreferKey.webDavDeviceName, Build.MODEL)

    val recordHeapDump get() = appCtx.getPrefBoolean(PreferKey.recordHeapDump, false)

    val loadCoverOnlyWifi get() = appCtx.getPrefBoolean(PreferKey.loadCoverOnlyWifi, false)

    val showAddToShelfAlert get() = appCtx.getPrefBoolean(PreferKey.showAddToShelfAlert, true)

    val ignoreAudioFocus get() = appCtx.getPrefBoolean(PreferKey.ignoreAudioFocus, false)

    var pauseReadAloudWhilePhoneCalls
        get() = appCtx.getPrefBoolean(PreferKey.pauseReadAloudWhilePhoneCalls, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.pauseReadAloudWhilePhoneCalls, value)

    val onlyLatestBackup get() = appCtx.getPrefBoolean(PreferKey.onlyLatestBackup, true)

    val autoCheckNewBackup get() = appCtx.getPrefBoolean(PreferKey.autoCheckNewBackup, true)

    val defaultHomePage get() = appCtx.getPrefString(PreferKey.defaultHomePage, "bookshelf")

    val updateToVariant get() = appCtx.getPrefString(PreferKey.updateToVariant, "default_version")

    val streamReadAloudAudio get() = appCtx.getPrefBoolean(PreferKey.streamReadAloudAudio, false)

    val doublePageHorizontal: String?
        get() = appCtx.getPrefString(PreferKey.doublePageHorizontal)

    val progressBarBehavior: String?
        get() = appCtx.getPrefString(PreferKey.progressBarBehavior, "page")

    val keyPageOnLongPress
        get() = appCtx.getPrefBoolean(PreferKey.keyPageOnLongPress, false)

    val volumeKeyPage
        get() = appCtx.getPrefBoolean(PreferKey.volumeKeyPage, true)

    val volumeKeyPageOnPlay
        get() = appCtx.getPrefBoolean(PreferKey.volumeKeyPageOnPlay, true)

    val mouseWheelPage
        get() = appCtx.getPrefBoolean(PreferKey.mouseWheelPage, true)

    val paddingDisplayCutouts
        get() = appCtx.getPrefBoolean(PreferKey.paddingDisplayCutouts, false)

    var searchScope: String
        get() = appCtx.getPrefString("searchScope") ?: ""
        set(value) {
            appCtx.putPrefString("searchScope", value)
        }

    var searchGroup: String
        get() = appCtx.getPrefString("searchGroup") ?: ""
        set(value) {
            appCtx.putPrefString("searchGroup", value)
        }

    var pageTouchSlop: Int
        get() = appCtx.getPrefInt(PreferKey.pageTouchSlop, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.pageTouchSlop, value)
        }

    var pageTouchClick: Int
        get() = appCtx.getPrefInt(PreferKey.pageTouchClick, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.pageTouchClick, value)
        }

    var bookshelfSort: Int
        get() = appCtx.getPrefInt(PreferKey.bookshelfSort, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.bookshelfSort, value)
        }

    fun getBookSortByGroupId(groupId: Long): Int {
        return appDb.bookGroupDao.getByID(groupId)?.getRealBookSort()
            ?: bookshelfSort
    }

    private fun getPrefUserAgent(): String {
        val ua = appCtx.getPrefString(PreferKey.userAgent)
        if (ua.isNullOrBlank()) {
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + BuildConfig.Cronet_Main_Version + " Safari/537.36"
        }
        return ua
    }

    var bitmapCacheSize: Int
        get() = appCtx.getPrefInt(PreferKey.bitmapCacheSize, 50)
        set(value) {
            appCtx.putPrefInt(PreferKey.bitmapCacheSize, value)
        }

    var imageRetainNum: Int
        get() = appCtx.getPrefInt(PreferKey.imageRetainNum, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.imageRetainNum, value)
        }

    var showReadTitleBarAddition: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showReadTitleAddition, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.showReadTitleAddition, value)
        }
    var readBarStyleFollowPage: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.readBarStyleFollowPage, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.readBarStyleFollowPage, value)
        }

    var sourceEditMaxLine: Int
        get() {
            val maxLine = appCtx.getPrefInt(PreferKey.sourceEditMaxLine, Int.MAX_VALUE)
            if (maxLine < 10) {
                return Int.MAX_VALUE
            }
            return maxLine
        }
        set(value) {
            appCtx.putPrefInt(PreferKey.sourceEditMaxLine, value)
        }

    var audioPlayUseWakeLock: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.audioPlayWakeLock)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.audioPlayWakeLock, value)
        }

    var brightnessVwPos: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.brightnessVwPos)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.brightnessVwPos, value)
        }

    fun detectClickArea() {
        if (clickActionTL * clickActionTC * clickActionTR
            * clickActionML * clickActionMC * clickActionMR
            * clickActionBL * clickActionBC * clickActionBR != 0
        ) {
            appCtx.putPrefInt(PreferKey.clickActionMC, 0)
            appCtx.toastOnUi("当前没有配置菜单区域,自动恢复中间区域为菜单.")
        }
    }

    //跳转到漫画界面不使用富文本模式
    val showMangaUi: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showMangaUi, true)

    //禁用漫画缩放
    var disableMangaScale: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.disableMangaScale, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.disableMangaScale, value)
        }

    var disableMangaPageAnim: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.disableMangaPageAnim, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.disableMangaPageAnim, value)
        }

    //漫画预加载数量
    var mangaPreDownloadNum
        get() = appCtx.getPrefInt(PreferKey.mangaPreDownloadNum, 10)
        set(value) {
            appCtx.putPrefInt(PreferKey.mangaPreDownloadNum, value)
        }

    //点击翻页
    var disableClickScroll
        get() = appCtx.getPrefBoolean(PreferKey.disableClickScroll, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.disableClickScroll, value)
        }

    //漫画滚动速度
    var mangaAutoPageSpeed
        get() = appCtx.getPrefInt(PreferKey.mangaAutoPageSpeed, 3)
        set(value) {
            appCtx.putPrefInt(PreferKey.mangaAutoPageSpeed, value)
        }

    //漫画页脚配置
    var mangaFooterConfig
        get() = appCtx.getPrefString(PreferKey.mangaFooterConfig, "")
        set(value) {
            appCtx.putPrefString(PreferKey.mangaFooterConfig, value)
        }

    //漫画水平滚动
    var enableMangaHorizontalScroll
        get() = appCtx.getPrefBoolean(PreferKey.enableMangaHorizontalScroll, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.enableMangaHorizontalScroll, value)
        }

    var mangaColorFilter
        get() = appCtx.getPrefString(PreferKey.mangaColorFilter, "")
        set(value) {
            appCtx.putPrefString(PreferKey.mangaColorFilter, value)
        }

    //禁用漫画内标题
    var hideMangaTitle
        get() = appCtx.getPrefBoolean(PreferKey.hideMangaTitle, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.hideMangaTitle, value)
        }

    //开启墨水屏模式
    var enableMangaEInk
        get() = appCtx.getPrefBoolean(PreferKey.enableMangaEInk, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.enableMangaEInk, value)
        }

    var mangaEInkThreshold
        get() = appCtx.getPrefInt(PreferKey.mangaEInkThreshold, 150)
        set(value) {
            appCtx.putPrefInt(PreferKey.mangaEInkThreshold, value)
        }

    var disableHorizontalPageSnap
        get() = appCtx.getPrefBoolean(PreferKey.disableHorizontalPageSnap, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.disableHorizontalPageSnap, value)
        }

    var enableMangaGray
        get() = appCtx.getPrefBoolean(PreferKey.enableMangaGray, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.enableMangaGray, value)
        }

    var welcomeImage
        get() = appCtx.getPrefString(PreferKey.welcomeImage)
        set(value) {
            appCtx.putPrefString(PreferKey.welcomeImage, value)
        }

    var welcomeShowText
        get() = appCtx.getPrefBoolean(PreferKey.welcomeShowText, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.welcomeShowText, value)
        }

    var welcomeShowIcon
        get() = appCtx.getPrefBoolean(PreferKey.welcomeShowIcon, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.welcomeShowIcon, value)
        }

    var welcomeImageDark
        get() = appCtx.getPrefString(PreferKey.welcomeImageDark)
        set(value) {
            appCtx.putPrefString(PreferKey.welcomeImageDark, value)
        }

    var welcomeShowTextDark
        get() = appCtx.getPrefBoolean(PreferKey.welcomeShowTextDark, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.welcomeShowTextDark, value)
        }

    var welcomeShowIconDark
        get() = appCtx.getPrefBoolean(PreferKey.welcomeShowIconDark, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.welcomeShowIconDark, value)
        }

    val autoUpdateVariant get() = appCtx.getPrefBoolean("autoUpdateVariant", true)

    // 是否使用新版发现（默认 false = 传统）
    var isNewExplore: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.isNewExplore, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.isNewExplore, value)
        }

fun aiProviderForModel(model: AiModelConfig?): AiProviderConfig? {
        val providerId = model?.providerId ?: return null
        return GSON.fromJsonObject<List<AiProviderConfig>>(appCtx.getPrefString(PreferKey.aiProvidersJson)).getOrDefault(emptyList()).firstOrNull { it.id == providerId }
    }

    var aiSceneDescEnabled: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.aiSceneDescEnabled, true)
        set(value) = appCtx.putPrefBoolean(PreferKey.aiSceneDescEnabled, value)

    var aiSummaryModelConfig: AiModelConfig?
        get() = GSON.fromJsonObject<AiModelConfig>(appCtx.getPrefString(PreferKey.aiSummaryModelConfig)).getOrNull()
        set(value) = appCtx.putPrefString(PreferKey.aiSummaryModelConfig, GSON.toJson(value))

    var autoBackup: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.autoBackup, true)
        set(value) = appCtx.putPrefBoolean(PreferKey.autoBackup, value)

    var autoSwitchS3Container
        get() = appCtx.getPrefBoolean(PreferKey.autoSwitchS3Container, true)
        set(value) = appCtx.putPrefBoolean(PreferKey.autoSwitchS3Container, value)

    // 向前预下载章节数量
    var backwardPreDownloadNum
        get() = appCtx.getPrefInt(PreferKey.backwardPreDownloadNum, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.backwardPreDownloadNum, value)
        }

    var bookCoverShadow: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.bookCoverShadow, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.bookCoverShadow, value)
        }

    var bookLayout: Int
        get() = appCtx.getPrefInt(PreferKey.bookLayout, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.bookLayout, value)
        }

    var bookSourceCacheFirst = appCtx.getPrefBoolean(PreferKey.bookSourceCacheFirst, false)

    // P0-S2/S4 书源安全开关：默认关=现状全局 CacheManager（应急回退通道）；观察日志默认开（灰度观察档数据收集）
    val bookSourceCacheScoped get() = appCtx.getPrefBoolean(PreferKey.bookSourceCacheScoped, false)

    // ===== 以下为当前项目独有配置（archive-ui P1-F 时从原 AppConfig 恢复，避免 archive 版覆盖丢失） =====
    var bookSourceContentBlacklist = appCtx.getPrefString(PreferKey.bookSourceContentBlacklist)

    var bookSourceContentWhitelist = appCtx.getPrefString(PreferKey.bookSourceContentWhitelist)

    // P0-S1/S3 书源安全开关：computed getter 实时读 SP（无设置页时 adb 改 SP 即时生效，避免单例加载期求值镜像假开关）
    val bookSourceFileSandbox get() = appCtx.getPrefBoolean(PreferKey.bookSourceFileSandbox, false)

    var bookshelfGroupTags: Map<Long, List<String>>
        get() {
            val rawMap = GSON.fromJsonObject<Map<String, List<String>>>(
                appCtx.getPrefString(PreferKey.bookshelfGroupTags)
            ).getOrDefault(emptyMap())
            return rawMap.mapNotNull { (key, value) ->
                key.toLongOrNull()?.let { groupId ->
                    groupId to value.map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinct()
                }
            }.filter { it.second.isNotEmpty() }.toMap()
        }
        set(value) {
            val normalized = value
                .mapValues { (_, tags) -> tags.map { it.trim() }.filter { it.isNotBlank() }.distinct() }
                .filterValues { it.isNotEmpty() }
                .mapKeys { it.key.toString() }
            if (normalized.isEmpty()) {
                appCtx.removePref(PreferKey.bookshelfGroupTags)
            } else {
                appCtx.putPrefString(PreferKey.bookshelfGroupTags, GSON.toJson(normalized))
            }
        }

    var bookshelfHiddenTags: Map<Long, Set<String>>
        get() {
            val rawMap = GSON.fromJsonObject<Map<String, List<String>>>(
                appCtx.getPrefString(PreferKey.bookshelfHiddenTags)
            ).getOrDefault(emptyMap())
            return rawMap.mapNotNull { (key, value) ->
                key.toLongOrNull()?.let { groupId ->
                    groupId to value.map { it.trim() }
                        .filter { it.isNotBlank() }
                        .toSet()
                }
            }.toMap()
        }
        set(value) {
            val normalized = value
                .mapValues { (_, tags) -> tags.map { it.trim() }.filter { it.isNotBlank() }.distinct() }
                .filterValues { it.isNotEmpty() }
                .mapKeys { it.key.toString() }
            if (normalized.isEmpty()) {
                appCtx.removePref(PreferKey.bookshelfHiddenTags)
            } else {
                appCtx.putPrefString(PreferKey.bookshelfHiddenTags, GSON.toJson(normalized))
            }
        }

    var bottomBarEffectMode: String
        get() = appCtx.getPrefString(PreferKey.bottomBarEffectMode, "glass")
            ?.takeIf { it in setOf("glass", "frosted", "solid") }
            ?: "glass"
        set(value) {
            appCtx.putPrefString(
                PreferKey.bottomBarEffectMode,
                value.takeIf { it in setOf("glass", "frosted", "solid") } ?: "glass"
            )
        }

    var bottomBarLayoutMode: String
        get() = appCtx.getPrefString(PreferKey.bottomBarLayoutMode, "floating")
            ?.takeIf { it in setOf("floating", "sidebar", "standard") }
            ?: "floating"
        set(value) {
            appCtx.putPrefString(
                PreferKey.bottomBarLayoutMode,
                value.takeIf { it in setOf("floating", "sidebar", "standard") } ?: "floating"
            )
        }

    var bottomBarSidebarGravity: String
        get() = appCtx.getPrefString(PreferKey.bottomBarSidebarGravity, "start")
            ?.takeIf { it in setOf("start", "end") }
            ?: "start"
        set(value) {
            appCtx.putPrefString(
                PreferKey.bottomBarSidebarGravity,
                value.takeIf { it in setOf("start", "end") } ?: "start"
            )
        }

    // 默认 true：底栏悬浮搜索按钮删除后（用户裁决 2026-09-27），顶栏搜索成为「书架 / 我的」
    // 两模式的搜索入口 ⇒ 默认必须可见，否则用户无从搜索（用户仍可在顶栏包里关掉）。

    var cacheConcurrentRate: String?
        get() = appCtx.getPrefString(PreferKey.cacheConcurrentRate)
        set(value) {
            if (value.isNullOrBlank()) {
                appCtx.removePref(PreferKey.cacheConcurrentRate)
            } else {
                appCtx.putPrefString(PreferKey.cacheConcurrentRate, value)
            }
        }

    var debugLogFloatingBall = appCtx.getPrefBoolean(PreferKey.debugLogFloatingBall, false)

    // P0-S1/S3 书源安全开关：computed getter 实时读 SP（无设置页时 adb 改 SP 即时生效，避免单例加载期求值镜像假开关）

    var debugLogOnlyCategories: Set<DebugCategory>
        get() {
            val raw = appCtx.getPrefString(PreferKey.debugLogOnlyCategories) ?: ""
            return DebugLogOnlyConfig.parseCategories(raw)
        }
        set(value) {
            appCtx.putPrefString(PreferKey.debugLogOnlyCategories, DebugLogOnlyConfig.serializeCategories(value))
        }

    var debugLogOnlyEnabled: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.debugLogOnlyEnabled, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.debugLogOnlyEnabled, value)
        }

    /** 被标记为"只进调试界面"的 DebugCategory 集合（持久化为逗号分隔字符串） */

    var dialogAlpha: Int
        get() = appCtx.getPrefInt(ThemeRuntimeKeys.dialogAlpha(isNightTheme), 100).coerceIn(0, 100)
        set(value) {
            appCtx.putPrefInt(ThemeRuntimeKeys.dialogAlpha(isNightTheme), value.coerceIn(0, 100))
        }

    // C6 审计（2026-09-22）：原两个 @Deprecated("Use uiLayoutAlpha") 属性
    // `uiCornerEffectMode`（getter 恒返回 "solid"、setter 仅写死键）与
    // `uiCornerEffectLevel`（转发 uiLayoutAlpha）在全仓（含 modules/）**零调用** ⇒ 删除死件。
    // 保留的常量：`PreferKey.uiCornerEffectLevel` 仍被 ThemeConfig / ThemeUiPalette /
    // ThemeManageActivity / 上方 uiLayoutAlpha 的旧值回落使用；`PreferKey.uiCornerEffectMode`
    // 属偏好键数据契约（旧数据兼容），不随属性一并删。

    // 下拉选择分组开关（默认关闭，仅在分组样式为标签时生效）
    var dropdownSelectGroup: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.dropdownSelectGroup, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.dropdownSelectGroup, value)
        }

    // 显示标签栏开关（默认关闭，开启后在书架分组下方显示二级标签栏）

    var fastScrollerTouchTargetDp: Int
        get() = appCtx.getPrefInt(
            PreferKey.fastScrollerTouchTargetDp,
            DEFAULT_FAST_SCROLLER_TOUCH_TARGET_DP
        ).coerceIn(MIN_FAST_SCROLLER_TOUCH_TARGET_DP, MAX_FAST_SCROLLER_TOUCH_TARGET_DP)
        set(value) {
            appCtx.putPrefInt(
                PreferKey.fastScrollerTouchTargetDp,
                value.coerceIn(MIN_FAST_SCROLLER_TOUCH_TARGET_DP, MAX_FAST_SCROLLER_TOUCH_TARGET_DP)
            )
        }

    var folderLayout: Int
        get() = appCtx.getPrefInt(PreferKey.folderLayout, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.folderLayout, value)
        }

    var forceSoftwareParagraphBubble: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.forceSoftwareParagraphBubble, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.forceSoftwareParagraphBubble, value)
        }

    val immersiveManageBar: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.immersiveManageBar, true)

    /**
     * 管理页背景透明度 fraction（followup F4 v3 最终语义）：0f=不透明（默认，原状），
     * 1f=全透明（透出 decorView 底图/背景）。E-Ink 强制 0f（不透明契约）。
     * 消费点：内容层根/顶栏 backgroundColor.copy(alpha=fraction) 半透明叠加，可透出底图；
     * 禁止预混不透明色（v2 教训：底图透出被消灭）。
     */

    // 简介显示行数（默认2行，范围1-10）
    var introLinesInList: Int
        get() = appCtx.getPrefInt(PreferKey.introLinesInList, 2)
        set(value) {
            appCtx.putPrefInt(PreferKey.introLinesInList, value.coerceIn(1, 10))
        }

    // 下拉选择分组开关（默认关闭，仅在分组样式为标签时生效）

    val isTocPartialLoad: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.tocPartialLoad)

    // 阅读时保留正文原文里的空行（默认关闭，入口在阅读菜单的「设置」面板）
    var keepBlankLine = appCtx.getPrefBoolean(PreferKey.keepBlankLine, false)

    var keyPageAnimSpeed: Int
        get() = appCtx.getPrefInt(PreferKey.keyPageAnimSpeed, 100)
        set(value) {
            appCtx.putPrefInt(PreferKey.keyPageAnimSpeed, value)
        }

    var loadCoverHighQuality = appCtx.getPrefBoolean(PreferKey.loadCoverHighQuality, false)

    val manageBgAlphaFraction: Float
        get() {
            if (isEInkMode) return 0f
            return appCtx.getPrefInt(PreferKey.manageBgAlpha, 0).coerceIn(0, 100) / 100f
        }

    // MD链接用内置浏览器打开，默认关闭
    val mdLinkInnerBrowser: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.mdLinkInnerBrowser, false)

    val navItemOrder: List<String>
        get() {
            val stored = appCtx.getPrefString(PreferKey.navItemOrder)
            if (!stored.isNullOrBlank()) {
                val parsed = stored.split(",").filter { it.isNotBlank() }
                if (parsed.isNotEmpty()) return parsed
            }
            return listOf("bookshelf", "homepage", "explore", "rss", "my")
        }

    val readAloudFloatingUi get() = appCtx.getPrefBoolean(PreferKey.readAloudFloatingUi, false)

    var recordNetworkLog = appCtx.getPrefBoolean(PreferKey.recordNetworkLog, false)

    var recordUrl: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.recordUrl, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.recordUrl, value)
        }

    var rssParseConcurrency: Int
        get() = appCtx.getPrefInt(PreferKey.rssParseConcurrency, 3)
        set(value) {
            appCtx.putPrefInt(PreferKey.rssParseConcurrency, value)
        }

    var rssSearchGroup: String
        get() = appCtx.getPrefString("rssSearchGroup") ?: ""
        set(value) {
            appCtx.putPrefString("rssSearchGroup", value)
        }

    var rssSearchScope: String
        get() = appCtx.getPrefString("rssSearchScope") ?: ""
        set(value) {
            appCtx.putPrefString("rssSearchScope", value)
        }

    var searchThreadCount: Int
        get() = appCtx.getPrefInt(PreferKey.searchThreadCount, 32)
        set(value) {
            appCtx.putPrefInt(PreferKey.searchThreadCount, value.coerceIn(1, 128))
        }

    fun setDefaultHomePage(key: String) {
        appCtx.putPrefString(PreferKey.defaultHomePage, key)
    }

    fun setNavItemOrder(order: List<String>) {
        appCtx.putPrefString(PreferKey.navItemOrder, order.joinToString(","))
    }

    // 书籍外边框开关（默认关闭，仅在列表/紧凑列表视图时生效）
    var showBookBorder: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showBookBorder, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.showBookBorder, value)
        }

    // 简介显示行数（默认2行，范围1-10）

    var showBookshelfReadProgress: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showBookshelfReadProgress, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.showBookshelfReadProgress, value)
        }

    // 显示标签栏开关（默认关闭，开启后在书架分组下方显示二级标签栏）
    var showBookshelfTagBar: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showBookshelfTagBar, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.showBookshelfTagBar, value)
        }

    /**
     * 书架每个分组配置的标签列表。
     * key = groupId, value = 标签名列表
     */

    var showCategoryInfoInList: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showCategoryInfoInList, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.showCategoryInfoInList, value)
        }

    val showHomepage: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showHomepage, true)

    // 调试模式，开启后"我的"页面显示调试工具入口

    var showIntroInList: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showIntroInList, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.showIntroInList, value)
        }

    var showMoreInfoInList: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showMoreInfoInList, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.showMoreInfoInList, value)
        }

    // W2 / REQ-14 / AD-05：视频嗅探赛马化（默认开启；关闭即回落原串行链 ⇒ 零回归回滚点）
    var sniffRaceEnabled: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.sniffRaceEnabled, true)
        set(value) = appCtx.putPrefBoolean(PreferKey.sniffRaceEnabled, value)

    // W7 8.2 / REQ-30：文章级离线预取（默认关闭）。
    // 开启后打开文章即把该文章全部图片逐条 `downloadOnly` 落到 Glide 磁盘缓存，
    // 口径固定为「并发 2 / 单文章 ≤200 张 / 单张失败不阻塞」（见 ImageCanvasViewModel.prefetchArticleImages）。

    var sourceQueryCacheEnabled = appCtx.getPrefBoolean(PreferKey.sourceQueryCacheEnabled, true)
    // log-system-upgrade AD-01: 默认值按包类型区分——debug 测试包默认 true（AI 解析需要详细日志），release 默认 false
    // 用户显式设置后 key 存在，getPrefBoolean 以设置为准（两类包行为一致）

    var sourceRecycleBinEnabled = appCtx.getPrefBoolean(PreferKey.sourceRecycleBinEnabled, false)

    var textBoldMode: Int
        get() = appCtx.getPrefInt(PreferKey.textBoldMode, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.textBoldMode, value)
        }

    /**
     * 书架状态图标样式
     * - 0: 新版样式（封面右上角显示图标）
     * - 1: 经典样式（书名前显示小绿点）
     */

    var titleFontColor: String
        get() = appCtx.getPrefString(ThemeRuntimeKeys.titleFontColor(isNightTheme), "").orEmpty()
        set(value) {
            appCtx.putPrefString(ThemeRuntimeKeys.titleFontColor(isNightTheme), value)
        }

    var titleFontPath: String
        get() = appCtx.getPrefString(ThemeRuntimeKeys.titleFontPath(isNightTheme)).orEmpty()
        set(value) {
            appCtx.putPrefString(ThemeRuntimeKeys.titleFontPath(isNightTheme), value)
        }

    var tocCollapseVolumeName: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.tocCollapseVolumeName)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.tocCollapseVolumeName, value)
        }

    var tocPartialLoadInterval: Int
        get() = appCtx.getPrefInt(PreferKey.tocPartialLoadInterval, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.tocPartialLoadInterval, value)
        }

    var tocShowFullChapterName: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.tocShowFullChapterName)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.tocShowFullChapterName, value)
        }

    var touchPageAnimSpeed: Int
        get() = appCtx.getPrefInt(PreferKey.touchPageAnimSpeed, 300)
        set(value) {
            appCtx.putPrefInt(PreferKey.touchPageAnimSpeed, value)
        }

    /** 按键翻页动画速度(毫秒)，数值越小动画越快 */

    val uiCornerReplyFollow: Boolean
        get() = appCtx.getPrefBoolean(ThemeRuntimeKeys.uiCornerReplyFollow(isNightTheme), false)

    var uiCornerScale: Float
        get() = appCtx.getPrefString(ThemeRuntimeKeys.uiCornerScale(isNightTheme), "1")
            ?.toFloatOrNull()
            ?.coerceIn(0f, 3f)
            ?: 1f
        set(value) {
            appCtx.putPrefString(ThemeRuntimeKeys.uiCornerScale(isNightTheme), value.coerceIn(0f, 3f).toPlainScale())
        }

    val uiCornerSearchFollow: Boolean
        get() = appCtx.getPrefBoolean(ThemeRuntimeKeys.uiCornerSearchFollow(isNightTheme), false)

    var uiFontColor: String
        get() = appCtx.getPrefString(ThemeRuntimeKeys.uiFontColor(isNightTheme), "").orEmpty()
        set(value) {
            appCtx.putPrefString(ThemeRuntimeKeys.uiFontColor(isNightTheme), value)
        }

    var uiFontPath: String
        get() = appCtx.getPrefString(ThemeRuntimeKeys.uiFontPath(isNightTheme)).orEmpty()
        set(value) {
            appCtx.putPrefString(ThemeRuntimeKeys.uiFontPath(isNightTheme), value)
        }

    var uiLayoutAlpha: Int
        get() = appCtx.getPrefInt(
            ThemeRuntimeKeys.uiLayoutAlpha(isNightTheme),
            appCtx.getPrefInt(PreferKey.uiCornerEffectLevel, 100)
        ).coerceIn(0, 100)
        set(value) {
            appCtx.putPrefInt(ThemeRuntimeKeys.uiLayoutAlpha(isNightTheme), value.coerceIn(0, 100))
        }

    var verboseUpdateLog: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.verboseUpdateLog, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.verboseUpdateLog, value)
        }

    const val DEFAULT_FAST_SCROLLER_TOUCH_TARGET_DP = 44

    const val MIN_FAST_SCROLLER_TOUCH_TARGET_DP = 32

    const val MAX_FAST_SCROLLER_TOUCH_TARGET_DP = 60

    private fun Float.toPlainScale(): String {
        return if (this % 1f == 0f) {
            this.toInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.2f", this).trimEnd('0').trimEnd('.')
        }
    }
}
