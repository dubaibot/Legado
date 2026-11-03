package io.legado.app.data.video

import io.legado.app.utils.LogUtils

/**
 * FongMi/TV TXT 源解析器。
 *
 * 支持两种 TXT 格式：
 *
 * 1. FongMi 逐行格式（每行一个 CMS 站点）：
 * 站点名1$https://api1.example.com/api.php/provide/vod
 * 站点名2$https://api2.example.com/api.php/provide/vod
 * 站点名3$https://api3.example.com/api.php/provide/vod
 *
 * 每行格式：名称$接口地址
 * 解析后每个站点创建一个 CMS 源。
 *
 * 2. 静态列表格式（单站点）：
 * # 站点名称
 * [电影]
 * 标题1|播放URL|封面URL
 *
 * 此格式由 TxtVideoSource 直接处理。
 */
object FongMiTxtParser {

    private const val TAG = "FongMiTxtParser"

    /** FongMi 站点条目 */
    data class FongMiSite(
        val name: String,
        val api: String,
    )

    /**
     * 判断是否为 FongMi 逐行格式。
     * 特征：至少一行包含 "$" 且 "$" 后有 http/https URL。
     */
    fun isFongMiTxt(content: String): Boolean {
        return content.lineSequence().any { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("[")) return false
            val idx = trimmed.indexOf('$')
            if (idx <= 0) return false
            val url = trimmed.substring(idx + 1).trim()
            return url.startsWith("http://") || url.startsWith("https://")
        }
    }

    /**
     * 解析 FongMi TXT 内容，返回站点列表。
     */
    fun parse(content: String): List<FongMiSite> {
        val sites = mutableListOf<FongMiSite>()
        content.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val idx = line.indexOf('$')
            if (idx <= 0) return@forEach
            val name = line.substring(0, idx).trim()
            val api = line.substring(idx + 1).trim()
            if (name.isNotBlank() && api.isNotBlank() &&
                (api.startsWith("http://") || api.startsWith("https://"))
            ) {
                sites.add(FongMiSite(name = name, api = api))
            }
        }
        return sites
    }
}
