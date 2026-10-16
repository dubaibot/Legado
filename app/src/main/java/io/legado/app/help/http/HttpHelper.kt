package io.legado.app.help.http

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.help.CacheManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.progress.ProgressManager.LISTENER
import io.legado.app.help.glide.progress.ProgressResponseBody
import io.legado.app.help.http.CookieManager.cookieJarHeader
import io.legado.app.model.ReadManga
import io.legado.app.utils.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

private val proxyClientCache: ConcurrentHashMap<String, OkHttpClient> by lazy {
    ConcurrentHashMap()
}

val cookieJar by lazy {
    object : CookieJar {

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return emptyList()
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            //临时保存 书源启用cookie选项再添加到数据库
            val cookieBuilder = StringBuilder()
            cookies.forEachIndexed { index, cookie ->
                if (index > 0) cookieBuilder.append(";")
                cookieBuilder.append(cookie.name).append('=').append(cookie.value)
            }
            val domain = NetworkUtils.getSubDomain(url.toString())
            CacheManager.putMemory("${domain}_cookieJar", cookieBuilder.toString())
        }

    }
}

val okHttpClient: OkHttpClient by lazy {
    val specs = arrayListOf(
        ConnectionSpec.MODERN_TLS,
        ConnectionSpec.COMPATIBLE_TLS,
        ConnectionSpec.CLEARTEXT
    )

    val builder = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        //.cookieJar(cookieJar = cookieJar)
        .sslSocketFactory(SSLHelper.unsafeSSLSocketFactory, SSLHelper.unsafeTrustManager)
        .retryOnConnectionFailure(true)
        .hostnameVerifier(SSLHelper.unsafeHostnameVerifier)
        .connectionSpecs(specs)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor(OkHttpExceptionInterceptor)
        .addInterceptor { chain ->
            val request = chain.request()
            val builder = request.newBuilder()
            if (request.header(AppConst.UA_NAME) == null) {
                builder.addHeader(AppConst.UA_NAME, AppConfig.userAgent)
            } else if (request.header(AppConst.UA_NAME) == "null") {
                builder.removeHeader(AppConst.UA_NAME)
            }
            builder.addHeader("Keep-Alive", "300")
            builder.addHeader("Connection", "Keep-Alive")
            builder.addHeader("Cache-Control", "no-cache")
            chain.proceed(builder.build())
        }
        .addNetworkInterceptor { chain ->
            var request = chain.request()
            val enableCookieJar = request.header(cookieJarHeader) != null

            if (enableCookieJar) {
                val requestBuilder = request.newBuilder()
                requestBuilder.removeHeader(cookieJarHeader)
                request = CookieManager.loadRequest(requestBuilder.build())
            }

            val networkResponse = chain.proceed(request)

            if (enableCookieJar) {
                CookieManager.saveResponse(networkResponse)
            }
            networkResponse
        }
    if (AppConfig.addressCache.isNotEmpty()) {
        builder.dns { hostname ->
            val cachedAddress = AppConfig.addressCache[hostname]
            cachedAddress ?: Dns.SYSTEM.lookup(hostname)
        }
    }
    if (AppConfig.isCronet) {
        if (Cronet.loader?.install() == true) {
            Cronet.interceptor?.let {
                builder.addInterceptor(it)
            }
        }
    }
    builder.addInterceptor(DecompressInterceptor)
    builder.build().apply {
        val okHttpName =
            OkHttpClient::class.java.name.removePrefix("okhttp3.").removeSuffix("Client")
        val executor = dispatcher.executorService as ThreadPoolExecutor
        val threadName = "$okHttpName Dispatcher"
        executor.threadFactory = ThreadFactory { runnable ->
            Thread(runnable, threadName).apply {
                isDaemon = false
                uncaughtExceptionHandler = OkhttpUncaughtExceptionHandler
            }
        }
    }
}

/**
 * FR-3: 视频流专用 OkHttpClient（强制 HTTP/1.1）
 * 根因：ExoPlayerHelper Range 嗅探请求用 okHttpClient（含 CronetInterceptor），
 *   Cronet 引擎走 HTTP/2 可能触发 ERR_HTTP2_PROTOCOL_ERROR
 *
 * 方案：基于 okHttpClient.newBuilder() 继承所有拦截器（SSLHelper/DohDns/缓存等），
 *   protocols 强制 HTTP/1.1，规避 HTTP/2 协议错误；Cronet 失败会降级到 OkHttp（HTTP/1.1）
 */
val videoStreamClient: OkHttpClient by lazy {
    okHttpClient.newBuilder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()
}

val okHttpClientManga by lazy {
    okHttpClient.newBuilder().run {
        val interceptors = interceptors()
        interceptors.add(1) { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            val url = request.url.toString()
            response.newBuilder()
                .body(ProgressResponseBody(url, LISTENER, response.body))
                .build()
        }
        interceptors.add(1) { chain ->
            ReadManga.rateLimiter.withLimitBlocking {
                chain.proceed(chain.request())
            }
        }
        build()
    }
}

/**
 * 缓存代理okHttp
 */
fun getProxyClient(proxy: String? = null): OkHttpClient {
    if (proxy.isNullOrBlank()) {
        return okHttpClient
    }
    proxyClientCache[proxy]?.let {
        return it
    }
    val r = Regex("(http|socks4|socks5)://(.*):(\\d{2,5})(@.*@.*)?")
    val ms = r.findAll(proxy)
    val group = ms.first()
    var username = ""       //代理服务器验证用户名
    var password = ""       //代理服务器验证密码
    val type = if (group.groupValues[1] == "http") "http" else "socks"
    val host = group.groupValues[2]
    val port = group.groupValues[3].toInt()
    if (group.groupValues[4] != "") {
        username = group.groupValues[4].split("@")[1]
        password = group.groupValues[4].split("@")[2]
    }
    if (host != "") {
        val builder = okHttpClient.newBuilder()
        if (type == "http") {
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)))
        } else {
            builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port)))
        }
        if (username != "" && password != "") {
            builder.proxyAuthenticator { _, response -> //设置代理服务器账号密码
                val credential: String = Credentials.basic(username, password)
                response.request.newBuilder()
                    .header("Proxy-Authorization", credential)
                    .build()
            }
        }
        val proxyClient = builder.build()
        proxyClientCache[proxy] = proxyClient
        return proxyClient
    }
    return okHttpClient
}

// 多源合并 D6（对齐 m 源 warmUpConnection）：成功日志 1/20 采样计数
private val preconnectOkCount = java.util.concurrent.atomic.AtomicInteger(0)

/**
 * 预连接/DNS 预解析（多源合并 D6，对齐 m 源 warmUpConnection）：
 * HEAD 请求提前建立 TCP+TLS 连接，复用到后续 GET 请求
 *
 * 适用场景：列表/目录解析完成后，对前 N 篇 URL 发起预连接，点击时减少 300-1000ms
 * 失败处理：kotlin.runCatching 捕获异常，失败不影响列表显示
 * 已知上限：HEAD 请求无 body 开销，复用连接池
 */
suspend fun warmUpConnection(url: String) = withContext(Dispatchers.IO) {
    if (url.isBlank()) return@withContext
    // 列表解析链路会把源规则里的相对路径（如 /book/xxx）传进来，
    // 无 scheme 时 OkHttp 必抛 Expected URL scheme 'http' or 'https'，预连接 100% 失败，直接跳过
    if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return@withContext
    kotlin.runCatching {
        val request = okhttp3.Request.Builder()
            .url(url)
            .head()
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            // 仅触发连接建立，不关心响应内容；成功路径 1/20 采样，避免日志噪音
            if (preconnectOkCount.incrementAndGet() % 20 == 1) {
                AppLog.put("HttpHelper 预连接完成: code=${response.code}")
            }
        }
    }.onFailure {
        AppLog.put("HttpHelper 预连接失败: ${it.message?.take(100)}", it)
    }
}