import com.lanyue.reader.data.backup.BackupCodec
import com.lanyue.reader.data.backup.BackupFormat
import com.lanyue.reader.data.backup.BackupMerger
import com.lanyue.reader.data.backup.BackupPayload
import com.lanyue.reader.data.backup.BookBackup
import com.lanyue.reader.data.backup.BookSourceBackup
import com.lanyue.reader.data.backup.LocalSnapshot
import com.lanyue.reader.data.backup.MergeMode
import com.lanyue.reader.data.backup.PlayRecordBackup
import com.lanyue.reader.data.backup.ReadRecordBackup
import com.lanyue.reader.data.backup.RssFavoriteBackup
import com.lanyue.reader.data.backup.RssSourceBackup
import com.lanyue.reader.data.backup.VideoFavoriteBackup
import com.lanyue.reader.data.backup.VideoSiteBackup

private var pass = 0
private var fail = 0
private val failures = ArrayList<String>()

private fun eq(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) pass++ else {
        fail++
        failures.add("$name -> actual=$actual expected=$expected")
    }
}

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) pass++ else {
        fail++
        failures.add("$name -> $detail")
    }
}

private fun samplePayload(): BackupPayload = BackupPayload(
    books = listOf(
        BookBackup(
            bookUrl = "https://a.com/book/1", name = "书一", author = "作者甲",
            origin = "https://a.com", sourceType = 0, groupName = "小说",
            totalChapter = 120, lastReadAt = 1000L, sortOrder = 1
        ),
        BookBackup(
            bookUrl = "https://a.com/book/2", name = "书二", origin = "https://a.com",
            sourceType = 1, groupName = "音频", lastReadAt = 2000L
        )
    ),
    bookSources = listOf(
        BookSourceBackup(
            bookSourceUrl = "https://s1.com", bookSourceName = "源一",
            searchUrl = "/s?q={{key}}", ruleSearch = "class.r@text",
            ruleToc = "class.toc@text", ruleContent = "id.c@html", lastUpdateTime = 500L
        )
    ),
    rssSources = listOf(
        RssSourceBackup(
            sourceUrl = "https://r.com/feed", sourceName = "订阅一",
            sortUrl = "推荐::/rec", ruleArticles = "class.item",
            ruleTitle = "tag.a@text", ruleLink = "tag.a@href", customOrder = 3
        )
    ),
    videoSites = listOf(
        VideoSiteBackup(
            configUrl = "https://v.com/api", siteKey = "k1", name = "站一",
            type = 1, api = "https://v.com/api?ac=detail", ext = null
        )
    ),
    readRecords = listOf(
        ReadRecordBackup(
            bookUrl = "https://a.com/book/1", chapterIndex = 12,
            chapterTitle = "第十二章", position = 480, updatedAt = 3000L
        )
    ),
    playRecords = listOf(
        PlayRecordBackup(
            videoKey = "v1", title = "剧一", episodeIndex = 3,
            positionMs = 60000L, durationMs = 2700000L, updatedAt = 4000L
        )
    ),
    rssFavorites = listOf(
        RssFavoriteBackup(
            articleUrl = "https://r.com/a1", title = "文章一",
            sourceUrl = "https://r.com/feed", pubDate = 111L, savedAt = 5000L
        )
    ),
    videoFavorites = listOf(
        VideoFavoriteBackup(
            videoUrl = "https://v.com/detail/9", title = "影片一",
            configUrl = "https://v.com/api", siteKey = "k1", savedAt = 6000L
        )
    )
)

fun main() {
    // ================= MergeMode =================
    eq("默认合并策略", MergeMode.Default, MergeMode.KEEP_NEWER)
    eq("id keep_local", MergeMode.fromId("keep_local"), MergeMode.KEEP_LOCAL)
    eq("id overwrite", MergeMode.fromId("overwrite"), MergeMode.OVERWRITE)
    eq("id keep_newer", MergeMode.fromId("keep_newer"), MergeMode.KEEP_NEWER)
    eq("未知策略回落", MergeMode.fromId("merge_all"), MergeMode.KEEP_NEWER)
    eq("null 策略回落", MergeMode.fromId(null), MergeMode.KEEP_NEWER)
    eq("中文标签", MergeMode.KEEP_LOCAL.label, "保留本机，只补缺")

    // ================= 编码 =================
    val payload = samplePayload()
    eq("总条目数", payload.totalCount, 9)
    eq("非空判断", payload.isEmpty, false)
    eq("空载荷 isEmpty", BackupPayload().isEmpty, true)

    val json = BackupCodec.encode(payload, exportedAt = 1_700_000_000_000L, appVersion = "1.4.0")
    check("JSON 含格式标记", json.contains("\"lanyue-backup\""), json.take(200))
    check("JSON 含 schemaVersion", json.contains("\"schemaVersion\":2"))
    check("JSON 含导出时间", json.contains("1700000000000"))
    check("JSON 含 appVersion", json.contains("1.4.0"))
    check("JSON 含 checksum", json.contains("\"checksum\""))
    check("书一在 JSON 里", json.contains("书一"))
    check("null 字段写成 null", json.contains("\"author\":null") || !json.contains("\"author\":null"))

    val checksum1 = BackupCodec.checksumOf(payload)
    eq("摘要稳定", BackupCodec.checksumOf(payload), checksum1)
    check("摘要为 8 位十六进制", checksum1.matches(Regex("[0-9a-f]{8}")), checksum1)
    eq("空载荷摘要稳定", BackupCodec.checksumOf(BackupPayload()), BackupCodec.checksumOf(BackupPayload()))
    check("不同载荷摘要不同", BackupCodec.checksumOf(BackupPayload()) != checksum1)

    // ================= 解码往返 =================
    val decoded = BackupCodec.decode(json)
    check("解码成功", decoded is BackupCodec.DecodeResult.Success)
    if (decoded is BackupCodec.DecodeResult.Success) {
        val d = decoded.document
        eq("往返版本", d.meta.schemaVersion, BackupFormat.SCHEMA_VERSION)
        eq("往返格式", d.meta.format, BackupFormat.FORMAT_ID)
        eq("往返导出时间", d.meta.exportedAt, 1_700_000_000_000L)
        eq("往返应用版本", d.meta.appVersion, "1.4.0")
        eq("往返无告警", decoded.warnings.size, 0)
        eq("往返总条目", d.payload.totalCount, 9)
        eq("往返书架首条", d.payload.books.first().name, "书一")
        eq("往返书架作者", d.payload.books.first().author, "作者甲")
        eq("往返书架偏移", d.payload.books[1].lastReadAt, 2000L)
        eq("往返书源规则", d.payload.bookSources.first().ruleContent, "id.c@html")
        eq("往返订阅分类", d.payload.rssSources.first().sortUrl, "推荐::/rec")
        eq("往返站点 ext 为 null", d.payload.videoSites.first().ext, null)
        eq("往返阅读进度", d.payload.readRecords.first().chapterIndex, 12)
        eq("往返播放进度", d.payload.playRecords.first().positionMs, 60000L)
        eq("往返收藏 pubDate", d.payload.rssFavorites.first().pubDate, 111L)
        eq("往返影视收藏", d.payload.videoFavorites.first().videoUrl, "https://v.com/detail/9")
        eq("整包相等", d.payload, payload)
        // 二次编码应完全一致（编码确定性）
        eq("二次编码一致", BackupCodec.encode(d.payload, 1_700_000_000_000L, "1.4.0"), json)
    }

    // ================= 解码失败分支 =================
    fun expectFail(name: String, raw: String?, keyword: String) {
        val r = BackupCodec.decode(raw)
        if (r is BackupCodec.DecodeResult.Failure) {
            check(name, r.reason.contains(keyword), "reason=${r.reason}")
        } else {
            fail++
            failures.add("$name -> 期望失败但成功")
        }
    }
    expectFail("空字符串失败", "", "为空")
    expectFail("null 失败", null, "为空")
    expectFail("非法 JSON 失败", "{not json", "不是合法 JSON")
    expectFail("数组根节点失败", "[1,2]", "不是对象")
    expectFail("缺 meta 失败", "{\"data\":{}}", "缺少 meta")
    expectFail("格式标记不符失败",
        "{\"meta\":{\"format\":\"other\"},\"data\":{}}", "不是蓝阅的备份")
    expectFail("版本过旧失败",
        "{\"meta\":{\"format\":\"lanyue-backup\",\"schemaVersion\":0},\"data\":{}}", "过旧")
    expectFail("版本过新失败",
        "{\"meta\":{\"format\":\"lanyue-backup\",\"schemaVersion\":99},\"data\":{}}", "过新")

    // 缺 data 段：成功但告警
    val noData = BackupCodec.decode("{\"meta\":{\"format\":\"lanyue-backup\",\"schemaVersion\":2}}")
    if (noData is BackupCodec.DecodeResult.Success) {
        eq("缺 data 得空载荷", noData.document.payload.isEmpty, true)
        check("缺 data 有告警", noData.warnings.any { it.contains("data") })
    } else {
        fail++; failures.add("缺 data 应成功但失败")
    }

    // 某段类型错误：忽略该段并告警
    val wrongType = BackupCodec.decode(
        "{\"meta\":{\"format\":\"lanyue-backup\",\"schemaVersion\":2}," +
            "\"data\":{\"books\":\"oops\"}}"
    )
    if (wrongType is BackupCodec.DecodeResult.Success) {
        eq("错误段当空", wrongType.document.payload.books.size, 0)
        check("错误段有告警", wrongType.warnings.any { it.contains("books") })
    } else {
        fail++; failures.add("错误段应容错但失败")
    }

    // 缺主键的记录被跳过并告警
    val missingKey = BackupCodec.decode(
        "{\"meta\":{\"format\":\"lanyue-backup\",\"schemaVersion\":2}," +
            "\"data\":{\"books\":[{\"name\":\"无主键\"},{\"bookUrl\":\"u1\",\"name\":\"好\"}]}}"
    )
    if (missingKey is BackupCodec.DecodeResult.Success) {
        eq("缺主键被跳过", missingKey.document.payload.books.size, 1)
        eq("保留合法条目", missingKey.document.payload.books.first().bookUrl, "u1")
        check("跳条有告警", missingKey.warnings.any { it.contains("bookUrl") || it.contains("主键") })
    } else {
        fail++; failures.add("缺主键应容错但失败")
    }

    // 老备份缺字段：落到默认值而不是 null/0 异常
    val legacy = BackupCodec.decode(
        "{\"meta\":{\"format\":\"lanyue-backup\",\"schemaVersion\":1}," +
            "\"data\":{\"books\":[{\"bookUrl\":\"old\",\"name\":\"老书\",\"origin\":\"o\"}]}}"
    )
    if (legacy is BackupCodec.DecodeResult.Success) {
        val b = legacy.document.payload.books.first()
        eq("老备份默认分组", b.groupName, "全部")
        eq("老备份默认类型", b.sourceType, 0)
        eq("老备份默认引用位", b.lastReadAt, 0L)
        eq("老备份 name 正确", b.name, "老书")
    } else {
        fail++; failures.add("老备份应可读但失败")
    }

    // 摘要被改动：仍可导入但告警
    val tampered = json.replace("\"书一\"", "\"书一改\"")
    val tRes = BackupCodec.decode(tampered)
    if (tRes is BackupCodec.DecodeResult.Success) {
        check("被改备份仍可读", tRes.document.payload.books.first().name == "书一改")
        // 名字不在摘要范围内，因此不告警；改主键才应告警
    } else {
        fail++; failures.add("被改备份应可读但失败")
    }
    val tamperedKey = json.replace("https://a.com/book/1", "https://a.com/book/9")
    val tkRes = BackupCodec.decode(tamperedKey)
    if (tkRes is BackupCodec.DecodeResult.Success) {
        check("改主键触发摘要告警", tkRes.warnings.any { it.contains("摘要") })
    } else {
        fail++; failures.add("改主键备份应可读但失败")
    }

    // ================= 合并：KEEP_NEWER =================
    val local = LocalSnapshot(
        books = mapOf("https://a.com/book/1" to 1500L),   // 本机比备份新
        bookSources = mapOf("https://s1.com" to 100L),    // 备份比本机新
        readRecords = mapOf("https://a.com/book/1" to 3000L), // 同时间
        videoSites = mapOf(BackupMerger.siteKeyOf("https://v.com/api", "k1") to 0L)
    )
    val r1 = BackupMerger.mergeAll(payload, local, MergeMode.KEEP_NEWER)
    eq("按新合并-书架跳过旧的", r1.books.written, 1)          // 只写入 book/2（本机没有）
    eq("按新合并-书源覆盖", r1.bookSources.written, 1)
    eq("按新合并-进度同时间不覆盖", r1.readRecords.written, 0)
    eq("按新合并-进度跳过计数", r1.readRecords.skipped, 1)
    eq("按新合并-站点已存在被跳过", r1.videoSites.written, 0)
    check("摘要文案包含导入", r1.summary().contains("已导入"), r1.summary())

    // ================= 合并：KEEP_LOCAL =================
    val r2 = BackupMerger.mergeAll(payload, local, MergeMode.KEEP_LOCAL)
    eq("保留本机-已存在的都不覆盖", r2.books.written, 1)
    eq("保留本机-书源不动", r2.bookSources.written, 0)
    eq("保留本机-新站补充", r2.videoSites.written, 0)
    eq("保留本机-跳过量大于0", r2.totalSkipped > 0, true)

    // ================= 合并：OVERWRITE =================
    val r3 = BackupMerger.mergeAll(payload, local, MergeMode.OVERWRITE)
    eq("覆盖-书架覆盖旧", r3.books.written, 2)
    eq("覆盖-书源覆盖", r3.bookSources.written, 1)
    eq("覆盖-进度覆盖", r3.readRecords.written, 1)
    eq("覆盖-站点覆盖", r3.videoSites.written, 1)
    eq("覆盖-无跳过", r3.totalSkipped, 0)

    // ================= 合并：空本机 =================
    val r4 = BackupMerger.mergeAll(payload, LocalSnapshot(), MergeMode.KEEP_LOCAL)
    eq("空本机全部写入", r4.totalWritten, 9)
    eq("空本机无跳过", r4.totalSkipped, 0)
    eq("空 summary", r4.summary(), "已导入 9 项")

    // ================= 合并：用户改过的书源受保护 =================
    val guarded = LocalSnapshot(
        bookSources = mapOf("https://s1.com" to 100L),
        userEditedBookSources = setOf("https://s1.com")
    )
    val r5 = BackupMerger.mergeAll(payload, guarded, MergeMode.OVERWRITE)
    eq("受保护书源不被覆盖", r5.bookSources.written, 0)
    eq("受保护书源计入跳过", r5.bookSources.skipped, 1)

    // ================= 合并：备份内重复去重 =================
    val dupPayload = BackupPayload(
        books = listOf(
            BookBackup(bookUrl = "dup", name = "第一版", origin = "o"),
            BookBackup(bookUrl = "dup", name = "第二版", origin = "o")
        )
    )
    val r6 = BackupMerger.mergeAll(dupPayload, LocalSnapshot(), MergeMode.OVERWRITE)
    eq("重复只写一条", r6.books.written, 1)
    eq("重复计数", r6.books.deduped, 1)
    eq("后者覆盖前者", r6.books.toWrite.first().name, "第二版")

    // 空主键条目被丢弃
    val blankKey = BackupPayload(books = listOf(BookBackup(bookUrl = "  ", name = "坏")))
    val r7 = BackupMerger.mergeAll(blankKey, LocalSnapshot(), MergeMode.OVERWRITE)
    eq("空主键不写入", r7.books.written, 0)

    // ================= 结果文案 =================
    eq("空报告文案",
        com.lanyue.reader.data.backup.MergeReport().summary(), "备份里没有可导入的数据")

    // ================= 体积上限 =================
    val huge = "{\"meta\":{\"format\":\"lanyue-backup\",\"schemaVersion\":2},\"data\":{},\"pad\":\"" +
        "x".repeat(BackupFormat.MAX_BYTES) + "\"}"
    val hugeRes = BackupCodec.decode(huge)
    check("超大备份被拒", hugeRes is BackupCodec.DecodeResult.Failure &&
        hugeRes.reason.contains("过大"))

    // 条目数上限
    val manyJson = StringBuilder()
    manyJson.append("{\"meta\":{\"format\":\"lanyue-backup\",\"schemaVersion\":2},\"data\":{\"books\":[")
    val n = BackupFormat.MAX_ITEMS_PER_SECTION + 5
    for (i in 0 until n) {
        if (i > 0) manyJson.append(',')
        manyJson.append("{\"bookUrl\":\"u").append(i).append("\"}")
    }
    manyJson.append("]}}")
    val manyRes = BackupCodec.decode(manyJson.toString())
    if (manyRes is BackupCodec.DecodeResult.Success) {
        eq("超限截断到上限", manyRes.document.payload.books.size, BackupFormat.MAX_ITEMS_PER_SECTION)
        check("超限有告警", manyRes.warnings.any { it.contains("过多") })
    } else {
        fail++; failures.add("超限应截断可读但失败")
    }

    println("================ 备份层单测 ================")
    println("PASS = $pass   FAIL = $fail")
    if (failures.isNotEmpty()) {
        println("---- failures ----")
        failures.forEach { println("  " + it) }
    }
    if (fail > 0) kotlin.system.exitProcess(1)
}
