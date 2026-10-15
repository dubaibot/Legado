package io.legado.app.help.source

import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import io.legado.app.constant.SourceType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.RssSource
import io.legado.app.help.AppCacheManager
import io.legado.app.help.config.SourceConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.AudioPlay
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadManga
import io.legado.app.model.VideoPlay
import io.legado.app.service.VideoPlayService
import io.legado.app.ui.video.VideoPlayerActivity
import io.legado.app.utils.EncoderUtils
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

object SourceHelp {

    private val list18Plus by lazy {
        try {
            return@lazy String(appCtx.assets.open("18PlusList.txt").readBytes())
                .splitNotBlank("\n").map {
                    EncoderUtils.base64Decode(it)
                }.toHashSet()
        } catch (_: Exception) {
            return@lazy emptySet()
        }
    }

    /**
     * 读取书源/订阅源（会话态优先，其次短时缓存，未命中回库）。
     *
     * 多源合并 D6（对齐 m 源 R18）：末段回库查询包进 [SourceQueryCache]（TTL + epoch 失效）；
     * 写源入口会让缓存整体失效，保证「写完立即读到最新」，开关关闭时行为与改造前一致。
     */
    fun getSource(key: String?): BaseSource? {
        key ?: return null
        if (ReadBook.bookSource?.bookSourceUrl == key) {
            return ReadBook.bookSource
        } else if (AudioPlay.bookSource?.bookSourceUrl == key) {
            return AudioPlay.bookSource
        } else if (ReadManga.bookSource?.bookSourceUrl == key) {
            return ReadManga.bookSource
        } else if (VideoPlay.source?.getKey() == key) {
            return VideoPlay.source
        }
        return SourceQueryCache.get("any:$key") {
            appDb.bookSourceDao.getBookSource(key)
                ?: appDb.rssSourceDao.getByKey(key)
        }
    }

    fun getSource(key: String?, @SourceType.Type type: Int): BaseSource? {
        key ?: return null
        return when (type) {
            SourceType.book -> SourceQueryCache.get("book:$key") {
                appDb.bookSourceDao.getBookSource(key)
            }
            SourceType.rss -> SourceQueryCache.get("rss:$key") {
                appDb.rssSourceDao.getByKey(key)
            }
            else -> null
        }
    }

    fun deleteSource(key: String, @SourceType.Type type: Int) {
        when (type) {
            SourceType.book -> deleteBookSource(key)
            SourceType.rss -> deleteRssSource(key)
        }
    }

    fun deleteBookSourceParts(sources: List<BookSourcePart>) {
        appDb.runInTransaction {
            sources.forEach {
                deleteBookSourceInternal(it.bookSourceUrl)
            }
        }
        AppCacheManager.clearSourceVariables()
    }

    fun deleteBookSources(sources: List<BookSource>) {
        appDb.runInTransaction {
            sources.forEach {
                deleteBookSourceInternal(it.bookSourceUrl)
            }
        }
        AppCacheManager.clearSourceVariables()
    }

    private fun deleteBookSourceInternal(key: String) {
        appDb.bookSourceDao.delete(key)
        // 写源入口 ②删除源：使查询缓存整体失效（epoch++）
        SourceQueryCache.invalidate()
        appDb.cacheDao.deleteSourceVariables(key)
        SourceConfig.removeSource(key)
        // 删源联动：清理脚本缓存命名空间（DB+内存+文件三处，失败不阻断删源）
        kotlin.runCatching { BookSourceCacheStore.clear(key) }
    }

    fun deleteBookSource(key: String) {
        deleteBookSourceInternal(key)
        AppCacheManager.clearSourceVariables()
    }

    fun deleteRssSources(sources: List<RssSource>) {
        appDb.runInTransaction {
            sources.forEach {
                deleteRssSourceInternal(it.sourceUrl)
            }
        }
        AppCacheManager.clearSourceVariables()
    }

    private fun deleteRssSourceInternal(key: String) {
        appDb.rssSourceDao.delete(key)
        appDb.rssArticleDao.delete(key)
        // 写源入口 ②删除源（订阅）：使查询缓存整体失效
        SourceQueryCache.invalidate()
        appDb.cacheDao.deleteSourceVariables(key)
        // 删源联动：清理脚本缓存命名空间（RssSource 用 sourceUrl 作 key；前缀清理对未隔离数据无副作用）
        kotlin.runCatching { BookSourceCacheStore.clear(key) }
    }

    fun deleteRssSource(key: String) {
        deleteRssSourceInternal(key)
        AppCacheManager.clearSourceVariables()
    }

    fun enableSource(key: String, @SourceType.Type type: Int, enable: Boolean) {
        when (type) {
            SourceType.book -> appDb.bookSourceDao.enable(key, enable)
            SourceType.rss -> appDb.rssSourceDao.enable(key, enable)
        }
        // 启停属于写源（影响 getSource 命中结果的可用态），一并失效
        SourceQueryCache.invalidate()
    }

    fun insertRssSource(vararg rssSources: RssSource) {
        val rssSourcesGroup = rssSources.groupBy {
            is18Plus(it.sourceUrl)
        }
        rssSourcesGroup[true]?.forEach {
            appCtx.toastOnUi("${it.sourceName}是18+网址,禁止导入.")
        }
        rssSourcesGroup[false]?.let {
            appDb.rssSourceDao.insert(*it.toTypedArray())
            // 写源入口 ①新增/③批量导入（订阅）
            SourceQueryCache.invalidate()
        }
    }

    fun insertBookSource(vararg bookSources: BookSource) {
        val bookSourcesGroup = bookSources.groupBy {
            is18Plus(it.bookSourceUrl)
        }
        bookSourcesGroup[true]?.forEach {
            appCtx.toastOnUi("${it.bookSourceName}是18+网址,禁止导入.")
        }
        bookSourcesGroup[false]?.let {
            appDb.bookSourceDao.insert(*it.toTypedArray())
            // 写源入口 ①新增/③批量导入/⑤内置源自动更新（RuleUpdate 经本函数写入）
            SourceQueryCache.invalidate()
        }
        Coroutine.async {
            adjustSortNumber()
        }
    }

    private fun is18Plus(url: String?): Boolean {
        if (list18Plus.isEmpty()) {
            return false
        }
        url ?: return false
        val baseUrl = NetworkUtils.getBaseUrl(url) ?: return false
        kotlin.runCatching {
            val host = baseUrl.split("//", ".").let {
                if (it.size > 2) "${it[it.lastIndex - 1]}.${it.last()}" else return false
            }
            return list18Plus.contains(host)
        }
        return false
    }

    /**
     * 调整排序序号
     */
    fun adjustSortNumber() {
        if (
            appDb.bookSourceDao.maxOrder > 99999
            || appDb.bookSourceDao.minOrder < -99999
            || appDb.bookSourceDao.hasDuplicateOrder
        ) {
            val sources = appDb.bookSourceDao.allPart
            sources.forEachIndexed { index, bookSource ->
                bookSource.customOrder = index
            }
            appDb.bookSourceDao.upOrder(sources)
        }
    }

    fun openVideoPlayer(source: BaseSource?, url: String, title: String, isFloat: Boolean) {
        if (isFloat) {
            val intent = Intent(appCtx, VideoPlayService::class.java).apply {
                putExtra("videoUrl", url)
                putExtra("videoTitle", title)
                putExtra("sourceKey", source?.getKey())
                putExtra("sourceType", source?.getSourceType())
            }
            ContextCompat.startForegroundService(appCtx, intent)
        } else {
            appCtx.startActivity<VideoPlayerActivity> {
                putExtra("videoUrl", url)
                putExtra("videoTitle", title)
                putExtra("sourceKey", source?.getKey())
                putExtra("sourceType", source?.getSourceType())
            }
        }
    }

}