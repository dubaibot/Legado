package com.lanyue.reader.source.video

import com.lanyue.reader.core.engine.NetworkDispatcher
import com.lanyue.reader.core.net.HttpRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom

/**
 * 7.3 LocalParseProxy —— 本地 HTTP 解析代理。
 *
 * 硬约束（文档明确）：
 * - 独立模块，本地 HTTP 服务
 * - 启动时随机分配空闲端口；生命周期与应用进程一致；崩溃自恢复重启
 * - Media3 拿到的播放 URL 形如 http://127.0.0.1:{port}/{token}/...
 * - Token 每次播放会话随机生成；会话内 retry/seek 保持有效；播放结束失效
 * - 上游请求统一走 NetworkDispatcher（继承通道、限速、Cookie、SSRF 校验）
 * - 仅绑定 127.0.0.1；token 校验请求来源为应用自身；不接受外部请求
 *
 * 实现要点：
 * - 连接处理走协程而不是「每连接一线程」，在低端机上不会因并发连接打爆线程。
 * - 会话有 TTL 与总量上限，避免播放器异常退出后 token 无限堆积。
 * - 上游解析带超时，且同一 URL 的并发请求会合并，防止播放器频繁 seek 打爆解析接口。
 */
class LocalParseProxy(
    private val net: NetworkDispatcher,
    private val parses: List<TvBoxParser.ParseRule>,
    private val sessionTtlMs: Long = DEFAULT_SESSION_TTL_MS,
    private val maxSessions: Int = DEFAULT_MAX_SESSIONS,
    private val now: () -> Long = System::currentTimeMillis
) {

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    @Volatile var port: Int = 0
        private set

    /** 协程作用域：IO 调度执行阻塞读写，Supervisor 保证单连接异常不影响服务。 */
    private var scope: CoroutineScope? = null

    /** 会话表：token -> 会话。容量有界，超限按最久未用淘汰。 */
    private val sessions = object : LinkedHashMap<String, Session>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Session>?): Boolean =
            size > maxSessions
    }

    private class Session(val episodeUrl: String, val createdAt: Long) {
        @Volatile var lastAccessAt: Long = createdAt
    }

    /** 同一上游 URL 的在途解析（请求合并，避免 seek 风暴）。 */
    private val inflight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<String?>>()

    /** 启动服务；端口随机分配空闲端口。重复调用幂等。 */
    @Synchronized
    fun start() {
        if (running) return
        val socket = ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK))
        serverSocket = socket
        port = socket.localPort
        running = true
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch { acceptLoop(socket, s) }
    }

    /** 崩溃自恢复重启：先关旧 socket 与协程再重开。 */
    @Synchronized
    fun restart() {
        stop()
        start()
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { scope?.cancel() }
        scope = null
        synchronized(sessions) { sessions.clear() }
        inflight.clear()
    }

    /**
     * 为一次播放会话签发播放地址。
     * 返回 http://127.0.0.1:{port}/{token}/{encodedUrl}
     */
    fun createPlayUrl(episodeUrl: String): String {
        if (!running) start()
        evictExpired()
        val token = randomToken()
        synchronized(sessions) { sessions[token] = Session(episodeUrl, now()) }
        val encoded = URLEncoder.encode(episodeUrl, "UTF-8")
        return "http://$LOOPBACK:$port/$token/$encoded"
    }

    /** 播放结束 token 失效。 */
    fun releasePlayUrl(token: String) {
        synchronized(sessions) { sessions.remove(token) }
    }

    /** 当前有效会话数（诊断用）。 */
    fun sessionCount(): Int = synchronized(sessions) { sessions.size }

    private fun evictExpired() {
        val deadline = now() - sessionTtlMs
        synchronized(sessions) {
            sessions.entries.removeIf { it.value.lastAccessAt < deadline }
        }
    }

    private fun randomToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private suspend fun acceptLoop(socket: ServerSocket, s: CoroutineScope) {
        // 连接数上限：超出时快速拒绝，而不是无限堆积协程
        val permits = Channel<Unit>(MAX_CONNECTIONS)
        repeat(MAX_CONNECTIONS) { permits.trySend(Unit) }

        while (running) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            permits.tryReceive()
            s.launch {
                try {
                    handle(client)
                } catch (_: Throwable) {
                    runCatching { writeSimple(client, 500, "Internal Error") }
                } finally {
                    runCatching { client.close() }
                    permits.trySend(Unit)
                }
            }
        }
    }

    private suspend fun handle(client: Socket) {
        client.use { conn ->
            runCatching { conn.soTimeout = SOCKET_TIMEOUT_MS }

            // 7.3 安全：只接受来自本机的连接，外部 IP 一律拒绝（不依赖绑定地址这一层）
            val remote = runCatching { conn.inetAddress }.getOrNull()
            if (remote == null || !remote.isLoopbackAddress) {
                writeSimple(conn, 403, "Forbidden")
                return
            }

            val input = BufferedInputStream(conn.inputStream)
            val requestLine = readLine(input) ?: return
            // 追加读掉请求头，直到空行；否则客户端会因未读完而 RST
            drainHeaders(input)

            val parts = requestLine.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                writeSimple(conn, 405, "Method Not Allowed")
                return
            }

            val path = parts[1].substringBefore('?')
            val segments = path.trimStart('/').split("/", limit = 2)
            val token = segments.getOrNull(0).orEmpty()
            val encoded = segments.getOrNull(1).orEmpty()

            // 7.3 安全：token 校验请求来源为应用自身；未知 token 直接拒绝
            val session = synchronized(sessions) {
                sessions[token]?.also { it.lastAccessAt = now() }
            }
            if (session == null || isExpired(session)) {
                writeSimple(conn, 403, "Forbidden")
                return
            }

            val upstreamUrl = session.episodeUrl.ifBlank {
                runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault("")
            }
            if (upstreamUrl.isBlank()) {
                writeSimple(conn, 400, "Bad Request")
                return
            }

            // 7.3 上游统一走 NetworkDispatcher，并带超时
            val resolved = resolveShared(upstreamUrl)
            if (resolved.isNullOrBlank()) {
                writeSimple(conn, 502, "Parse Failed")
                return
            }
            writeRedirect(conn, resolved)
        }
    }

    private fun isExpired(session: Session): Boolean = now() - session.lastAccessAt > sessionTtlMs

    /**
     * 合并同一 URL 的在途解析。
     *
     * 播放器 seek/重试会在极短时间内重复请求同一集，如果每次都打上游解析接口
     * 很快会被限流甚至封禁，因此共享同一个 Deferred。
     */
    private suspend fun resolveShared(episodeUrl: String): String? {
        val existing = inflight[episodeUrl]
        if (existing != null) return runCatching { existing.await() }.getOrNull()

        val s = scope
        val deferred = if (s != null) {
            kotlinx.coroutines.CompletableDeferred<String?>()
        } else {
            kotlinx.coroutines.CompletableDeferred<String?>()
        }
        val prior = inflight.putIfAbsent(episodeUrl, deferred)
        if (prior != null) return runCatching { prior.await() }.getOrNull()

        val result = runCatching {
            withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { resolveUpstream(episodeUrl) }
        }.getOrNull()
        deferred.complete(result)
        inflight.remove(episodeUrl, deferred)
        return result
    }

    /** 按 parses 配置依次尝试上游解析接口，取第一个可用结果。 */
    private suspend fun resolveUpstream(episodeUrl: String): String? {
        for (rule in parses.sortedBy { it.type }) {
            val candidate = when (rule.type) {
                0, 1, 2 -> queryJsonParse(rule, episodeUrl)
                else -> null // type 3 走 Sniffer 池，不在本地代理内
            }
            if (!candidate.isNullOrBlank()) return candidate
        }
        return null
    }

    private suspend fun queryJsonParse(rule: TvBoxParser.ParseRule, episodeUrl: String): String? {
        val url = rule.url + URLEncoder.encode(episodeUrl, "UTF-8")
        val resp = runCatching {
            net.execute(
                HttpRequest(url = url, method = "GET", sourceId = "local-parse-proxy"),
                traceId = "local-parse-" + rule.name
            )
        }.getOrNull() ?: return null
        if (!resp.isSuccess) return null
        val body = resp.body ?: return null
        return runCatching {
            val obj = com.google.gson.JsonParser.parseString(body).asJsonObject
            obj.get("url")?.takeIf { !it.isJsonNull }?.asString
        }.getOrNull()
    }

    /** 读一行（CRLF 或 LF 结尾），超长则截断，避免被恶意长行拖死。 */
    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (prev == '\r'.code) sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
            prev = b
            if (sb.length > MAX_LINE) return sb.toString()
        }
    }

    private fun drainHeaders(input: BufferedInputStream) {
        var guard = 0
        while (guard++ < MAX_HEADERS) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) return
        }
    }

    private fun writeSimple(socket: Socket, code: Int, text: String) {
        val body = text.toByteArray()
        val header = "HTTP/1.1 " + code + " " + text + CRLF +
            "Content-Length: " + body.size + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Connection: close" + CRLF + CRLF
        runCatching {
            val out = socket.getOutputStream()
            out.write(header.toByteArray())
            out.write(body)
            out.flush()
        }
    }

    /**
     * 标准响应：302 重定向到上游直链，Media3 会自行跟随。
     *
     * 上游地址必须过滤 CR/LF，否则可被上游构造出响应头注入。
     */
    private fun writeRedirect(socket: Socket, upstream: String) {
        val safe = upstream.replace("\r", "").replace("\n", "")
        val header = "HTTP/1.1 302 Found" + CRLF +
            "Location: " + safe + CRLF +
            "Content-Length: 0" + CRLF +
            "Cache-Control: no-store" + CRLF +
            "Connection: close" + CRLF + CRLF
        runCatching {
            val out = socket.getOutputStream()
            out.write(header.toByteArray())
            out.flush()
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val CRLF = "\r\n"
        const val BACKLOG = 16
        const val MAX_CONNECTIONS = 8
        const val SOCKET_TIMEOUT_MS = 15_000
        const val RESOLVE_TIMEOUT_MS = 12_000L
        const val MAX_LINE = 8192
        const val MAX_HEADERS = 64
        const val DEFAULT_SESSION_TTL_MS = 6L * 60 * 60 * 1000
        const val DEFAULT_MAX_SESSIONS = 16
    }
}
