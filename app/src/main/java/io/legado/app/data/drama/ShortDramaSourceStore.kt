package io.legado.app.data.drama

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookSourceType
import io.legado.app.data.appDb
import io.legado.app.data.video.SourceType
import io.legado.app.data.video.VideoSourceRegistry
import io.legado.app.utils.getPrefStringSet
import io.legado.app.utils.putPrefStringSet
import io.legado.app.utils.removePref
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/**
 * 短剧源选择存储。
 *
 * 只保存「哪些源被勾选为短剧源」，源本身的配置仍由各来源体系维护：
 * 书源在书源管理，订阅源在订阅管理，PY/TVBOX 在源管理。
 */
object ShortDramaSourceStore {

    private const val KEY_ENABLED = "short_drama_sources"

    fun enabledIds(): Set<String> = appCtx.getPrefStringSet(KEY_ENABLED, mutableSetOf<String>()) ?: emptySet()

    fun isEnabled(ref: DramaSourceRef): Boolean = ref.id in enabledIds()

    fun setEnabled(ref: DramaSourceRef, enabled: Boolean) {
        val current = enabledIds().toMutableSet()
        if (enabled) current.add(ref.id) else current.remove(ref.id)
        appCtx.putPrefStringSet(KEY_ENABLED, current)
    }

    fun toggle(ref: DramaSourceRef): Boolean {
        val next = !isEnabled(ref)
        setEnabled(ref, next)
        return next
    }

    /** 列出某类别下全部可选源（含未勾选项）。 */
    suspend fun available(kind: DramaSourceKind): List<DramaSourceRef> = withContext(Dispatchers.IO) {
        runCatching {
            when (kind) {
                DramaSourceKind.BOOK -> appDb.bookSourceDao.allEnabledPart
                    .filter { it.bookSourceType == BookSourceType.video }
                    .map { DramaSourceRef(kind, it.bookSourceUrl, it.bookSourceName) }
                DramaSourceKind.RSS -> appDb.rssSourceDao.allEnabled
                    .map { DramaSourceRef(kind, it.sourceUrl, it.sourceName) }
                DramaSourceKind.PY, DramaSourceKind.TVBOX -> VideoSourceRegistry.listSources()
                    .filter { info ->
                        if (kind == DramaSourceKind.PY) info.type == SourceType.PY
                        else info.type != SourceType.PY
                    }
                    .map { DramaSourceRef(kind, it.id, it.name) }
            }
        }.onFailure {
            AppLog.put("ShortDramaSourceStore.available($kind) failed", it)
        }.getOrDefault(emptyList())
    }

    suspend fun availableAll(): Map<DramaSourceKind, List<DramaSourceRef>> =
        DramaSourceKind.entries.associateWith { available(it) }

    /** 已勾选且仍然存在的源。 */
    suspend fun enabled(): List<DramaSourceRef> {
        val all = availableAll().values.flatten()
        val ids = enabledIds()
        return all.filter { it.id in ids }
    }

    fun clear() {
        appCtx.removePref(KEY_ENABLED)
    }
}