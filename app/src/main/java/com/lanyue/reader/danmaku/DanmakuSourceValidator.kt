package com.lanyue.reader.danmaku

import com.lanyue.reader.data.db.entity.DanmakuSourceEntity

/**
 * 4.10.1 / 4.10.6 弹幕源配置校验。
 *
 * 「必填只有 apiBase；保存时探活校验」这条在代码里此前是空的：DAO 只做入库，
 * 引擎直接 `enabledOrdered()` 取源，中间没有任何一层拦下明显写错的地址。
 * 结果是用户把 `https://example.com/api` 写成 `example.com/api`
 * 或漏掉协议头，保存成功、播放时静默不匹配，表现为「弹幕功能坏了」。
 */
object DanmakuSourceValidator {

    /** 保存前的校验结论。 */
    sealed class Verdict {
        /** 可以保存，且探活通过。 */
        data class Ok(val normalized: DanmakuSourceEntity) : Verdict()
        /** 可以保存，但探活失败：标记「未验证」，播放时自动重试。 */
        data class Unverified(
            val normalized: DanmakuSourceEntity,
            val reason: String
        ) : Verdict()
        /** 不可保存：缺必填或格式明显错误。 */
        data class Rejected(val reason: String) : Verdict()
    }

    const val MAX_NAME_CHARS = 60
    const val MAX_API_BASE_CHARS = 300

    /**
     * 规范化并校验一条配置。[probe] 返回 null 表示探活通过，否则为失败原因。
     *
     * 探活失败**不阻断保存**（4.10.6）：临时离线、限流、甚至源站正在重启，
     * 都会让一次探测失败。此时若禁止保存，用户改一条正确的地址却因为网络抖动
     * 被卡住，会反复重试并最终放弃这个功能。正确做法是存下来、标「未验证」，
     * 下次播放时自动重试。
     */
    fun validate(
        entity: DanmakuSourceEntity,
        probe: (String) -> String? = { null }
    ): Verdict {
        val name = entity.name.trim()
        val apiBase = entity.apiBase.trim()

        if (apiBase.isEmpty()) {
            return Verdict.Rejected("apiBase 不能为空")
        }
        if (apiBase.length > MAX_API_BASE_CHARS) {
            return Verdict.Rejected("apiBase 过长（> $MAX_API_BASE_CHARS 字符）")
        }
        val normalizedBase = normalizeBase(apiBase)
            ?: return Verdict.Rejected("apiBase 格式无效，需以 http:// 或 https:// 开头并包含主机名")
        if (name.length > MAX_NAME_CHARS) {
            return Verdict.Rejected("名称过长（> $MAX_NAME_CHARS 字符）")
        }

        val finalName = name.ifEmpty { defaultName(normalizedBase) }
        val normalized = entity.copy(name = finalName, apiBase = normalizedBase)

        // 未启用时不探活：保存一条备用源不该触发网络请求
        if (!entity.enabled) return Verdict.Ok(normalized)

        val failure = probe(normalizedBase)
        return if (failure == null) Verdict.Ok(normalized)
        else Verdict.Unverified(normalized, failure)
    }

    /**
     * 规范化 apiBase：补协议、去尾部斜杠、拒绝明显非 HTTP 的输入。
     *
     * 补 `https://` 是刻意的：用户手抄地址时最常见的就是漏协议头。
     * 但补完仍要校验主机名形态，否则 `api` 这种单词也会被补成
     * `https://api` 而通过校验，等到播放时才暴露。
     */
    fun normalizeBase(raw: String): String? {
        if (raw.isEmpty()) return null
        val withScheme = when {
            raw.startsWith("https://", true) -> raw
            raw.startsWith("http://", true) -> raw
            // 带其他协议（ftp://、file://）直接拒绝，不猜测用户意图
            raw.contains("://") -> return null
            else -> "https://$raw"
        }
        val scheme = withScheme.substringBefore("://")
        if (!scheme.equals("https", true) && !scheme.equals("http", true)) return null

        val rest = withScheme.substringAfter("://")
        if (rest.isEmpty()) return null
        val host = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (host.isEmpty()) return null
        // 端口段若存在必须是数字；主机名判断必须剥离端口，否则 localhost:8080
        // 会被当成「含冒号的单段主机名」而误拒
        val hostOnly = host.substringBefore(':')
        if (host.contains(':') && !isValidPort(host.substringAfter(':'))) return null
        // 主机名必须含点（域名或 IP）或为 localhost；单段单词视为无效
        if (!hostOnly.contains('.') && !hostOnly.equals("localhost", true)) return null
        if (hostOnly.startsWith('.') || hostOnly.endsWith('.')) return null
        if (host.any { it.isWhitespace() }) return null

        val path = rest.substring(host.length).trimEnd('/')
        val lowerScheme = if (scheme.equals("http", true)) "http" else "https"
        return "$lowerScheme://$host$path"
    }

    /** 端口段必须是 1..65535 的纯数字。空串、非数字、越界都算无效。 */
    private fun isValidPort(raw: String): Boolean {
        if (raw.isEmpty() || raw.length > 5) return false
        if (raw.any { !it.isDigit() }) return false
        val value = raw.toIntOrNull() ?: return false
        return value in 1..65535
    }

    private fun defaultName(base: String): String {
        val host = base.substringAfter("://").substringBefore('/')
        return host.ifEmpty { "弹幕源" }
    }

    /**
     * 4.10.6 播放期复用：库里标了「未验证」的源，起播时重试一次探活；
     * 通过则升级为已验证。失败的保持未验证，不影响本次播放的其他源。
     */
    fun shouldRetryProbe(entity: DanmakuSourceEntity): Boolean =
        entity.enabled && !entity.verified
}
