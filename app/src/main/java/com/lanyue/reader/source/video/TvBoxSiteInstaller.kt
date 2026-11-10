package com.lanyue.reader.source.video

import com.lanyue.reader.data.db.entity.VideoApiEntity
import com.lanyue.reader.data.db.entity.VideoSiteEntity
import com.lanyue.reader.source.model.SourceValidator

/**
 * TVBox 站点安装器（纯逻辑，无 IO）。
 *
 * [TvBoxParser] 负责「把一份 JSON 展开成站点」，本类负责「多份配置装到一起会发生什么」：
 * 重复站点怎么合并、开关状态怎么继承、类型写错怎么归一、jar 站点怎么标记。
 *
 * 为什么独立成一层：
 * 1. 真实使用中用户会连续导入好几个订阅仓，站点大量重叠（同一 site.key 出现在多个仓里）；
 * 2. 「重新导入」必须是幂等的——不能因为再点一次导入就把用户手工关掉的站点全打开；
 * 3. 这些规则直接决定播放成功率，必须能脱机重放与单测。
 *
 * 冲突语义：
 * - 同一 (configUrl, siteKey) 视为同一站点
 * - 重新导入时保留用户的 enabled / quickSearch 设置，只更新站点定义本体
 * - 站点 key 为空或 api 缺失的条目直接拒绝，不入库
 * - type 3 且 api 非 .js/.py 的（spider jar）标记 unsupported，保留但不参与搜索
 */
object TvBoxSiteInstaller {

    /** 站点 key 的复合键分隔符（不可见字符，避免与 url 冲突）。 */
    private const val KEY_SEP = "\u0001"

    /** 站点主键。 */
    fun siteIdentity(configUrl: String, siteKey: String): String =
        configUrl + KEY_SEP + siteKey

    /** 一次安装的结果。 */
    data class InstallReport(
        val added: List<String> = emptyList(),
        val updated: List<String> = emptyList(),
        val unchanged: List<String> = emptyList(),
        /** 类型不受支持但仍保留的站点数。 */
        val unsupported: Int = 0,
        val rejected: List<Rejected> = emptyList(),
        /** 多个配置之间的重复站点数。 */
        val duplicates: Int = 0,
        val apis: List<String> = emptyList()
    ) {
        /** 被拒绝的条目。 */
        data class Rejected(val siteKey: String, val reason: String)

        val totalSites: Int get() = added.size + updated.size + unchanged.size

        val isEmpty: Boolean
            get() = added.isEmpty() && updated.isEmpty() && unchanged.isEmpty() &&
                rejected.isEmpty()

        /** 一句话结论，直接做导入结果条。 */
        fun summary(): String {
            if (isEmpty) return "没有可安装的站点"
            val parts = ArrayList<String>()
            if (added.isNotEmpty()) parts.add("新增 " + added.size)
            if (updated.isNotEmpty()) parts.add("更新 " + updated.size)
            if (unchanged.isNotEmpty()) parts.add("不变 " + unchanged.size)
            if (unsupported > 0) parts.add("不支持 " + unsupported)
            if (rejected.isNotEmpty()) parts.add("无效 " + rejected.size)
            return parts.joinToString("，")
        }
    }

    /** 站点集合概览。 */
    data class Stats(
        val total: Int,
        val enabled: Int,
        val searchable: Int,
        val unsupported: Int,
        val apis: Int
    )

    /**
     * 把多份已解析配置装成最终站点列表。
     *
     * @param existing  库里已有的站点（用于判断新增/更新/不变与继承开关）
     * @param configs   本次导入的配置
     * @param now       当前时间戳，写进 VideoApiEntity.lastUpdate
     */
    fun install(
        existing: List<VideoSiteEntity>,
        configs: List<TvBoxParser.ParsedConfig>,
        now: Long
    ): Pair<List<VideoSiteEntity>, InstallReport> {

        val current = LinkedHashMap<String, VideoSiteEntity>()
        existing.forEach { current[siteIdentity(it.configUrl, it.siteKey)] = it }

        val added = ArrayList<String>()
        val updated = ArrayList<String>()
        val unchanged = ArrayList<String>()
        val rejected = ArrayList<InstallReport.Rejected>()
        val apis = LinkedHashSet<String>()
        var duplicates = 0
        var unsupportedCount = 0

        // 本次导入内部先按复合键去重：后出现的配置覆盖前面的定义
        val incoming = LinkedHashMap<String, Pair<VideoApiEntity, VideoSiteEntity>>()
        configs.forEach { cfg ->
            apis.add(cfg.api.url)
            cfg.sites.forEach { raw ->
                val key = raw.siteKey.trim()
                if (key.isEmpty()) {
                    rejected.add(
                        InstallReport.Rejected(raw.name.ifBlank { "(无名称)" }, "缺少 site.key")
                    )
                    return@forEach
                }
                val apiText = raw.api?.trim().orEmpty()
                if (apiText.isEmpty() && raw.jar.isNullOrBlank()) {
                    rejected.add(InstallReport.Rejected(key, "缺少 api 地址"))
                    return@forEach
                }
                val normalized = normalize(raw)
                val id = siteIdentity(normalized.configUrl, normalized.siteKey)
                if (incoming.put(id, cfg.api to normalized) != null) duplicates++
            }
        }

        incoming.forEach { (id, pair) ->
            val (api, site) = pair
            val old = current[id]
            if (old == null) {
                current[id] = site
                added.add(id)
                if (site.unsupported) unsupportedCount++
                return@forEach
            }
            if (site.unsupported) unsupportedCount++

            // 站点定义本身是否变化
            val changed = old.name != site.name ||
                old.type != site.type ||
                old.api != site.api ||
                old.ext != site.ext ||
                old.jar != site.jar ||
                old.searchable != site.searchable ||
                old.filterable != site.filterable ||
                old.unsupported != site.unsupported

            if (!changed) {
                unchanged.add(id)
                return@forEach
            }
            // 定义变了：继承用户的启用状态与快速搜索偏好
            current[id] = site.copy(
                enabled = old.enabled,
                quickSearch = old.quickSearch,
                timeout = if (site.timeout > 0) site.timeout else old.timeout
            )
            updated.add(id)
        }

        // 保持稳定顺序：先按配置地址，再按站点名
        val result = current.values.sortedWith(
            compareBy({ it.configUrl }, { it.name }, { it.siteKey })
        )
        return result to InstallReport(
            added = added,
            updated = updated,
            unchanged = unchanged,
            unsupported = unsupportedCount,
            rejected = rejected,
            duplicates = duplicates,
            apis = apis.toList()
        )
    }

    /**
     * 归一化单个站点。
     *
     * 站点的 type 在真实配置里既可能是数字也可能是字符串（"1" / "json" / "spider"），
     * 这里统一成 [VideoSiteEntity.type] 的 0..4。
     * 同时判定 unsupported：type 3 且 api 为 jar 形态（不带 .js/.py 后缀）即 spider jar，
     * 一期不提供 jar 运行时，因此标记出来而不是直接丢弃——用户仍能看到它存在。
     */
    fun normalize(raw: VideoSiteEntity): VideoSiteEntity {
        val type = normalizeType(raw.type, raw.api)
        val jar = raw.jar?.takeIf { it.isNotBlank() }
        val isJarSpider = type == 3 && jar != null
        return raw.copy(
            siteKey = raw.siteKey.trim(),
            name = raw.name.trim().ifBlank { raw.siteKey.trim() },
            type = type,
            api = raw.api?.trim(),
            ext = raw.ext?.trim()?.takeIf { it.isNotEmpty() },
            jar = jar,
            unsupported = isJarSpider,
            // jar 站点无法搜索，置为不可搜索以免列表里点了报错
            searchable = if (isJarSpider) false else raw.searchable,
            timeout = raw.timeout.coerceAtLeast(0)
        )
    }

    /**
     * 类型归一。
     *
     * 依据 [VideoSiteEntity.type] 的注释：
     * 0 XML(MacCMS V9) / 1 JSON(MacCMS10) / 2 JSON 扩展 / 3 Spider或drpy / 4 JSON扩展1(hipy t4)
     */
    fun normalizeType(rawType: Int, api: String?): Int {
        if (rawType in 0..4) return rawType
        // 越界值按 api 形态猜测：以 .js / .py 结尾视为 spider，否则按 JSON 处理
        val a = api?.trim()?.lowercase().orEmpty()
        return if (a.endsWith(".js") || a.endsWith(".py") || a.endsWith(".jar")) 3 else 1
    }

    /** 从站点列表算概览。 */
    fun stats(sites: List<VideoSiteEntity>): Stats = Stats(
        total = sites.size,
        enabled = sites.count { it.enabled },
        searchable = sites.count { it.enabled && it.searchable && !it.unsupported },
        unsupported = sites.count { it.unsupported },
        apis = sites.map { it.configUrl }.distinct().size
    )

    /** 可参与搜索/推荐的站点。 */
    fun searchable(sites: List<VideoSiteEntity>): List<VideoSiteEntity> =
        sites.filter { it.enabled && it.searchable && !it.unsupported }

    /** 按接口配置分组，便于设置页按仓展示。 */
    fun byApi(sites: List<VideoSiteEntity>): Map<String, List<VideoSiteEntity>> =
        sites.groupBy { it.configUrl }.mapValues { it.value.sortedBy { s -> s.name } }

    /** 删除整份配置对应的站点，返回删除条数。 */
    fun removeApi(sites: List<VideoSiteEntity>, configUrl: String): List<VideoSiteEntity> =
        sites.filterNot { it.configUrl == configUrl }

    /** 启停单个站点。 */
    fun setEnabled(
        sites: List<VideoSiteEntity>,
        configUrl: String,
        siteKey: String,
        enabled: Boolean
    ): List<VideoSiteEntity> = sites.map {
        if (it.configUrl == configUrl && it.siteKey == siteKey) it.copy(enabled = enabled) else it
    }

    /**
     * 批量体检站点配置，返回 siteKey -> 结论。
     *
     * 与 [SourceValidator] 的分工：这里只做「影视站点」这一类源的形态检查，
     * 比通用校验更严格地要求 api 可解析。
     */
    fun validate(site: VideoSiteEntity): String {
        if (site.siteKey.isBlank()) return "缺少站点标识"
        if (site.unsupported) return "jar 类型站点暂不支持"
        val api = site.api?.trim().orEmpty()
        if (api.isEmpty()) return "缺少 api 地址"
        if (!SourceValidator.isHttpUrl(api) && !api.startsWith("/")) {
            // 相对路径（/api.php/...）由配置地址拼接后可用，属合法
            return "api 地址无法识别：" + api.take(60)
        }
        if (!site.searchable) return "未开启搜索"
        return "可用"
    }
}
