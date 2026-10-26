package io.legado.app.help.readaloud.prebuild

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TTS 批量预合成队列单例（最小端口）
 *
 * 预合成 worker / 合成器依赖的 readaloud 子系统（SpeechRoute、ReadAloudDelegate、
 * TtsSynthesizer、TtsCacheKeys 等）未随本次端口引入，此处仅保留删书联动 / 缓存清理
 * 所需的取消接口，保证调用点可编译；待 readaloud 子系统整体端口后替换为完整实现。
 */
object TtsPrebuildManager {

    /** 预合成保留名单：已落盘预合成文件名集合 */
    val reservedKeys = ConcurrentHashMap<String, Long>()

    /** 进行中 / 排队任务的取消标志 */
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()

    /** 按书取消（缓存清理 / 删书联动）：置 cancelFlag，worker 感知后落终态 */
    fun cancelByBook(bookKey: String) {
        cancelFlags[bookKey]?.set(true)
    }

    /** 清空全部任务与保留名单（缓存管理页整目录清理联动） */
    fun cancelAllAndClearReserved() {
        cancelFlags.values.forEach { it.set(true) }
        reservedKeys.clear()
    }
}
