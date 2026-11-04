package io.legado.app.data.video

import android.content.Context
import com.github.catvod.Init
import com.github.catvod.Proxy
import com.github.catvod.crawler.Spider
import com.github.catvod.utils.Util
import okhttp3.OkHttpClient
import okhttp3.Request
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import dalvik.system.DexClassLoader

/**
 * jar 爬虫（FongMi Spider）装载器。
 *
 * 参考薯条版 TvBoxJarLoader：
 * - jar 规格串 "url;md5;md5url"（md5/md5url 可选）
 * - 缓存到 cacheDir/jar/<md5>.jar
 * - DexClassLoader 父优先委托（宿主 com.github.catvod.* 优先于 jar 内同名类）
 * - csp_Xxx → com.github.catvod.spider.Xxx
 * - siteKey + init(context, ext) 实例化
 *
 * 宿主 catvod 表面（com.github.catvod.*）由 App 提供，jar 内只含 spider 实现。
 */
object JarSpiderLoader {

    private val lock = Any()

    /** jar 规格串 + 站点 api → (DexClassLoader, Spider class) */
    private val loaderCache = ConcurrentHashMap<String, Pair<DexClassLoader, Class<*>>>()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 装载并实例化 spider。
     *
     * @param jarSpec jar 规格串（"url" 或 "url;md5;md5url"）
     * @param siteApi 站点 api（csp_Xxx，用于定位 spider 类）
     * @param siteKey 站点 key（写入 spider.siteKey，供 spider 识别自身）
     * @param ext 扩展参数（写入 init 的 extend）
     */
    fun load(jarSpec: String, siteApi: String, siteKey: String = "", ext: String = ""): Spider {
        val jarFile = resolveJar(jarSpec)
            ?: throw IllegalStateException("jar 下载失败: $jarSpec")
        val cacheKey = "$jarSpec|$siteApi"
        val pair = loaderCache[cacheKey] ?: synchronized(lock) {
            loaderCache[cacheKey] ?: createLoader(jarFile, siteApi).also { loaderCache[cacheKey] = it }
        }
        val spider = pair.second.getDeclaredConstructor().newInstance() as Spider
        spider.siteKey = siteKey
        Init.set(appCtx)
        invokeSpiderInit(pair.first)
        invokeSpiderProxy(pair.first)
        spider.init(appCtx, ext)
        return spider
    }

    /** 清空已加载的 jar 缓存（源更新/删除时调用） */
    fun invalidate() {
        loaderCache.clear()
    }

    /** 解析 jar 规格串，返回本地缓存文件（必要时下载） */
    private fun resolveJar(jarSpec: String): File? {
        val parts = jarSpec.split(";")
        val url = parts[0].trim()
        val md5 = if (parts.size > 1) parts[1].trim() else ""
        val downloadUrl = if (parts.size > 2) parts[2].trim() else url
        val jarDir = File(appCtx.cacheDir, "jar")
        if (!jarDir.exists()) jarDir.mkdirs()
        val file = if (md5.isNotEmpty()) File(jarDir, "$md5.jar") else File(jarDir, Util.md5(url) + ".jar")
        if (file.exists() && file.length() > 0) return file
        return downloadJar(downloadUrl, file)
    }

    /** 流式下载 jar 到文件（二进制安全，不走 String 解码） */
    private fun downloadJar(url: String, file: File): File? {
        return try {
            httpClient.newCall(Request.Builder().url(url).get().build()).execute().use { res ->
                if (!res.isSuccessful) return null
                res.body?.byteStream()?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
            }
            if (file.length() > 0) file else null
        } catch (e: Exception) {
            null
        }
    }

    /** 创建 DexClassLoader 并加载 spider 类 */
    private fun createLoader(jarFile: File, siteApi: String): Pair<DexClassLoader, Class<*>> {
        Init.set(appCtx)
        val loader = DexClassLoader(
            jarFile.absolutePath,
            appCtx.cacheDir.absolutePath,
            null,
            appCtx.classLoader
        )
        val clsName = "com.github.catvod.spider." + siteApi.substringAfter("csp_")
        val cls = loader.loadClass(clsName)
        return loader to cls
    }

    /** 反射调用 jar 内 com.github.catvod.spider.Init.set(context)（jar 侧 Init 可选） */
    private fun invokeSpiderInit(loader: DexClassLoader) {
        try {
            val cls = loader.loadClass("com.github.catvod.spider.Init")
            cls.getMethod("set", Context::class.java).invoke(null, appCtx)
        } catch (_: Throwable) {
        }
    }

    /** 反射调用 jar 内 com.github.catvod.spider.Proxy.set(port)（jar 侧 Proxy 可选） */
    private fun invokeSpiderProxy(loader: DexClassLoader) {
        try {
            val cls = loader.loadClass("com.github.catvod.spider.Proxy")
            cls.getMethod("set", Int::class.javaPrimitiveType).invoke(null, Proxy.getPort())
        } catch (_: Throwable) {
        }
    }
}
