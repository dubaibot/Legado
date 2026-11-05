package com.lanyue.reader.source.video

import com.lanyue.reader.core.engine.NetworkDispatcher
import com.lanyue.reader.core.net.HttpRequest
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
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
 */
class LocalParseProxy(
    private val net: NetworkDispatcher,
    private val parses: List<TvBoxParser.ParseRule>
) {

    companion object {
        private const val LOOPBACK = "127.0.0.1"
        private const val ACCEPT_TIMEOUT_MS = 500
    }

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    @Volatile var port: Int = 0
        private set

    /** 会话 token 池：每次播放会话一个，播放结束移除。 */
    private val activeTokens = java.util.concurrent.ConcurrentHashMap<String, Session>()

    private class Session(val token: String, val episodeUrl: String)

    /** 启动服务；端口随机分配空闲端口。 */
    @Synchronized
    fun start() {
        if (running) return
        val socket = ServerSocket(0, 8, InetAddress.getByName(LOOPBACK))
        serverSocket = socket
        port = socket.localPort
        running = true
        Thread({ acceptLoop(socket) }, "local-parse-proxy").apply { isDaemon = true }.start()
    }

    /** 崩溃自恢复重启：先关旧 socket 再重开。 */
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
        activeTokens.clear()
    }

    /**
     * 为一次播放会话签发播放地址。
     * 返回 http://127.0.0.1:{port}/{token}/{encodedUrl}
     */
    fun createPlayUrl(episodeUrl: String): String {
        if (!running) start()
        val token = randomToken()
        activeTokens[token] = Session(token, episodeUrl)
        val encoded = java.net.URLEncoder.encode(episodeUrl, "UTF-8")
        return "http://" + LOOPBACK + ":" + port + "/" + token + "/" + encoded
    }

    /** 播放结束 token 失效。 */
    fun releasePlayUrl(token: String) {
        activeTokens.remove(token)
    }

    private fun randomToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            Thread({ handle(client) }, "local-parse-conn").apply { isDaemon = true }.start()
        }
    }

    private fun handle(client: Socket) {
        client.use { conn ->
            runCatching { conn.soTimeout = ACCEPT_TIMEOUT_MS * 10 }
            val input = BufferedInputStream(conn.inputStream)
            val requestLine = readLine(input) ?: return
            // 只接受 GET
            val parts = requestLine.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                writeSimple(conn, 405, "Method Not Allowed")
                return
            }

            val path = parts[1]
            val segments = path.trimStart('/').split("/", limit = 2)
            val token = segments.getOrNull(0).orEmpty()
            val encoded = segments.getOrNull(1).orEmpty()

            // 7.3 安全：token 校验请求来源为应用自身；未知 token 直接拒绝
            val session = activeTokens[token]
            if (session == null) {
                writeSimple(conn, 403, "Forbidden")
                return
            }

            val upstreamUrl = session.episodeUrl.ifBlank {
                java.net.URLDecoder.decode(encoded, "UTF-8")
            }

            // 7.3 上游统一走 NetworkDispatcher
            val resolved = runCatching { resolveUpstream(upstreamUrl) }.getOrNull()
            if (resolved == null) {
                writeSimple(conn, 502, "Parse Failed")
                return
            }
            writeRedirect(conn, resolved)
        }
    }

    /** 按 parses 配置并发/顺序轮询上游解析接口，取第一个可用结果。 */
    private suspend fun resolveUpstream(episodeUrl: String): String? {
        for (rule in parses.sortedBy { it.type }) {
            val candidate = when (rule.type) {
                0, 1 -> queryJsonParse(rule, episodeUrl)
                2 -> queryAggregate(rule, episodeUrl)
                3 -> null // type 3 走 Sniffer 池，不在本地代理内
                else -> null
            }
            if (!candidate.isNullOrBlank()) return candidate
        }
        return null
    }

    private suspend fun queryJsonParse(rule: TvBoxParser.ParseRule, episodeUrl: String): String? {
        val url = rule.url + java.net.URLEncoder.encode(episodeUrl, "UTF-8")
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
            obj.get("url")?.asString
        }.getOrNull()
    }

    private suspend fun queryAggregate(rule: TvBoxParser.ParseRule, episodeUrl: String): String? {
        // 聚合：本地代理先向上游聚合接口取候选列表，取第一个
        return queryJsonParse(rule, episodeUrl)
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (prev == '\r'.code && b == '\n'.code) return sb.dropLast(1).toString()
            sb.append(b.toChar())
            prev = b
            if (sb.length > 8192) return sb.toString()
        }
    }

    private fun writeSimple(socket: Socket, code: Int, text: String) {
        val body = text.toByteArray()
        val header = "HTTP/1.1 " + code + " " + text + "\r\n" +
            "Content-Length: " + body.size + "\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Connection: close\r\n\r\n"
        runCatching {
            socket.getOutputStream().write(header.toByteArray())
            socket.getOutputStream().write(body)
            socket.getOutputStream().flush()
        }
    }

    /** 标准响应：302 重定向到上游直链，Media3 会自行跟随。 */
    private fun writeRedirect(socket: Socket, upstream: String) {
        val header = "HTTP/1.1 302 Found\r\n" +
            "Location: " + upstream + "\r\n" +
            "Content-Length: 0\r\n" +
            "Connection: close\r\n\r\n"
        runCatching {
            socket.getOutputStream().write(header.toByteArray())
            socket.getOutputStream().flush()
        }
    }
}
