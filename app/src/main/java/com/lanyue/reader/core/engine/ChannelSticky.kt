package com.lanyue.reader.core.engine

import java.security.MessageDigest

/** 4.6 三通道。 */
enum class NetChannel { OKHTTP, CRONET, WEBVIEW }

/**
 * 通道粘滞所需的持久化能力。
 *
 * 刻意不直接依赖 android.content.SharedPreferences：
 * 网络调度是纯逻辑层，靠接口反转依赖后可以在 JVM 单测里用内存实现验证
 * 「2 次命中才切换」「7 天过期」「源删除清理」等规则。
 * Android 侧由 SharedPreferencesStickyStore 适配。
 */
interface StickyKeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun keys(): Set<String>
}

/** 内存实现：单测与降级场景用，进程重启即失效。 */
class InMemoryStickyStore : StickyKeyValueStore {
    private val map = LinkedHashMap<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
    override fun keys(): Set<String> = map.keys.toSet()
}

/**
 * 4.6 通道粘滞。
 * - 粘滞 key = 域名 + sourceId 哈希（明确不使用 UA 哈希）
 * - 连续 2 次命中指纹拦截特征才更新绑定并埋点
 * - 降级后同源后续请求直接走新通道
 * - 持久化 7 天
 * - 源删除时清理；源编辑保存 diff baseUrl/UA 变化时清理重探测
 */
class ChannelStickyStore(
    private val store: StickyKeyValueStore,
    private val now: () -> Long = System::currentTimeMillis
) {

    private val ttlMs = 7L * 24 * 60 * 60 * 1000
    private val hitCounts = mutableMapOf<String, Int>()

    /** sourceId 取 SHA-256 前 16 位，避免把完整 id 写进键里。 */
    private fun digestOf(sourceId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(sourceId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)

    fun stickyKey(host: String, sourceId: String): String = "$host#${digestOf(sourceId)}"

    fun channelFor(host: String, sourceId: String): NetChannel? {
        val key = stickyKey(host, sourceId)
        val raw = store.get(key) ?: return null
        val parts = raw.split('|')
        if (parts.size != 2) return null
        val channel = runCatching { NetChannel.valueOf(parts[1]) }.getOrNull() ?: return null
        val savedAt = parts[0].toLongOrNull() ?: return null
        if (now() - savedAt > ttlMs) {
            store.remove(key)
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
        if (count < REQUIRED_HITS) {
            hitCounts[key] = count
            return false
        }
        hitCounts[key] = 0
        store.put(key, "${now()}|${newChannel.name}")
        return true
    }

    fun resetHitCount(host: String, sourceId: String) {
        hitCounts.remove(stickyKey(host, sourceId))
    }

    /** 源被删除时同步清理该源全部粘滞键与计数。 */
    fun clearForSource(sourceId: String) {
        val suffix = "#${digestOf(sourceId)}"
        store.keys().filter { it.endsWith(suffix) }.forEach { store.remove(it) }
        hitCounts.keys.filter { it.endsWith(suffix) }.forEach { hitCounts.remove(it) }
    }

    /**
     * 源编辑保存时调用：baseUrl 或 UA 变化 → 清键重探测。
     * UA 变化会改变站点指纹响应，保留旧粘滞会指向已不适用的通道。
     */
    fun onSourceEdited(host: String, sourceId: String, baseUrlChanged: Boolean, uaChanged: Boolean) {
        if (!baseUrlChanged && !uaChanged) return
        val key = stickyKey(host, sourceId)
        store.remove(key)
        hitCounts.remove(key)
    }

    private companion object {
        /** 连续命中阈值：2 次才认定站点确有指纹拦截，避免偶发抖动误切通道。 */
        const val REQUIRED_HITS = 2
    }
}
