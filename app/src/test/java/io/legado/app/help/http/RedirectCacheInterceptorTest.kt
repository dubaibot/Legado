package io.legado.app.help.http

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class RedirectCacheInterceptorTest {

    private class RecordingChain(
        private val originalRequest: Request,
        private val finalRequest: Request
    ) : Interceptor.Chain {
        val proceeded = mutableListOf<Request>()

        override fun request(): Request = originalRequest

        override fun proceed(request: Request): Response {
            proceeded.add(request)
            return Response.Builder()
                .request(finalRequest)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("body".toResponseBody())
                .build()
        }

        override fun connection(): Connection? = null

        override fun call(): Call = throw UnsupportedOperationException("not used in test")

        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

        override fun connectTimeoutMillis(): Int = 10000

        override fun readTimeoutMillis(): Int = 10000

        override fun writeTimeoutMillis(): Int = 10000
    }

    private fun postRequest(url: String): Request =
        Request.Builder()
            .url(url)
            .post("formhash=abcd1234&subject=checkin".toRequestBody())
            .header("Referer", "https://forum.example.com/forum.php?mod=post&action=newthread&fid=130")
            .header("Cookie", "auth=aaaa1111bbbb; uid=123")
            .build()

    @Test
    fun postRequestsNeverUseRedirectCache() {
        val postUrl = "https://forum.example.com/forum.php?mod=post&action=newthread&fid=130"
        val finalUrl = "https://forum.example.com/forum.php?mod=viewthread&tid=9999"
        val finalRequest = Request.Builder().url(finalUrl).get().build()

        var chain = RecordingChain(postRequest(postUrl), finalRequest)
        RedirectCacheInterceptor.intercept(chain)
        assertEquals(1, chain.proceeded.size)
        assertEquals(postUrl, chain.proceeded[0].url.toString())

        chain = RecordingChain(postRequest(postUrl), finalRequest)
        RedirectCacheInterceptor.intercept(chain)
        assertEquals(1, chain.proceeded.size)
        assertEquals(
            "POST 请求不得被重定向缓存改写到历史跳转目标",
            postUrl,
            chain.proceeded[0].url.toString()
        )

        chain = RecordingChain(postRequest(postUrl), finalRequest)
        RedirectCacheInterceptor.intercept(chain)
        assertEquals(1, chain.proceeded.size)
        assertEquals(postUrl, chain.proceeded[0].url.toString())
    }
}
