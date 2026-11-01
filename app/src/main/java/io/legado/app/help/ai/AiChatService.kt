package io.legado.app.help.ai

import io.legado.app.ui.main.ai.AiChatMessage
import org.json.JSONObject

/**
 * 「文章总结」场景模型配置（供名场面描述等 Sigma 侧 AI 功能复用）。
 *
 * providerId 对应 ng AI 引擎的供应商 id；modelId 为该供应商下的模型 id。
 */
data class AiModelConfig(
    val providerId: String = "",
    val modelId: String = "",
    val temperature: Float? = null,
    val maxTokens: Int? = null
)

/**
 * ng AI 引擎（help/ai/AiManager）到 Sigma 既有调用面 `AiChatService.chatStream` 的桥接。
 *
 * ng 引擎为一次性文本生成（无工具调用循环、无逐 token 流式）：
 *  · [useAllTools] / [includeStructuredBlocks] 仅保持调用面兼容，当前实现不启用工具链；
 *  · [onPartial] 在完整结果返回时回调一次；
 *  · [onStatus] 通过 stage 字段回报 start / done / fail。
 *
 * 失败口径：供应商/模型缺失、生成异常均返回 null，由调用方降级处理。
 */
object AiChatService {

    private const val STATUS_STAGE_START = "start"
    private const val STATUS_STAGE_DONE = "done"
    private const val STATUS_STAGE_FAIL = "fail"

    suspend fun chatStream(
        messages: List<AiChatMessage>,
        onPartial: (String) -> Unit = {},
        onStatus: (JSONObject) -> Unit = {},
        includeStructuredBlocks: Boolean = true,
        useAllTools: Boolean = true,
        modelConfigOverride: AiModelConfig? = null
    ): String? {
        val config = modelConfigOverride ?: run {
            val providers = AiProviderStore.providers()
            if (providers.isEmpty()) return null
            val activeId = runCatching { AiProviderStore.activeProviderId() }.getOrNull()
            val provider = providers.firstOrNull { it.id == activeId && it.enabled }
                ?: providers.firstOrNull { it.enabled }
                ?: return null
            AiModelConfig(providerId = provider.id, modelId = provider.model)
        }
        val provider = AiProviderStore.provider(config.providerId)?.takeIf { it.enabled }
            ?: return null
        if (provider.model.isBlank() && config.modelId.isBlank()) return null
        onStatus(JSONObject().put("stage", STATUS_STAGE_START))
        val result = runCatching {
            val aiMessages = messages.map {
                AiMessage(role = AiMessage.Role.valueOf(it.role.name), content = it.content)
            }
            AiManager.generateText(
                messages = aiMessages,
                params = AiTextParams(temperature = config.temperature, maxTokens = config.maxTokens),
                providerId = config.providerId,
                modelId = config.modelId
            )
        }.onFailure {
            onStatus(JSONObject().put("stage", STATUS_STAGE_FAIL).put("message", it.message.orEmpty()))
        }.getOrNull() ?: return null
        onPartial(result.content)
        onStatus(JSONObject().put("stage", STATUS_STAGE_DONE).put("model", result.model.orEmpty()))
        return result.content
    }
}
