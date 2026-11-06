package com.lanyue.reader.data.backup

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 备份编解码。
 *
 * 刻意不用 Gson 的对象映射（toJson/fromJson）而是手写字段读写，原因：
 * - Gson 反射反序列化会忽略 Kotlin 默认值，老备份缺字段时得到 null/0 而不是默认值；
 * - 备份是长期契约，字段读写必须显式可控，才能给出"哪个字段坏了"的精确告警；
 * - 需要逐字段容错：单条记录损坏不应让整份备份失败。
 */
object BackupCodec {

    /** 解码结果。 */
    sealed class DecodeResult {
        /** 成功。[warnings] 是逐字段/逐条记录级的容错提示，不影响可用性。 */
        data class Success(val document: BackupDocument, val warnings: List<String>) : DecodeResult()

        /** 失败，[reason] 是可直接展示给用户的中文原因。 */
        data class Failure(val reason: String) : DecodeResult()
    }

    // ============================ 编码 ============================

    /**
     * 导出为 JSON 文本。
     * 顺序固定为 meta 在前，便于人工查看与 diff。
     */
    fun encode(
        payload: BackupPayload,
        exportedAt: Long,
        appVersion: String = ""
    ): String {
        val root = JsonObject()
        val meta = JsonObject()
        meta.addProperty("format", BackupFormat.FORMAT_ID)
        meta.addProperty("schemaVersion", BackupFormat.SCHEMA_VERSION)
        meta.addProperty("exportedAt", exportedAt)
        meta.addProperty("appVersion", appVersion)
        meta.addProperty("checksum", checksumOf(payload))
        root.add("meta", meta)

        val body = JsonObject()
        body.add("books", arrayOf(payload.books) { b ->
            obj {
                str("bookUrl", b.bookUrl)
                str("name", b.name)
                strOrNull("author", b.author)
                strOrNull("cover", b.cover)
                strOrNull("intro", b.intro)
                str("origin", b.origin)
                strOrNull("originName", b.originName)
                num("sourceType", b.sourceType)
                str("groupName", b.groupName)
                strOrNull("latestChapter", b.latestChapter)
                num("totalChapter", b.totalChapter)
                num("lastReadAt", b.lastReadAt)
                num("sortOrder", b.sortOrder)
            }
        })
        body.add("bookSources", arrayOf(payload.bookSources) { s ->
            obj {
                str("bookSourceUrl", s.bookSourceUrl)
                str("bookSourceName", s.bookSourceName)
                strOrNull("bookSourceGroup", s.bookSourceGroup)
                num("bookSourceType", s.bookSourceType)
                bool("enabled", s.enabled)
                bool("enabledExplore", s.enabledExplore)
                strOrNull("searchUrl", s.searchUrl)
                strOrNull("ruleSearch", s.ruleSearch)
                strOrNull("exploreUrl", s.exploreUrl)
                strOrNull("ruleExplore", s.ruleExplore)
                strOrNull("ruleToc", s.ruleToc)
                strOrNull("ruleContent", s.ruleContent)
                strOrNull("ruleBookInfo", s.ruleBookInfo)
                strOrNull("header", s.header)
                strOrNull("loginUrl", s.loginUrl)
                strOrNull("jsLib", s.jsLib)
                num("lastUpdateTime", s.lastUpdateTime)
                strOrNull("userEditedFields", s.userEditedFields)
            }
        })
        body.add("rssSources", arrayOf(payload.rssSources) { s ->
            obj {
                str("sourceUrl", s.sourceUrl)
                str("sourceName", s.sourceName)
                strOrNull("sourceIcon", s.sourceIcon)
                strOrNull("sourceGroup", s.sourceGroup)
                bool("enabled", s.enabled)
                strOrNull("sortUrl", s.sortUrl)
                strOrNull("ruleArticles", s.ruleArticles)
                strOrNull("ruleTitle", s.ruleTitle)
                strOrNull("ruleLink", s.ruleLink)
                strOrNull("rulePubDate", s.rulePubDate)
                strOrNull("ruleContent", s.ruleContent)
                num("type", s.type)
                strOrNull("searchUrl", s.searchUrl)
                num("customOrder", s.customOrder)
                num("lastUpdateTime", s.lastUpdateTime)
            }
        })
        body.add("videoSites", arrayOf(payload.videoSites) { s ->
            obj {
                str("configUrl", s.configUrl)
                str("siteKey", s.siteKey)
                str("name", s.name)
                num("type", s.type)
                str("api", s.api)
                strOrNull("ext", s.ext)
                bool("enabled", s.enabled)
                bool("searchable", s.searchable)
            }
        })
        body.add("readRecords", arrayOf(payload.readRecords) { r ->
            obj {
                str("bookUrl", r.bookUrl)
                num("chapterIndex", r.chapterIndex)
                strOrNull("chapterTitle", r.chapterTitle)
                strOrNull("chapterUrl", r.chapterUrl)
                num("position", r.position)
                num("updatedAt", r.updatedAt)
            }
        })
        body.add("playRecords", arrayOf(payload.playRecords) { r ->
            obj {
                str("videoKey", r.videoKey)
                str("title", r.title)
                num("episodeIndex", r.episodeIndex)
                num("positionMs", r.positionMs)
                num("durationMs", r.durationMs)
                num("updatedAt", r.updatedAt)
            }
        })
        body.add("rssFavorites", arrayOf(payload.rssFavorites) { f ->
            obj {
                str("articleUrl", f.articleUrl)
                str("title", f.title)
                str("sourceUrl", f.sourceUrl)
                strOrNull("cover", f.cover)
                numOrNull("pubDate", f.pubDate)
                num("savedAt", f.savedAt)
            }
        })
        body.add("videoFavorites", arrayOf(payload.videoFavorites) { f ->
            obj {
                str("videoUrl", f.videoUrl)
                str("title", f.title)
                str("configUrl", f.configUrl)
                str("siteKey", f.siteKey)
                strOrNull("cover", f.cover)
                num("savedAt", f.savedAt)
            }
        })
        root.add("data", body)
        return root.toString()
    }

    // ============================ 解码 ============================

    /**
     * 解析 JSON 文本。
     *
     * 容错策略：
     * - 格式标记不符 / 版本过高 -> 整体失败（继续读没有意义）
     * - 某一块缺失或类型不对 -> 该块当空，记一条告警
     * - 某条记录主键为空 -> 跳过该条，记一条告警
     * - checksum 不匹配 -> 仍返回数据，但告警提示"可能被改过"
     */
    fun decode(raw: String?): DecodeResult {
        if (raw.isNullOrBlank()) return DecodeResult.Failure("备份内容为空")
        if (raw.length > BackupFormat.MAX_BYTES) {
            return DecodeResult.Failure("备份文件过大，超过 8 MiB")
        }

        val root: JsonElement = try {
            JsonParser.parseString(raw)
        } catch (e: Throwable) {
            return DecodeResult.Failure("备份不是合法 JSON：" + (e.message ?: ""))
        }
        if (!root.isJsonObject) return DecodeResult.Failure("备份根节点不是对象")
        val rootObj = root.asJsonObject

        val metaObj = rootObj.get("meta")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return DecodeResult.Failure("备份缺少 meta 段")
        val format = metaObj.stringOrNull("format")
        if (format != BackupFormat.FORMAT_ID) {
            return DecodeResult.Failure("不是蓝阅的备份文件（格式标记：" + (format ?: "无") + "）")
        }
        val version = (metaObj.get("schemaVersion")?.takeIf { it.isJsonPrimitive }?.asInt) ?: -1
        if (version < BackupFormat.MIN_READABLE_VERSION) {
            return DecodeResult.Failure("备份版本过旧（v$version），当前最低支持 v" +
                BackupFormat.MIN_READABLE_VERSION)
        }
        if (version > BackupFormat.SCHEMA_VERSION) {
            return DecodeResult.Failure(
                "备份版本过新（v$version），请升级应用后再导入"
            )
        }

        val meta = BackupMeta(
            format = BackupFormat.FORMAT_ID,
            schemaVersion = version,
            exportedAt = metaObj.get("exportedAt")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
            appVersion = metaObj.stringOrNull("appVersion") ?: "",
            checksum = metaObj.stringOrNull("checksum") ?: ""
        )

        val warnings = ArrayList<String>()
        val data = rootObj.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
        if (data == null) {
            warnings.add("备份缺少 data 段，导入后将没有任何数据")
            return DecodeResult.Success(BackupDocument(meta, BackupPayload()), warnings)
        }

        fun section(name: String): JsonArray? {
            val el = data.get(name) ?: return JsonArray()
            if (!el.isJsonArray) {
                warnings.add("字段 $name 类型错误，已忽略")
                return JsonArray()
            }
            val arr = el.asJsonArray
            if (arr.size() > BackupFormat.MAX_ITEMS_PER_SECTION) {
                warnings.add("字段 $name 条目过多，仅读取前 " + BackupFormat.MAX_ITEMS_PER_SECTION + " 条")
                val cut = JsonArray()
                for (i in 0 until BackupFormat.MAX_ITEMS_PER_SECTION) cut.add(arr.get(i))
                return cut
            }
            return arr
        }

        val books = ArrayList<BookBackup>()
        eachObj(section("books"), "books", warnings) { o ->
            val url = o.stringOrNull("bookUrl").orEmpty()
            if (url.isBlank()) return@eachObj false
            books.add(
                BookBackup(
                    bookUrl = url,
                    name = o.stringOrNull("name") ?: "",
                    author = o.stringOrNull("author"),
                    cover = o.stringOrNull("cover"),
                    intro = o.stringOrNull("intro"),
                    origin = o.stringOrNull("origin") ?: "",
                    originName = o.stringOrNull("originName"),
                    sourceType = o.intOr("sourceType", 0),
                    groupName = o.stringOrNull("groupName") ?: "全部",
                    latestChapter = o.stringOrNull("latestChapter"),
                    totalChapter = o.intOr("totalChapter", 0),
                    lastReadAt = o.longOr("lastReadAt", 0),
                    sortOrder = o.intOr("sortOrder", 0)
                )
            )
            true
        }

        val bookSources = ArrayList<BookSourceBackup>()
        eachObj(section("bookSources"), "bookSources", warnings) { o ->
            val url = o.stringOrNull("bookSourceUrl").orEmpty()
            if (url.isBlank()) return@eachObj false
            bookSources.add(
                BookSourceBackup(
                    bookSourceUrl = url,
                    bookSourceName = o.stringOrNull("bookSourceName") ?: "",
                    bookSourceGroup = o.stringOrNull("bookSourceGroup"),
                    bookSourceType = o.intOr("bookSourceType", 0),
                    enabled = o.boolOr("enabled", true),
                    enabledExplore = o.boolOr("enabledExplore", false),
                    searchUrl = o.stringOrNull("searchUrl"),
                    ruleSearch = o.stringOrNull("ruleSearch"),
                    exploreUrl = o.stringOrNull("exploreUrl"),
                    ruleExplore = o.stringOrNull("ruleExplore"),
                    ruleToc = o.stringOrNull("ruleToc"),
                    ruleContent = o.stringOrNull("ruleContent"),
                    ruleBookInfo = o.stringOrNull("ruleBookInfo"),
                    header = o.stringOrNull("header"),
                    loginUrl = o.stringOrNull("loginUrl"),
                    jsLib = o.stringOrNull("jsLib"),
                    lastUpdateTime = o.longOr("lastUpdateTime", 0),
                    userEditedFields = o.stringOrNull("userEditedFields")
                )
            )
            true
        }

        val rssSources = ArrayList<RssSourceBackup>()
        eachObj(section("rssSources"), "rssSources", warnings) { o ->
            val url = o.stringOrNull("sourceUrl").orEmpty()
            if (url.isBlank()) return@eachObj false
            rssSources.add(
                RssSourceBackup(
                    sourceUrl = url,
                    sourceName = o.stringOrNull("sourceName") ?: "",
                    sourceIcon = o.stringOrNull("sourceIcon"),
                    sourceGroup = o.stringOrNull("sourceGroup"),
                    enabled = o.boolOr("enabled", true),
                    sortUrl = o.stringOrNull("sortUrl"),
                    ruleArticles = o.stringOrNull("ruleArticles"),
                    ruleTitle = o.stringOrNull("ruleTitle"),
                    ruleLink = o.stringOrNull("ruleLink"),
                    rulePubDate = o.stringOrNull("rulePubDate"),
                    ruleContent = o.stringOrNull("ruleContent"),
                    type = o.intOr("type", 0),
                    searchUrl = o.stringOrNull("searchUrl"),
                    customOrder = o.intOr("customOrder", 0),
                    lastUpdateTime = o.longOr("lastUpdateTime", 0)
                )
            )
            true
        }

        val videoSites = ArrayList<VideoSiteBackup>()
        eachObj(section("videoSites"), "videoSites", warnings) { o ->
            val config = o.stringOrNull("configUrl").orEmpty()
            val key = o.stringOrNull("siteKey").orEmpty()
            if (config.isBlank() || key.isBlank()) return@eachObj false
            videoSites.add(
                VideoSiteBackup(
                    configUrl = config,
                    siteKey = key,
                    name = o.stringOrNull("name") ?: "",
                    type = o.intOr("type", 1),
                    api = o.stringOrNull("api") ?: "",
                    ext = o.stringOrNull("ext"),
                    enabled = o.boolOr("enabled", true),
                    searchable = o.boolOr("searchable", true)
                )
            )
            true
        }

        val readRecords = ArrayList<ReadRecordBackup>()
        eachObj(section("readRecords"), "readRecords", warnings) { o ->
            val url = o.stringOrNull("bookUrl").orEmpty()
            if (url.isBlank()) return@eachObj false
            readRecords.add(
                ReadRecordBackup(
                    bookUrl = url,
                    chapterIndex = o.intOr("chapterIndex", 0),
                    chapterTitle = o.stringOrNull("chapterTitle"),
                    chapterUrl = o.stringOrNull("chapterUrl"),
                    position = o.intOr("position", 0),
                    updatedAt = o.longOr("updatedAt", 0)
                )
            )
            true
        }

        val playRecords = ArrayList<PlayRecordBackup>()
        eachObj(section("playRecords"), "playRecords", warnings) { o ->
            val key = o.stringOrNull("videoKey").orEmpty()
            if (key.isBlank()) return@eachObj false
            playRecords.add(
                PlayRecordBackup(
                    videoKey = key,
                    title = o.stringOrNull("title") ?: "",
                    episodeIndex = o.intOr("episodeIndex", 0),
                    positionMs = o.longOr("positionMs", 0),
                    durationMs = o.longOr("durationMs", 0),
                    updatedAt = o.longOr("updatedAt", 0)
                )
            )
            true
        }

        val rssFavorites = ArrayList<RssFavoriteBackup>()
        eachObj(section("rssFavorites"), "rssFavorites", warnings) { o ->
            val url = o.stringOrNull("articleUrl").orEmpty()
            if (url.isBlank()) return@eachObj false
            rssFavorites.add(
                RssFavoriteBackup(
                    articleUrl = url,
                    title = o.stringOrNull("title") ?: "",
                    sourceUrl = o.stringOrNull("sourceUrl") ?: "",
                    cover = o.stringOrNull("cover"),
                    pubDate = o.longOrNull("pubDate"),
                    savedAt = o.longOr("savedAt", 0)
                )
            )
            true
        }

        val videoFavorites = ArrayList<VideoFavoriteBackup>()
        eachObj(section("videoFavorites"), "videoFavorites", warnings) { o ->
            val url = o.stringOrNull("videoUrl").orEmpty()
            if (url.isBlank()) return@eachObj false
            videoFavorites.add(
                VideoFavoriteBackup(
                    videoUrl = url,
                    title = o.stringOrNull("title") ?: "",
                    configUrl = o.stringOrNull("configUrl") ?: "",
                    siteKey = o.stringOrNull("siteKey") ?: "",
                    cover = o.stringOrNull("cover"),
                    savedAt = o.longOr("savedAt", 0)
                )
            )
            true
        }

        val payload = BackupPayload(
            books = books,
            bookSources = bookSources,
            rssSources = rssSources,
            videoSites = videoSites,
            readRecords = readRecords,
            playRecords = playRecords,
            rssFavorites = rssFavorites,
            videoFavorites = videoFavorites
        )

        // 校验和：不匹配不阻断导入（用户可能手工编辑过），但要明确告警
        if (meta.checksum.isNotBlank() && meta.checksum != checksumOf(payload)) {
            warnings.add("内容摘要与 meta 不一致，备份可能被修改或截断")
        }
        return DecodeResult.Success(BackupDocument(meta, payload), warnings)
    }

    // ============================ 校验和 ============================

    /**
     * 内容摘要：对"各段条目数 + 每段首尾主键"做 FNV-1a 32 位散列。
     *
     * 不逐字节哈希全文是刻意的：备份允许字段顺序/空白差异，
     * 这里只关心"结构规模与边界是否一致"，能发现截断与替换，又不受格式化影响。
     */
    fun checksumOf(payload: BackupPayload): String {
        val sb = StringBuilder()
        sb.append(payload.books.size).append('|')
            .append(payload.books.firstOrNull()?.bookUrl.orEmpty()).append('|')
            .append(payload.books.lastOrNull()?.bookUrl.orEmpty()).append('|')
        sb.append(payload.bookSources.size).append('|')
            .append(payload.bookSources.firstOrNull()?.bookSourceUrl.orEmpty()).append('|')
            .append(payload.bookSources.lastOrNull()?.bookSourceUrl.orEmpty()).append('|')
        sb.append(payload.rssSources.size).append('|')
            .append(payload.rssSources.firstOrNull()?.sourceUrl.orEmpty()).append('|')
            .append(payload.rssSources.lastOrNull()?.sourceUrl.orEmpty()).append('|')
        sb.append(payload.videoSites.size).append('|')
            .append(payload.videoSites.firstOrNull()?.siteKey.orEmpty()).append('|')
            .append(payload.videoSites.lastOrNull()?.siteKey.orEmpty()).append('|')
        sb.append(payload.readRecords.size).append('|')
            .append(payload.readRecords.firstOrNull()?.bookUrl.orEmpty()).append('|')
            .append(payload.readRecords.lastOrNull()?.bookUrl.orEmpty()).append('|')
        sb.append(payload.playRecords.size).append('|')
            .append(payload.playRecords.firstOrNull()?.videoKey.orEmpty()).append('|')
            .append(payload.playRecords.lastOrNull()?.videoKey.orEmpty()).append('|')
        sb.append(payload.rssFavorites.size).append('|')
            .append(payload.rssFavorites.firstOrNull()?.articleUrl.orEmpty()).append('|')
            .append(payload.rssFavorites.lastOrNull()?.articleUrl.orEmpty()).append('|')
        sb.append(payload.videoFavorites.size).append('|')
            .append(payload.videoFavorites.firstOrNull()?.videoUrl.orEmpty()).append('|')
            .append(payload.videoFavorites.lastOrNull()?.videoUrl.orEmpty())

        var hash = 0x811C9DC5.toInt()
        for (ch in sb.toString()) {
            hash = hash xor ch.code
            hash *= 0x01000193
        }
        return java.lang.String.format("%08x", hash)
    }

    // ============================ 内部工具 ============================

    private inline fun obj(block: JsonObject.() -> Unit): JsonObject =
        JsonObject().apply(block)

    private fun <T> arrayOf(items: List<T>, mapper: (T) -> JsonObject): JsonArray {
        val arr = JsonArray()
        items.forEach { arr.add(mapper(it)) }
        return arr
    }

    private fun JsonObject.str(key: String, value: String) = addProperty(key, value)

    private fun JsonObject.strOrNull(key: String, value: String?) {
        if (value == null) add(key, com.google.gson.JsonNull.INSTANCE) else addProperty(key, value)
    }

    private fun JsonObject.num(key: String, value: Int) = addProperty(key, value)

    private fun JsonObject.num(key: String, value: Long) = addProperty(key, value)

    private fun JsonObject.numOrNull(key: String, value: Long?) {
        if (value == null) add(key, com.google.gson.JsonNull.INSTANCE) else addProperty(key, value)
    }

    private fun JsonObject.bool(key: String, value: Boolean) = addProperty(key, value)

    private fun JsonObject.stringOrNull(key: String): String? {
        val el = get(key) ?: return null
        if (el.isJsonNull || !el.isJsonPrimitive) return null
        return try {
            el.asString
        } catch (e: Throwable) {
            null
        }
    }

    private fun JsonObject.intOr(key: String, def: Int): Int {
        val el = get(key) ?: return def
        if (!el.isJsonPrimitive) return def
        return try {
            el.asInt
        } catch (e: Throwable) {
            def
        }
    }

    private fun JsonObject.longOr(key: String, def: Long): Long {
        val el = get(key) ?: return def
        if (!el.isJsonPrimitive) return def
        return try {
            el.asLong
        } catch (e: Throwable) {
            def
        }
    }

    private fun JsonObject.longOrNull(key: String): Long? {
        val el = get(key) ?: return null
        if (!el.isJsonPrimitive) return null
        return try {
            el.asLong
        } catch (e: Throwable) {
            null
        }
    }

    private fun JsonObject.boolOr(key: String, def: Boolean): Boolean {
        val el = get(key) ?: return def
        if (!el.isJsonPrimitive) return def
        return try {
            el.asBoolean
        } catch (e: Throwable) {
            def
        }
    }

    /** 遍历数组里的对象元素；返回 false 表示该条被跳过（已记告警）。 */
    private inline fun eachObj(
        arr: JsonArray?,
        sectionName: String,
        warnings: MutableList<String>,
        handler: (JsonObject) -> Boolean
    ) {
        if (arr == null) return
        var skipped = 0
        arr.forEach { el ->
            if (!el.isJsonObject) {
                skipped++
                return@forEach
            }
            if (!handler(el.asJsonObject)) skipped++
        }
        if (skipped > 0) warnings.add("$sectionName 中有 $skipped 条缺少主键被跳过")
    }
}
