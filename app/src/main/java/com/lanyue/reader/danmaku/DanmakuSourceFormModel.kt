package com.lanyue.reader.danmaku

import com.lanyue.reader.data.db.entity.DanmakuSourceEntity

/**
 * 弹幕源配置表单模型。
 *
 * T32 验收要求：
 * - 新增源表单：只要求填 apiBase；
 * - 校验失败时**允许强制保存**并显示「未验证」；
 * - 显示「已验证 / 未验证」状态。
 *
 * 此前 [DanmakuSourceValidator] 做了地址规范化与探活校验，
 * [DanmakuSourceRegistry] 做了列表增删排序，但两者之间缺一层桥接：
 * 表单输入 → 校验 → 重复检测 → 实体创建 → 注册入库。
 * 这一层就是本类的职责。
 */
object DanmakuSourceFormModel {

    /**
     * 表单输入。只要求填 [apiBase]，[name] 为空时自动用主机名兜底。
     *
     * @param apiBase   用户输入的 API 地址（必填）
     * @param name      源名称（可选，空则用主机名）
     * @param enabled   是否启用
     * @param editingId 编辑模式下被编辑的源 ID；null 表示新建
     */
    data class Form(
        val apiBase: String = "",
        val name: String = "",
        val enabled: Boolean = true,
        val editingId: String? = null
    ) {
        /** 是否为编辑模式（改已有源）而非新建模式。 */
        val isEditing: Boolean get() = editingId != null

        /** 表单是否有实质内容（apiBase 非空）。 */
        val hasInput: Boolean get() = apiBase.isNotBlank()
    }

    /**
     * 表单提交结果。
     */
    sealed class SubmitResult {
        /** 新建成功（探活通过 → verified=true；探活失败但强制保存 → verified=false）。 */
        data class Created(
            val entity: DanmakuSourceEntity,
            val verified: Boolean,
            val warning: String?
        ) : SubmitResult()

        /** 更新成功（同上）。 */
        data class Updated(
            val entity: DanmakuSourceEntity,
            val verified: Boolean,
            val warning: String?
        ) : SubmitResult()

        /** 不可保存：缺必填或格式错误。 */
        data class Rejected(val reason: String) : SubmitResult()

        /** 地址与已有源重复（编辑自己除外）。 */
        data class Duplicate(val existingId: String, val existingName: String) : SubmitResult()
    }

    /**
     * 提交表单：校验 → 重复检测 → 生成实体。
     *
     * @param form      表单输入
     * @param existing  现有源列表（用于重复检测）
     * @param probe     探活函数（返回 null 表示通过，否则为失败原因）
     * @param forceSave 探活失败时是否强制保存为未验证（默认 true，符合 T32 验收要求）
     * @return 提交结果
     */
    fun submit(
        form: Form,
        existing: List<DanmakuSourceEntity>,
        probe: (String) -> String? = { null },
        forceSave: Boolean = true
    ): SubmitResult {
        // 1. 基础校验
        if (!form.hasInput) {
            return SubmitResult.Rejected("apiBase 不能为空")
        }

        // 2. 走 Validator 做地址规范化与探活
        val rawEntity = DanmakuSourceEntity(
            id = form.editingId ?: generateId(form.apiBase, existing),
            name = form.name,
            apiBase = form.apiBase,
            enabled = form.enabled,
            sortOrder = if (form.isEditing)
                existing.firstOrNull { it.id == form.editingId }?.sortOrder ?: 0
            else existing.size,
            verified = false
        )

        val verdict = DanmakuSourceValidator.validate(rawEntity, probe)

        // 3. 拒绝：格式问题，不可保存
        if (verdict is DanmakuSourceValidator.Verdict.Rejected) {
            return SubmitResult.Rejected(verdict.reason)
        }

        // 4. 取规范化后的实体
        val normalized: DanmakuSourceEntity = when (verdict) {
            is DanmakuSourceValidator.Verdict.Ok -> verdict.normalized.copy(verified = true)
            is DanmakuSourceValidator.Verdict.Unverified -> {
                if (!forceSave) {
                    return SubmitResult.Rejected("探活失败：${verdict.reason}（可勾选「强制保存」再试）")
                }
                verdict.normalized.copy(verified = false)
            }
            else -> return SubmitResult.Rejected("未知校验结果")
        }

        // 5. 重复检测：用规范化后的 apiBase 比对已有源
        val dup = existing.firstOrNull { existingSrc ->
            existingSrc.id != form.editingId &&  // 编辑自己不算重复
                normalizeForCompare(existingSrc.apiBase) == normalizeForCompare(normalized.apiBase)
        }
        if (dup != null) {
            return SubmitResult.Duplicate(dup.id, dup.name)
        }

        // 6. 返回结果
        val warning = when (verdict) {
            is DanmakuSourceValidator.Verdict.Unverified -> verdict.reason
            else -> null
        }

        return if (form.isEditing) {
            SubmitResult.Updated(normalized, normalized.verified, warning)
        } else {
            SubmitResult.Created(normalized, normalized.verified, warning)
        }
    }

    /**
     * 将提交结果转为 registry 可用的操作。
     *
     * - Created → registry.add(existing, entity)
     * - Updated → registry 中替换对应条目
     * - Rejected/Duplicate → 不操作
     */
    fun toRegistryOp(
        result: SubmitResult,
        existing: List<DanmakuSourceEntity>
    ): DanmakuSourceRegistry.ReorderResult {
        val entity = when (result) {
            is SubmitResult.Created -> result.entity
            is SubmitResult.Updated -> result.entity
            else -> return DanmakuSourceRegistry.ReorderResult.Rejected("不可保存")
        }

        return if (result is SubmitResult.Updated) {
            // 编辑模式：替换现有条目，不新增
            val updated = existing.map { if (it.id == entity.id) entity else it }
            DanmakuSourceRegistry.ReorderResult.Ok(DanmakuSourceRegistry.normalize(updated))
        } else {
            // 新建模式：add 到末尾
            DanmakuSourceRegistry.add(existing, entity)
        }
    }

    /**
     * 生成新源 ID。
     *
     * 用 apiBase 的主机名 + 现有列表大小做基础，避免纯自增 ID 在导入备份后撞号。
     * 如果生成的 ID 已存在，追加数字后缀直到不重复。
     */
    fun generateId(apiBase: String, existing: List<DanmakuSourceEntity>): String {
        val base = DanmakuSourceValidator.normalizeBase(apiBase)
        val host = if (base != null) {
            base.substringAfter("://").substringBefore('/').substringBefore(':')
        } else {
            "source"
        }
        val existingIds = existing.map { it.id }.toSet()
        var id = host
        var suffix = 1
        while (id in existingIds) {
            id = "$host-$suffix"
            suffix++
        }
        return id
    }

    /**
     * 归一化 apiBase 用于重复比较：小写 + 去尾部斜杠。
     * 不走 Validator 的完整规范化（那会拒绝无效输入），只做大小写归一。
     */
    private fun normalizeForCompare(apiBase: String): String {
        return apiBase.trim().trimEnd('/').lowercase()
    }

    /**
     * 表单校验状态（不含探活），供 UI 实时反馈。
     *
     * 返回三种状态：
     * - "ok"：地址格式有效
     * - "empty"：未输入
     * - "invalid"：格式无效
     */
    fun formValidationState(apiBase: String): String {
        if (apiBase.isBlank()) return "empty"
        return if (DanmakuSourceValidator.normalizeBase(apiBase) != null) "ok" else "invalid"
    }

    /**
     * 从现有源列表构造编辑表单。
     */
    fun editForm(entity: DanmakuSourceEntity): Form = Form(
        apiBase = entity.apiBase,
        name = entity.name,
        enabled = entity.enabled,
        editingId = entity.id
    )
}
