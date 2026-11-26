package io.legado.app.help.video

import io.legado.app.constant.AppLog
import io.legado.app.help.http.getProxyClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * 解析 B 站书源产出的 `data:bilibili;base64,<aid>` 伪协议，换出可直接播放的媒体地址。
 *
 * 书源 ruleContent 常把 B 站视频 aid 编码成伪协议（如 `raw=data:bilibili;base64,MTE3NiU4N`，
 * base64 解码即 aid），短剧播放链路不认该协议，导致拿不到直链、选集为空、只能走抓包兜底后失败。
 * 本解析器：
 * 1. [parsePseudoUrl]：伪协议 → aid；
 * 2. [resolve]：aid → view 接口取 cid → playurl 接口取 durl[0].url 直链（免登录 480P）。
 *
 * 全程 [runCatching] 包裹，任何一步失败都返回 null，由调用方回落既有嗅探链路。
 */
object BiliDirectResolver {

    private const val VIEW_URL = "https://api.bilibili.com/x/web-interface/view"
    private const val PLAYURL_URL = "https://api.bilibili.com/x/player/playurl"
    private const val DEFAULT_REFERER = "https://www.bilibili.com/"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10) Legado/3.26"

    /**
     * 解析 `data:bilibili;base64,<aid>` 伪协议，返回 aid；非该协议或解码非纯数字返回 null。
     */
    fun parsePseudoUrl(url: String): String? {
        if (!url.contains("data:bilibili", ignoreCase = true)) return null
        val b64 = url.substringAfter("base64,", "")
            .substringBefore("?").trim().trim(',', ';', '"')
        return runCatching {
            String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
                .trim()
                .takeIf { it.all(Char::isDigit) }
        }.getOrNull()
    }

    /**
     * aid → 可直播的媒体地址：优先用调用方传入的 cid（通常来自 pageUrl），
     * 没有才回退 view 接口取 cid，再 playurl 取 durl[0].url（mp4/flv 直链）。
     * 失败返回 null。
     */
    suspend fun resolve(
        aid: String,
        cid: String? = null,
        referer: String = DEFAULT_REFERER
    ): String? {
        val realCid = cid?.takeIf { it.isNotBlank() }
            ?: runCatching { fetchCid(aid, referer) }.getOrNull()
            ?: return null
        AppLog.put("BiliDirect: resolve aid=$aid cid=$realCid")
        return runCatching { fetchPlayUrl(aid, realCid, referer) }.getOrNull()
            ?.also {
                AppLog.put("BiliDirect: 命中直链 len=${it.length} 域名=${it.split('/').getOrNull(2)}")
            }
    }

    /** view 接口取 cid：优先单 P 的 cid，其次 pages[0].cid。 */
    private suspend fun fetchCid(aid: String, referer: String): String? {
        AppLog.put("BiliDirect: fetchCid aid=$aid")
        val json = getJson("$VIEW_URL?aid=$aid", referer) ?: return null
        val data = json.optJSONObject("data") ?: return null
        data.optString("cid").takeIf { it.isNotBlank() }?.let { return it }
        return data.optJSONArray("pages")?.optJSONObject(0)?.optString("cid")
            ?.takeIf { it.isNotBlank() }
    }

    /** playurl 接口取直链：优先 durl[0].url，其次 dash 视频流 baseUrl。 */
    private suspend fun fetchPlayUrl(aid: String, cid: String, referer: String): String? {
        AppLog.put("BiliDirect: fetchPlayUrl aid=$aid cid=$cid")
        val json = getJson(
            "$PLAYURL_URL?avid=$aid&cid=$cid&qn=64&fnval=0&fnver=0&fourk=0",
            referer
        ) ?: return null
        if (json.optInt("code", -1) != 0) {
            AppLog.put("BiliDirect: playurl 业务码=${json.optInt("code", -1)} ${json.optString("message")}")
            return null
        }
        val data = json.optJSONObject("data") ?: return null
        data.optJSONArray("durl")?.optJSONObject(0)?.optString("url")
            ?.takeIf { it.isNotBlank() }?.let { return it }
        return data.optJSONArray("dash")?.optJSONObject(0)?.optString("baseUrl")
            ?.takeIf { it.isNotBlank() }
    }

    /** GET 请求并解析 JSON；先走带代理的客户端，失败再用标准 OkHttpClient 直连兜底。 */
    private suspend fun getJson(url: String, referer: String): JSONObject? {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("Referer", referer)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            val clients = listOf(getProxyClient(), okhttp3.OkHttpClient())
            for (client in clients) {
                val result = runCatching {
                    client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            AppLog.put("BiliDirect: HTTP ${resp.code} ${url.take(60)}")
                            null
                        } else {
                            resp.body?.string()?.let { txt ->
                                runCatching { JSONObject(txt) }.onFailure {
                                    AppLog.put("BiliDirect: JSON 解析失败, 前80字=${txt.take(80)}")
                                }.getOrNull()
                            }
                        }
                    }
                }.onFailure {
                    AppLog.put("BiliDirect: GET 失败 ${url.take(60)}: ${it.message}")
                }.getOrNull()
                if (result != null) return@withContext result
            }
            null
        }
    }
}
