package com.lanyue.reader.core.net

/** 4.6 统一请求描述。三通道共享同一入参，避免各通道各自发明字段。 */
data class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val sourceId: String = "",
    val timeoutMs: Long = 20_000L
) {
    val host: String get() = runCatching { java.net.URI(url).host ?: "" }.getOrDefault("")
}

/** 4.6 统一响应描述。 */
data class HttpResponse(
    val code: Int,
    val body: String?,
    val headers: Map<String, String> = emptyMap(),
    val finalUrl: String = "",
    val channel: String = ""
) {
    val isSuccess: Boolean get() = code in 200..299
}

/** 4.6 指纹拦截特征判定依据。 */
data class FingerprintObservation(
    val statusCode: Int,
    val bodyPreview: String?,
    val tlsHandshakeFailed: Boolean,
    val certificateConfigError: Boolean
)

/**
 * 4.6 通道执行契约：OkHttp/Cronet/WebView 三通道统一入参。
 *
 * 网络调度依赖本接口而非具体实现，便于 JVM 单测注入假通道验证
 * 降级链、Cookie 注入回写等执行期行为。
 */
interface HttpChannel {
    suspend fun execute(req: HttpRequest): HttpResponse
}
