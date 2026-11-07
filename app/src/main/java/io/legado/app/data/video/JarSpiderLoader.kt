package io.legado.app.data.video

import android.content.Context
import android.os.Build
import com.github.catvod.Init
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
 * - Spider 实例由 jar 侧 Init.getSpider(siteApi) 工厂产出（tvbox 惯例）
 * - 受保护 jar：自动抽取 assets/ftyguard_vX.so 并 System.load，随后反射
 *   Init.init(Context)（jar 侧唯一公有初始化入口）
 * - siteKey + init(context, ext) 实例化
 *
 * 宿主 catvod 表面（com.github.catvod.*）由 App 提供，jar 内只含 spider 实现。
 */
object JarSpiderLoader {

    private val lock = Any()
    private val lockNative = Any()

    /** 已加载的 native guard 指纹（jar 内容变化时重新加载） */
    private val nativeGuards = mutableSetOf<String>()

    /** jar 规格串 + 站点 api → (DexClassLoader, 已就绪的 jar 侧 Init 类) */
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
     * 受保护 jar（DexNative/guard）的调用契约（经字节码取证）：
     * 1. init.classLoader() = 单例的类加载器（Init.<init> 自动注入），classLoader() 返回该实例；
     *    DexNative.<clinit> 会用 getResourceAsStream("assets/ftyguard_vX.so") 抽取并
     *    System.load——但 BaseDexClassLoader 无法读取 jar 内 assets，因此宿主必须
     *    在 DexNative 首次被触碰前自行从 jar zip 抽取同款 .so 并 System.load。
     *    clinit 内部任何异常都被静默吞掉，Native 方法真正需要 .so 已加载完成。
     * 2. Init.init(Context) 仅存 Application 字段并调用 native DexNative.getLoader(ctx)，
     *    loader(DexClassLoader) 字段由 native 返回对象填充——Host 无需自建 DexClassLoader
     *    （tvbox 惯例：DexClassLoader 创建后立即 init(ctx)，getSpider 产生蜘蛛实例）。
     * 3. Spider 实例一律由 Init.getSpider(api) 工厂产出，spider 内部的 siteKey/init
     *    仍需调用方设置。
     *
     * @param jarSpec jar 规格串（"url" 或 "url;md5;md5url"）
     * @param siteApi 站点 api（csp_Xxx，传入 jar 的 spider 工厂）
     * @param siteKey 站点 key（写入 spider.siteKey，供 spider 识别自身）
     * @param ext 扩展参数（写入 init 的 extend）
     */
    fun load(jarSpec: String, siteApi: String, siteKey: String = "", ext: String = ""): Spider {
        val jarFile = resolveJar(jarSpec)
        val cacheKey = "$jarSpec|$siteApi"
        val pair = loaderCache[cacheKey] ?: synchronized(lock) {
            loaderCache[cacheKey] ?: run {
                Init.set(appCtx)
                val loader = DexClassLoader(
                    jarFile.absolutePath,
                    appCtx.cacheDir.absolutePath,
                    null,
                    appCtx.classLoader
                )
                // 受保护 jar：DexNative.<clinit> 依赖 assets/ftyguard_vX.so，
                // DexClassLoader 无法读取 jar assets，宿主先抽取并 System.load
                ensureJarNativeLib(jarFile)
                // jar 侧 Init 是受保护 jar 唯一公有初始化入口，getSpider 为其工厂
                val init = loader.loadClass("com.github.catvod.spider.Init")
                invokeJarInit(init)
                loaderCache[cacheKey] = loader to init
                return@run loader to init
            }
        }
        // 每个源都从工厂取新实例（spider 缓存里已有实例会串 siteKey）
        val spider = try {
            val obj = pair.second.getMethod("getSpider", String::class.java).invoke(null, siteApi)
            obj as Spider
        } catch (e: Exception) {
            val target = (e as? java.lang.reflect.InvocationTargetException)?.targetException
            val cause = target ?: e
            throw IllegalStateException(
                "jar spider 工厂失败 (getSpider(\"$siteApi\")): ${cause.javaClass.simpleName}: ${cause.message}", e
            )
        }
        spider.siteKey = siteKey
        try {
            spider.init(appCtx, ext)
        } catch (e: Exception) {
            throw IllegalStateException("jar spider init 失败: ${e.javaClass.simpleName}: ${e.message}", e)
        }
        return spider
    }

    /** 清空已加载的 jar 缓存（源更新/删除时调用） */
    fun invalidate() {
        loaderCache.clear()
    }

    /** 解析 jar 规格串，返回本地缓存文件（必要时下载） */
    private fun resolveJar(jarSpec: String): File {
        val parts = jarSpec.split(";").map { it.trim() }
        val url = parts.getOrNull(0).orEmpty()
        val md5 = parts.getOrNull(1).orEmpty()
        // 第三段为备用下载地址（FongMi 规格 jar;md5;备用地址）；仅接受合法 URL
        // （部分配置把 md5 重复填在第三段，非 URL 时忽略）
        val backupUrl = parts.getOrNull(2).orEmpty().takeIf {
            it.startsWith("http://") || it.startsWith("https://")
        }
        val jarDir = File(appCtx.cacheDir, "jar")
        if (!jarDir.exists()) jarDir.mkdirs()
        // md5 仅作为缓存文件名；含非 hex 字符（配置污染）时改用 url 的 md5 避免非法文件名
        val cacheName = if (md5.length == 32 && md5.all {
            it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
        }) {
            md5.lowercase()
        } else {
            Util.md5(url)
        }
        val file = File(jarDir, "$cacheName.jar")
        if (file.exists() && file.length() > 0) {
            makeReadOnly(file)
            return file
        }
        return try {
            downloadJar(url, backupUrl.orEmpty(), file, md5)
        } catch (e: Exception) {
            throw IllegalStateException(
                "jar 下载失败: ${e.message ?: e.javaClass.simpleName}" +
                    (backupUrl?.takeIf { it.isNotBlank() && it != url }?.let { " 备用: $it" } ?: "") +
                    " ($url)"
            )
        }
    }

    /** 流式下载 jar 到文件（二进制安全）；主地址失败时尝试备用地址 */
    private fun downloadJar(url: String, backupUrl: String, file: File, expectedMd5: String): File {
        val candidates = mutableListOf(url)
        backupUrl.takeIf { it.isNotBlank() && it != url }?.let { candidates.add(it) }
        var lastError = "未尝试任何地址"
        for (u in candidates) {
            var downloaded = false
            var attemptError = ""
            try {
                val request = Request.Builder().url(u).get()
                    .header("User-Agent", Util.CHROME)
                    .build()
                httpClient.newCall(request).execute().use { res ->
                    if (!res.isSuccessful) {
                        attemptError = "HTTP ${res.code} ($u)"
                        return@use
                    }
                    res.body?.byteStream()?.use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                downloaded = file.length() > 0
                if (!downloaded) attemptError = "下载内容为空 ($u)"
            } catch (e: Exception) {
                attemptError = "${u}: ${e.message ?: e.javaClass.simpleName}"
            }
            if (downloaded) {
                // md5 校验（仅当 spec 内 md5 为合法 32 位 hex；污染值跳过，避免误伤）
                if (expectedMd5.length == 32 && expectedMd5.all {
                    it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
                }) {
                    val actual = Util.md5(file)
                    if (actual != expectedMd5.lowercase()) {
                        file.delete()
                        throw IllegalStateException("MD5 校验失败: 期望 ${expectedMd5.lowercase()} 实际 $actual")
                    }
                }
                // ART 禁止加载可写的 dex 文件：置只读后返回
                makeReadOnly(file)
                return file
            }
            // 清理半成品，尝试下一个地址
            if (file.exists()) file.delete()
            lastError = attemptError
        }
        throw IllegalStateException(lastError)
    }

    /** Android 不允许 DexClassLoader 加载带写位的文件，置为只读（chmod 444） */
    private fun makeReadOnly(file: File) {
        try {
            if (!file.setReadOnly()) {
                Runtime.getRuntime().exec(arrayOf("chmod", "444", file.absolutePath))
            }
        } catch (_: Exception) {
        }
    }

    /**
     * 反射调用 jar 侧 com.github.catvod.spider.Init.init(Context)。
     *
     * 这是受保护 jar 唯一公有初始化入口：内部把 Application 存入单例字段，
     * 随后调用 native DexNative.getLoader(ctx)，native 返回的 DexClassLoader
     * 被写入 loader 字段供 Init.getSpider 使用。此调用必须成功——失败即
     * DexNative 无法建立（.so 未加载 + 单例字段为 null）。
     */
    private fun invokeJarInit(init: Class<*>) {
        try {
            init.getMethod("init", Context::class.java).invoke(null, appCtx)
        } catch (e: Exception) {
            val target = (e as? java.lang.reflect.InvocationTargetException)?.targetException
            val cause = target ?: e
            throw IllegalStateException(
                "jar 侧 Init.init(Context) 失败: ${cause.javaClass.simpleName}: ${cause.message}", e
            )
        }
    }

    /**
     * 抽取受保护 jar 的 native guard .so 并 System.load。
     *
     * jar 内 DexNative.<clinit> 约定：Init.classLoader().getResourceAsStream
     * ("assets/ftyguard_vX.so") 抽取并 System.load——BaseDexClassLoader 不提供
     * jar assets 读取能力，会静默失败。宿主在 DexClassLoader/JarInit 之前自行完成
     * 同样的抽取+加载（与 DexNative 一致：CPU_ABI 含 "64" 选 v8 否则 v7），
     * 之后 DexNative 的静默失败不再有影响（.so 已全局注册）。
     *
     * 文件名采用内容寻址（ftyguard_v8.<md5前12位>.so）：ART 按规范化路径登记已加载
     * 的 .so，同路径二次 System.load 会抛
     * "JNI_OnLoad failed on a previous attempt to load"——多个 jar 共用同一
     * libName 但内容不同（守护等级不同/jar 迭代）时会触发，故不同内容各占独立路径，
     * 相同 digest 复用同一文件且只加载一次。
     */
    private fun ensureJarNativeLib(jarFile: File) {
        if (!jarFile.exists()) return
        val cpuAbi = Build.CPU_ABI.orEmpty()
        val libName = if (cpuAbi.contains("64")) "ftyguard_v8.so" else "ftyguard_v7.so"
        val bytes = try {
            java.util.zip.ZipFile(jarFile).use { zip ->
                zip.getEntry("assets/$libName")?.let { entry ->
                    zip.getInputStream(entry).use { it.readBytes() }
                }
            }
        } catch (_: Exception) {
            return
        } ?: return
        val digest = java.security.MessageDigest.getInstance("MD5").digest(bytes)
        val hex = digest.joinToString("") { "%02x".format(it) }
        val key = "$libName|${bytes.size}|$hex"
        val short = hex.take(12)
        synchronized(lockNative) {
            if (nativeGuards.contains(key)) return
            try {
                val dir = File(appCtx.cacheDir, "spider-native")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "$libName.$short.so")
                if (!file.exists()) {
                    file.writeBytes(bytes)
                    file.setReadOnly()
                }
                System.load(file.absolutePath)
                nativeGuards.add(key)
            } catch (e: Exception) {
                throw IllegalStateException(
                    "native guard .so 加载失败 ($libName): ${e.javaClass.simpleName}: ${e.message}", e
                )
            }
        }
    }
}
