package io.legado.app.data.video

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.utils.LogUtils

/**
 * FongMi/TV Config 解析器。
 *
 * 支持两种 Config 格式：
 *
 * 1. 多站点 Config（type 数组）：
 * {
 *   "sites": [
 *     {"key":"app1","name":"App1","type":0,"api":"https://.../api.php/provide/vod"},
 *     {"key":"app2","name":"App2","type":1,"api":"https://.../api.php/provide/vod"},
 *     {"key":"app3","name":"App3","type":3,"api":"https://.../api.php/provide/vod"}
 *   ]
 * }
 *
 * 2. 单站点 Config：
 * {
 *   "name": "站点名",
 *   "type": "cms",
 *   "api": "https://.../api.php/provide/vod"
 * }
 *
 * type 说明（FongMi 约定）：
 * - 0: CMS 直连（苹果CMS V10 标准 API）
 * - 1: CMS 直连 + 搜索 API 分离
 * - 3: jar 爬虫（本实现用 CMS API 兼容）
 * - "cms": 同 type 0
 * - "custom": 自定义端点
 *
 * 本解析器将多站点 Config 拆分为独立的 SiteConfig 列表。
 */
object FongMiConfigParser {

    private const val TAG = "FongMiConfigParser"

    /** 单站点配置 */
    data class SiteConfig(
        val key: String,
        val name: String,
        val type: Int,           // 0=CMS, 1=CMS+search, 3=jar(用CMS兼容)
        val api: String,         // CMS API 地址
        val searchApi: String,   // 搜索 API（type 1 专用，可空）
        val ext: String = "",    // 扩展参数（预留）
    )

    /** 解析 Config JSON，返回站点列表 */
    fun parse(configJson: String): List<SiteConfig> {
        return try {
            val root = JsonParser.parseString(configJson).asJsonObject
            val sitesArr = root.getAsJsonArray("sites")
            if (sitesArr != null) {
                // 多站点 Config
                parseSitesArray(sitesArr)
            } else {
                // 单站点 Config
                listOf(parseSingleSite(root))
            }
        } catch (e: Exception) {
            LogUtils.d(TAG, "parse failed: ${e.message}")
            emptyList()
        }
    }

    /** 解析多站点数组 */
    private fun parseSitesArray(sitesArr: JsonArray): List<SiteConfig> {
        return sitesArr.mapNotNull { el ->
            try {
                val obj = el.asJsonObject
                parseSiteObject(obj)
            } catch (e: Exception) {
                null
            }
        }
    }

    /** 解析单站点对象 */
    private fun parseSingleSite(root: JsonObject): SiteConfig {
        return parseSiteObject(root)
    }

    /** 解析单个站点对象（通用） */
    private fun parseSiteObject(obj: JsonObject): SiteConfig {
        val key = str(obj, "key", "id")
        val name = str(obj, "name", "title").ifBlank { key }
        val typeStr = str(obj, "type", "mode").ifBlank { "0" }
        val type = typeStr.toIntOrNull() ?: if (typeStr.lowercase() == "cms") 0 else 0
        val api = str(obj, "api", "apiUrl", "url")
        val searchApi = str(obj, "searchApi", "searchUrl")
        val ext = str(obj, "ext")
        return SiteConfig(
            key = key,
            name = name,
            type = type,
            api = api,
            searchApi = searchApi,
            ext = ext,
        )
    }

    /** 判断是否为 FongMi Config 格式（含 sites 数组） */
    fun isFongMiConfig(json: String): Boolean {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            root.has("sites")
        } catch (e: Exception) {
            false
        }
    }

    private fun str(obj: JsonObject, vararg keys: String): String {
        for (key in keys) {
            val v = obj.get(key) ?: continue
            try {
                if (!v.isJsonNull) {
                    val s = v.asString
                    if (s.isNotBlank()) return s
                }
            } catch (e: Exception) {
                continue
            }
        }
        return ""
    }
}
