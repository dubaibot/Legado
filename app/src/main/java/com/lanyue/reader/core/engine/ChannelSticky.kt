package com.lanyue.reader.core.engine

import android.content.SharedPreferences
import java.security.MessageDigest

/** 4.6 三通道。 */
enum class NetChannel { OKHTTP, CRONET, WEBVIEW }

/**
 * 4.6 通道粘滞。
 * - 粘滞 key = 域名 + sourceId 哈希（明确不使用 UA 哈希）
 * - 连续 2 次命中指纹拦截特征才更新绑定并埋点
 * - 降级后同源后续请求直接走新通道
 * - 持久化 7 天
 * - 源删除时清理；源编辑保存 diff baseUrl/UA 变化时清理重探测
 */
class ChannelStickyStore(private val prefs: SharedPreferences) {

    private val ttlMs = 7L * 24 * 60 * 60 * 1000
    private val hitCounts = mutableMapOf<String, Int>()

    fun stickyKey(host: String, sourceId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sourceId.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
        return "$host#$digest"
    }

    fun channelFor(host: String, sourceId: String): NetChannel? {
        val key = stickyKey(host, sourceId)
        val raw = prefs.getString(key, null) ?: return null
        val parts = raw.split('|')
        if (parts.size != 2) return null
        val channel = runCatching { NetChannel.valueOf(parts[1]) }.getOrNull() ?: return null
        val savedAt = parts[0].toLongOrNull() ?: return null
        if (System.currentTimeMillis() - savedAt > ttlMs) {
            prefs.edit().remove(key).apply()
            return null
        }
        return channel
    }

    /**
     * 记录一次指纹拦截特征命中。
     * 返回 true 表示达到「连续 2 次」阈值，调用方应切换到 [newChannel] 并埋点。
     */
    fun recordFingerprintHit(host: String, sourceId: String, newChannel: NetChannel): Boolean {
        val key = stickyKey(host, sourceId)
        val count = (hitCounts[key] ?: 0) + 1
        hitCounts[key] = count
        if (count < 2) return false
        hitCounts[key] = 0
        prefs.edit().putString(key, "${System.currentTimeMillis()}|${newChannel.name}").apply()
        return true
    }

    fun resetHitCount(host: String, sourceId: String) {
        hitCounts.remove(stickyKey(host, sourceId))
    }

    /** 源被删除时同步清理该源全部粘滞键。 */
    fun clearForSource(sourceId: String) {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sourceId.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
        val suffix = "#$digest"
        prefs.all.keys.filter { it.endsWith(suffix) }.forEach { prefs.edit().remove(it).apply() }
        hitCounts.keys.filter { it.endsWith(suffix) }.forEach { hitCounts.remove(it) }
    }

    /**
     * 源编辑保存时调用：baseUrl 或 UA 变化 → 清键重探测。
     * UA 变化会改变站点指纹响应，保留旧粘滞会指向已不适用的通道。
     */
    fun onSourceEdited(host: String, sourceId: String, baseUrlChanged: Boolean, uaChanged: Boolean) {
        if (baseUrlChanged || uaChanged) {
            prefs.edit().remove(stickyKey(host, sourceId)).apply()
            hitCounts.remove(stickyKey(host, sourceId))
        }
    }
}
