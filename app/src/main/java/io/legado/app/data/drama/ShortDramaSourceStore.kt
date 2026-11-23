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
 *
 * PUSH（推送源）不依赖外部源体系：内置 B 站推送入口，并允许用户自定义推送 URL。
 */
object ShortDramaSourceStore {

    private const val KEY_ENABLED = "short_drama_sources"
    private const val KEY_PUSH_URLS = "short_drama_push_urls"

    /** B 站推送视频接口：后端按算法返回推荐流，每次请求内容由后端决定。 */
    const val BILI_FEED_URL = "https://api.bilibili.com/x/web-interface/index/top/feed/rcmd"

    /** 内置 B 站推送源：固定 key，名称可读。 */
    val DEFAULT_PUSH = DramaSourceRef(DramaSourceKind.PUSH, "bili_rcmd", "B站推送")

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
                DramaSourceKind.PUSH -> pushSources()
            }
        }.onFailure {
            AppLog.put("ShortDramaSourceStore.available($kind) failed", it)
        }.getOrDefault(emptyList())
    }

    /** 推送源清单：内置 B 站推送 + 用户自定义 URL。复用 enabledIds 勾选机制，无需新存储结构。 */
    fun pushSources(): List<DramaSourceRef> {
        val custom = appCtx.getPrefStringSet(KEY_PUSH_URLS, mutableSetOf<String>()) ?: emptySet()
        return listOf(DEFAULT_PUSH) + custom.map { DramaSourceRef(DramaSourceKind.PUSH, it, it) }
    }

    /** 取推送源对应的发现 URL：内置源返回 B 站 feed 地址，自定义源直接用其 key 作为 URL。 */
    fun pushUrl(ref: DramaSourceRef): String =
        if (ref.key == DEFAULT_PUSH.key) BILI_FEED_URL else ref.key

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