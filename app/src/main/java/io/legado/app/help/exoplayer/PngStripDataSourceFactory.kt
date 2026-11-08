package io.legado.app.help.exoplayer

import androidx.annotation.Keep
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import io.legado.app.constant.AppLog
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * PNG 伪装 TS 数据源（2026-11-08）
 *
 * 背景：部分爬虫 jar 的 m3u8 分片 URL 实际指向 PNG 文件。
 * HTTP 返回的响应是合法 PNG（1x1 像素），真实 TS 载荷隐藏在 PNG IEND chunk 之后。
 * ExoPlayer 拿 PNG 头解析成 TS 必然失败。
 *
 * 实测样本 /tmp/opencode/png_test.bin：
 * 偏移 0-8：PNG 签名 89 50 4E 47 0D 0A 1A 0A
 * 偏移 8-33：IHDR chunk（4+4+13+4 = 25 字节）
 * 偏移 33-61：IDAT chunk（4+4+16+4 = 28 字节）
 * 偏移 61-73：IEND chunk（4+4+0+4 = 12 字节）
 * 偏移 73 起：TS 载荷（1,339,876 字节，以同步字节 0x47 开头，188 字节对齐）
 *
 * 方案：DataSource 包装层拦截 open()。
 * - 前 8 字节取 PNG 签名判定是否伪装
 * - PNG 伪装：逐 chunk 解析找 IEND，再校验首字节是同步字节 0x47
 * - 非 PNG：缓存所有消费字节作 prefix buffer，后续 read 先吐 prefix 再委派上游
 *
 * Prefix buffer 复用：无论 PNG 探测成功（同步字节还没吐回去）还是失败
 * （签名+chunk 试探失败回退），都从同一个 consumedBuf 吐字节，read() 逻辑统一。
 *
 * 兼容性：
 * - 非 PNG 响应零额外开销（仅前 8 字节 peek，后续 prefix 缓冲直接影响下游）
 * - PNG 伪装响应返回 (length - stripLen)，read 直接走上游（已越过 PNG 头部）
 * - IEND 偏移 > 4KB 判定不伪装（防真 PNG 误伤）
 *
 * 安全规范：日志只记录技术结论（strip 偏移、payload 长度），不输出完整 URL 和 Referer/Cookie
 */
@Keep
class PngStripDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
) : DataSource.Factory {

    override fun createDataSource(): DataSource {
        return PngStripDataSource(upstreamFactory.createDataSource())
    }

    class PngStripDataSource(
        private val upstream: DataSource,
    ) : BaseDataSource(true) {

        // 缓存所有已消费字节（PNG 探测期累积）：非 PNG 透传 / PNG 失败回退 / PNG 成功同步字节待吐
        private val consumedBuf = ByteArrayOutputStream()
        private var consumed = 0

        // PNG 探测成功：上游位置已在 TS 载荷起点（同步字节已经在 consumedBuf 里有待吐）
        private var stripLen = 0L

        override fun open(dataSpec: DataSpec): Long {
            consumedBuf.reset()
            consumed = 0
            stripLen = 0

            val upstreamLen = upstream.open(dataSpec)

            // Peek PNG 签名
            val sig = ByteArray(8)
            val readLen = upstream.read(sig, 0, 8)
            // -1 表示上游已结束（空响应）；返回 0 让下游看到 empty stream
            if (readLen < 0) {
                consumedBuf.reset()
                consumed = 0
                stripLen = 0
                return 0L
            }
            if (readLen > 0) {
                consumedBuf.write(sig, 0, readLen)
                consumed = readLen
            }

            if (readLen < 8 || !isPngSignature(sig)) {
                return upstreamLen
            }

            // PNG 签名命中：spectre 试探 - 摸索 chunk 结构找 IEND
            val chunkSkip = tryWalkingSource(upstream)
            if (chunkSkip < 0 || consumed + chunkSkip > MAX_PNG_HEADER_LEN) {
                AppLog.putDebug("PngStrip: PNG sig hit but IEND mismatch, passthrough")
                return upstreamLen
            }

            // 试探成功：继续校验同步字节
            val syncBuf = ByteArray(1)
            val syncRead = upstream.read(syncBuf, 0, 1)
            if (syncRead > 0) {
                consumedBuf.write(syncBuf, 0, syncRead)
                consumed += syncRead
            }
            if (syncRead != 1 || syncBuf[0] != TS_SYNC_BYTE) {
                AppLog.putDebug("PngStrip: sync byte not TS_SYNC, passthrough")
                return upstreamLen
            }

            // TS 伪装判定通过：剥离偏移 = 签名 + 全部 chunk 字节数
            // 清空缓冲区重放：只保留同步字节（作为 TS payload 首字节吐给 ExoPlayer）
            consumedBuf.reset()
            consumedBuf.write(syncBuf, 0, syncRead)
            consumed = syncRead
            stripLen = readLen.toLong() + chunkSkip

            AppLog.putDebug(
                "PngStrip: stripped PNG header, headerLen=$stripLen, " +
                    "payloadLen=${"%d".format(upstreamLen - stripLen)}"
            )
            return if (upstreamLen == C.LENGTH_UNSET.toLong()) {
                C.LENGTH_UNSET.toLong()
            } else {
                upstreamLen - stripLen
            }
        }

        override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
            if (readLength == 0) return 0

            // 优先吐 buffered bytes（非 PNG 透传 / PNG 探测失败回退 / PNG 成功同步字节）
            if (consumed < consumedBuf.size()) {
                val toCopy = minOf(consumedBuf.size() - consumed, readLength)
                val bytes = consumedBuf.toByteArray()
                System.arraycopy(bytes, consumed, buffer, offset, toCopy)
                consumed += toCopy
                return toCopy
            }

            // Buffer 已吐完：委派上游
            return upstream.read(buffer, offset, readLength)
        }

        override fun getUri(): android.net.Uri? = upstream.uri

        override fun close() {
            upstream.close()
        }

        private fun isPngSignature(bytes: ByteArray): Boolean =
            bytes[0] == 0x89.toByte() &&
                bytes[1] == 'P'.code.toByte() &&
                bytes[2] == 'N'.code.toByte() &&
                bytes[3] == 'G'.code.toByte() &&
                bytes[4] == '\r'.code.toByte() &&
                bytes[5] == '\n'.code.toByte() &&
                bytes[6] == 0x1A.toByte() &&
                bytes[7] == '\n'.code.toByte()

        /**
         * 从上游 DataSource 逐 chunk 解析，找 IEND chunk
         *
         * 每个 PNG chunk：长度[4](big-endian) + 类型[4] + 数据[length] + CRC[4]
         * 容纳字节数 = length + 12
         *
         * 试探期所有消费字节写入 consumedBuf，确保试探失败时 read() 可吐回
         *
         * @return IEND chunk 后偏移（含 IEND 自身大小），-1 表示异常
         */
        private fun tryWalkingSource(upstream: DataSource): Int {
            var totalSkip = 0
            for (guard in 0 until 64) {
                val lenBuf = ByteArray(4)
                val rLen = try {
                    upstream.read(lenBuf, 0, 4)
                } catch (_: IOException) {
                    return -1
                }
                if (rLen < 4) return -1
                consumedBuf.write(lenBuf, 0, rLen)
                val chunkLen = intOfBE(lenBuf)
                // 防御性围栏：每个 chunk 声明若 > 4MB 或 negative = 非真实 PNG，直接退出
                if (chunkLen < 0 || chunkLen > MAX_CHUNK_DATA) return -1

                val typeBuf = ByteArray(4)
                val rType = try {
                    upstream.read(typeBuf, 0, 4)
                } catch (_: IOException) {
                    return -1
                }
                if (rType < 4) return -1
                consumedBuf.write(typeBuf, 0, rType)

                val typeStr = String(typeBuf, Charsets.US_ASCII)

                val skipBuf = ByteArray(chunkLen + 4)
                try {
                    var skipRead = 0
                    while (skipRead < skipBuf.size) {
                        val n = upstream.read(skipBuf, skipRead, skipBuf.size - skipRead)
                        if (n < 0) return -1
                        skipRead += n
                    }
                } catch (_: IOException) {
                    return -1
                }
                consumedBuf.write(skipBuf, 0, skipBuf.size)
                totalSkip += 4 + 4 + chunkLen + 4

                if (typeStr == "IEND") {
                    return totalSkip
                }
            }
            return -1
        }

        private fun intOfBE(bytes: ByteArray): Int {
            var result = 0
            for (b in bytes) {
                result = (result shl 8) or (b.toInt() and 0xFF)
            }
            return result
        }

        companion object {
            private const val MAX_PNG_HEADER_LEN = 4096L
            private const val TS_SYNC_BYTE = 0x47.toByte()
            private const val MAX_CHUNK_DATA = 4 * 1024 * 1024
        }
    }
}
