package com.lanyue.reader.danmaku

import com.lanyue.reader.data.db.entity.DanmakuSourceEntity

/**
 * 4.10.1 弹幕源启用顺序管理。
 *
 * 引擎按 `sortOrder` 遍历源，但此前没有任何地方负责**维护**这个顺序：
 * DAO 只有 `ORDER BY sortOrder ASC` 的读，没有写入排序号的入口。
 * 直接后果有两个：
 * - 新建源的 sortOrder 都由调用方随手给 0，多个源并列时 SQLite 的返回顺序
 *   是未定义的，表现是「匹配源每次播放都不一样」，用户明明只想要 A 源却偶尔用了 B；
 * - 删掉中间一条后序号出现空洞（0,1,3），再插入时无法判断该放哪。
 *
 * 这里把顺序维护做成纯函数：输入现有列表，输出新列表，不碰数据库。
 * 便于单测，也避免 UI 层各自写一套重排逻辑。
 */
object DanmakuSourceRegistry {

    /** 启用源数上限。超过这个数匹配链路会变慢，且用户也管不过来。 */
    const val MAX_SOURCES = 20

    sealed class ReorderResult {
        data class Ok(val ordered: List<DanmakuSourceEntity>) : ReorderResult()
        data class Rejected(val reason: String) : ReorderResult()
    }

    /**
     * 规范化顺序：按现有 sortOrder 排序（并列时用 id 兜底保证稳定），
     * 再重编号为 0..n-1 的连续整数。
     *
     * 并列时不能依赖输入顺序：DAO 返回的并列顺序在不同 SQLite 版本/查询计划下
     * 可能变化。用 id 做次级键，至少保证同一份数据每次算出的顺序一致。
     */
    fun normalize(sources: List<DanmakuSourceEntity>): List<DanmakuSourceEntity> =
        sources
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
            .mapIndexed { index, s -> if (s.sortOrder == index) s else s.copy(sortOrder = index) }

    /** 取启用源，按顺序返回。引擎实际用的就是这份。 */
    fun enabledOrdered(sources: List<DanmakuSourceEntity>): List<DanmakuSourceEntity> =
        normalize(sources).filter { it.enabled }

    /** 新增一条：追加到末尾并重编号。超过上限则拒绝。 */
    fun add(
        existing: List<DanmakuSourceEntity>,
        newSource: DanmakuSourceEntity
    ): ReorderResult {
        val current = normalize(existing)
        if (current.any { it.id == newSource.id }) {
            return ReorderResult.Rejected("已存在同 id 的弹幕源：${newSource.id}")
        }
        if (current.size >= MAX_SOURCES) {
            return ReorderResult.Rejected("弹幕源数量已达上限 $MAX_SOURCES")
        }
        val appended = current + newSource.copy(sortOrder = current.size)
        return ReorderResult.Ok(normalize(appended))
    }

    /**
     * 删除一条并重编号。
     *
     * 允许删到零个：用户可能就想清空重配。但**不允许把最后一个启用源删掉后
     * 还假装有源可用**——那由 [setEnabled] 负责拦截，删除本身不需要额外条件。
     */
    fun remove(
        existing: List<DanmakuSourceEntity>,
        id: String
    ): ReorderResult {
        val current = normalize(existing)
        if (current.none { it.id == id }) {
            return ReorderResult.Rejected("找不到弹幕源：$id")
        }
        return ReorderResult.Ok(normalize(current.filterNot { it.id == id }))
    }

    /**
     * 启停一条。
     *
     * 关闭最后一个启用源时拒绝：全部关闭等价于「弹幕功能消失」，
     * 而用户按的只是某个源后面的开关，不期望整个弹幕区变空。
     * 想彻底停用应当走「弹幕总开关」，那是另一个配置项。
     */
    fun setEnabled(
        existing: List<DanmakuSourceEntity>,
        id: String,
        enabled: Boolean
    ): ReorderResult {
        val current = normalize(existing)
        val target = current.firstOrNull { it.id == id }
            ?: return ReorderResult.Rejected("找不到弹幕源：$id")
        if (!enabled && current.count { it.enabled } <= 1 && target.enabled) {
            return ReorderResult.Rejected("至少要保留一个启用中的弹幕源")
        }
        if (target.enabled == enabled) return ReorderResult.Ok(current)
        return ReorderResult.Ok(
            normalize(current.map { if (it.id == id) it.copy(enabled = enabled) else it })
        )
    }

    /**
     * 上移/下移。[delta] 为正表示向后（顺序变晚），负表示向前。
     * 越界时原样返回，不做环绕——环绕会让「已经在第一位」的源突然跑到末尾，
     * 用户连按两次上移会得到完全相反的结果。
     */
    fun move(
        existing: List<DanmakuSourceEntity>,
        id: String,
        delta: Int
    ): ReorderResult {
        val current = normalize(existing)
        val from = current.indexOfFirst { it.id == id }
        if (from < 0) return ReorderResult.Rejected("找不到弹幕源：$id")
        val to = (from + delta).coerceIn(0, current.lastIndex)
        if (to == from) return ReorderResult.Ok(current)
        val mutable = current.toMutableList()
        val item = mutable.removeAt(from)
        mutable.add(to, item)
        // 必须在这里直接重编号：move 只调整了列表位置，各元素仍带着旧的 sortOrder，
        // 若交给 normalize 处理，它会按旧序号把列表原样排回去，移动等于没发生。
        return ReorderResult.Ok(mutable.mapIndexed { index, s -> s.copy(sortOrder = index) })
    }
}
