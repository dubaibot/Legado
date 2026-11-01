package io.legado.app.ui.config.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.help.ai.AiConfig
import io.legado.app.help.ai.AiManager
import io.legado.app.ui.main.ai.AiModelConfig
import io.legado.app.help.ai.AiProviderSetting
import io.legado.app.help.ai.AiProviderStore
import io.legado.app.help.ai.AiProviderType
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * AI 服务商管理页（D15/5.2.1，参照 ng ui/config AI 配置重写为 Sigma Compose 页面）。
 *
 * 第一版能力：
 * - 服务商列表（新增 / 编辑 / 删除 / 启用 / 设为当前）
 * - 基础表单：名称、类型、Base URL、API Key、默认模型、超时
 * - 拉取模型列表（AiManager.fetchAndSaveModels）、连接测试（AiManager.testConnection）
 * - 场景模型选择：AI 助理（聊天）、正文净化、文章总结
 * 技能、MCP、分镜模型后置。
 */
class AiProviderManageActivity : BaseComposeActivity() {

    private val providersState = mutableStateOf<List<AiProviderSetting>>(emptyList())
    private val activeIdState = mutableStateOf("")
    private val assistantSelectionState = mutableStateOf("" to "")
    private val purifySelectionState = mutableStateOf("" to "")
    private val summarySelectionState = mutableStateOf<AiModelConfig?>(null)
    private val busyState = mutableStateOf<String?>(null)

    private val editingProviderState = mutableStateOf<AiProviderSetting?>(null)
    private val showEditDialogState = mutableStateOf(false)
    private val modelPickerState = mutableStateOf<ModelPickerTarget?>(null)

    private var loadJob: Job? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    @Composable
    override fun ComposeContent() {
        Scaffold(
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = {
                        editingProviderState.value = null
                        showEditDialogState.value = true
                    },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(getString(R.string.ai_provider_add)) }
                )
            }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                ProviderListContent()
                if (showEditDialogState.value) {
                    EditProviderDialog()
                }
                if (modelPickerState.value != null) {
                    ModelPickerDialog()
                }
            }
        }
    }

    private fun reload() {
        if (loadJob?.isActive == true) return
        loadJob = lifecycleScope.launch(Dispatchers.IO) {
            val providers = AiProviderStore.providers()
            val activeId = runCatching { AiProviderStore.activeProviderId() }.getOrDefault("")
            withContext(Dispatchers.Main) {
                providersState.value = providers
                activeIdState.value = activeId
                assistantSelectionState.value = AiConfig.assistantProviderId to AiConfig.assistantModelId
                purifySelectionState.value = AiConfig.purifyProviderId to AiConfig.purifyModelId
                summarySelectionState.value = AppConfig.aiSummaryModelConfig
            }
        }
    }

    private fun onProviderSaved(provider: AiProviderSetting) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { AiProviderStore.saveProvider(provider) }
                .onFailure { withContext(Dispatchers.Main) { toastOnUi(it.message ?: "ERROR") } }
            reload()
        }
    }

    private fun onProviderDeleted(provider: AiProviderSetting) {
        alert(
            title = getString(R.string.ai_provider_delete),
            message = getString(R.string.ai_provider_delete_confirm, provider.name)
        ) {
            positiveButton(R.string.ok) { _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching {
                        val rest = AiProviderStore.providers().filter { it.id != provider.id }
                        AiProviderStore.saveProviders(rest)
                    }.onFailure {
                        withContext(Dispatchers.Main) { toastOnUi(it.message ?: "ERROR") }
                    }
                    reload()
                }
            }
            cancelButton()
        }
    }

    private fun onSetActive(provider: AiProviderSetting) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { AiProviderStore.setActiveProvider(provider.id) }
                .onFailure { withContext(Dispatchers.Main) { toastOnUi(it.message ?: "ERROR") } }
            reload()
        }
    }

    private fun onFetchModels(provider: AiProviderSetting) {
        if (busyState.value != null) return
        busyState.value = getString(R.string.ai_provider_fetching_models)
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { AiManager.fetchAndSaveModels(provider.id) }
            withContext(Dispatchers.Main) {
                busyState.value = null
                result.onSuccess { models ->
                    if (models.isEmpty()) {
                        toastOnUi(R.string.ai_provider_models_empty)
                    } else {
                        toastOnUi(getString(R.string.ai_provider_models_fetched, models.size))
                    }
                }.onFailure { toastOnUi(it.message ?: "ERROR") }
                reload()
            }
        }
    }

    private fun onTestConnection(provider: AiProviderSetting) {
        if (busyState.value != null) return
        busyState.value = getString(R.string.ai_provider_testing)
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { AiManager.testConnection(provider.id) }
            withContext(Dispatchers.Main) {
                busyState.value = null
                result.onSuccess {
                    toastOnUi(getString(R.string.ai_provider_test_ok, it.model ?: provider.model))
                }.onFailure { toastOnUi(it.message ?: "ERROR") }
            }
        }
    }

    private fun saveSelection(target: ModelPickerTarget, providerId: String, modelId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                when (target) {
                    ModelPickerTarget.ASSISTANT -> AiConfig.saveAssistantModel(providerId, modelId)
                    ModelPickerTarget.PURIFY -> AiConfig.savePurifyModel(providerId, modelId)
                    ModelPickerTarget.SUMMARY -> {
                        AppConfig.aiSummaryModelConfig =
                            AiModelConfig(providerId = providerId, modelId = modelId)
                    }
                }
            }.onFailure { withContext(Dispatchers.Main) { toastOnUi(it.message ?: "ERROR") } }
            reload()
        }
    }

    @Composable
    private fun ProviderListContent() {
        Column(Modifier.fillMaxSize()) {
            Text(
                text = getString(R.string.ai_provider_manage),
                fontSize = 20.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    ScenarioModelCard(
                        title = getString(R.string.ai_model_assistant),
                        current = selectionLabel(assistantSelectionState.value),
                        onClick = { modelPickerState.value = ModelPickerTarget.ASSISTANT }
                    )
                }
                item {
                    ScenarioModelCard(
                        title = getString(R.string.ai_model_purify),
                        current = selectionLabel(purifySelectionState.value),
                        onClick = { modelPickerState.value = ModelPickerTarget.PURIFY }
                    )
                }
                item {
                    ScenarioModelCard(
                        title = getString(R.string.ai_model_summary),
                        current = summarySelectionState.value?.let {
                            providerName(it.providerId) + " / " + it.modelId
                        } ?: getString(R.string.ai_model_unset),
                        onClick = { modelPickerState.value = ModelPickerTarget.SUMMARY }
                    )
                }
                item {
                    Text(
                        text = getString(R.string.ai_provider_section),
                        fontSize = 16.sp,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
                }
                items(providersState.value, key = { it.id }) { provider ->
                    ProviderCard(
                        provider = provider,
                        isActive = provider.id == activeIdState.value,
                        busy = busyState.value,
                        onToggleEnabled = { onProviderSaved(provider.copy(enabled = it)) },
                        onSetActive = { onSetActive(provider) },
                        onEdit = {
                            editingProviderState.value = provider
                            showEditDialogState.value = true
                        },
                        onFetchModels = { onFetchModels(provider) },
                        onTest = { onTestConnection(provider) },
                        onDelete = { onProviderDeleted(provider) }
                    )
                }
                item {
                    Spacer(Modifier.height(72.dp))
                }
            }
        }
    }

    @Composable
    private fun selectionLabel(selection: Pair<String, String>): String {
        return if (selection.first.isBlank() || selection.second.isBlank()) {
            getString(R.string.ai_model_unset)
        } else {
            providerName(selection.first) + " / " + selection.second
        }
    }

    private fun providerName(providerId: String): String {
        return providersState.value.firstOrNull { it.id == providerId }?.name ?: providerId
    }

    @Composable
    private fun ScenarioModelCard(title: String, current: String, onClick: () -> Unit) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(text = title, fontSize = 15.sp)
                Text(
                    text = current,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    @Composable
    private fun ProviderCard(
        provider: AiProviderSetting,
        isActive: Boolean,
        busy: String?,
        onToggleEnabled: (Boolean) -> Unit,
        onSetActive: () -> Unit,
        onEdit: () -> Unit,
        onFetchModels: () -> Unit,
        onTest: () -> Unit,
        onDelete: () -> Unit
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = provider.name,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (isActive) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = getString(R.string.ai_provider_active),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Switch(checked = provider.enabled, onCheckedChange = onToggleEnabled)
                }
                Text(
                    text = provider.type.displayName + " · " + provider.baseUrl,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = getString(R.string.ai_provider_model_label) + ": " +
                        provider.model.ifBlank { getString(R.string.ai_provider_model_unset) },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (busy != null) {
                    Text(
                        text = busy,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row {
                    TextButton(onClick = onEdit) { Text(getString(R.string.edit)) }
                    TextButton(onClick = onFetchModels) {
                        Text(getString(R.string.ai_provider_fetch_models))
                    }
                    TextButton(onClick = onTest) { Text(getString(R.string.ai_provider_test)) }
                    TextButton(onClick = onSetActive) {
                        Text(getString(R.string.ai_provider_set_active))
                    }
                    if (!provider.builtIn) {
                        IconButton(onClick = onDelete) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = getString(R.string.delete),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun EditProviderDialog() {
        val original = editingProviderState.value
        val nameState = remember { mutableStateOf(original?.name.orEmpty()) }
        val typeState = remember { mutableStateOf(original?.type ?: AiProviderType.OPENAI) }
        val baseUrlState = remember { mutableStateOf(original?.baseUrl.orEmpty()) }
        val apiKeyState = remember { mutableStateOf(original?.apiKey.orEmpty()) }
        val modelState = remember { mutableStateOf(original?.model.orEmpty()) }
        val timeoutState = remember {
            mutableStateOf((original?.timeoutSeconds ?: 180).toString())
        }
        AlertDialog(
            onDismissRequest = { showEditDialogState.value = false },
            title = {
                Text(
                    if (original == null) {
                        getString(R.string.ai_provider_add)
                    } else {
                        getString(R.string.ai_provider_edit)
                    }
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = nameState.value,
                        onValueChange = { nameState.value = it },
                        label = { Text(getString(R.string.ai_provider_name)) },
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AiProviderType.entries.forEach { type ->
                            TextButton(
                                onClick = { typeState.value = type },
                                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                    contentColor = if (typeState.value == type) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            ) { Text(type.displayName) }
                        }
                    }
                    OutlinedTextField(
                        value = baseUrlState.value,
                        onValueChange = { baseUrlState.value = it },
                        label = { Text(getString(R.string.ai_provider_base_url)) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = apiKeyState.value,
                        onValueChange = { apiKeyState.value = it },
                        label = { Text(getString(R.string.ai_provider_api_key)) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = modelState.value,
                        onValueChange = { modelState.value = it },
                        label = { Text(getString(R.string.ai_provider_model_label)) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = timeoutState.value,
                        onValueChange = { timeoutState.value = it },
                        label = { Text(getString(R.string.ai_provider_timeout)) },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val baseUrl = baseUrlState.value.trim()
                        if (nameState.value.isBlank() || baseUrl.isBlank()) {
                            toastOnUi(R.string.ai_provider_form_incomplete)
                            return@TextButton
                        }
                        val timeout = timeoutState.value.toIntOrNull()?.coerceIn(10, 600) ?: 180
                        val saved = (original ?: AiProviderSetting(
                            id = UUID.randomUUID().toString(),
                            type = AiProviderType.OPENAI,
                            builtIn = false,
                            name = "",
                            baseUrl = ""
                        )).copy(
                            name = nameState.value.trim(),
                            type = typeState.value,
                            baseUrl = baseUrl,
                            apiKey = apiKeyState.value.trim(),
                            model = modelState.value.trim(),
                            timeoutSeconds = timeout
                        )
                        showEditDialogState.value = false
                        onProviderSaved(saved)
                    }
                ) { Text(getString(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialogState.value = false }) {
                    Text(getString(R.string.cancel))
                }
            }
        )
    }

    @Composable
    private fun ModelPickerDialog() {
        val target = modelPickerState.value ?: return
        val providers = providersState.value
        val initialProviderId = when (target) {
            ModelPickerTarget.ASSISTANT -> assistantSelectionState.value.first
            ModelPickerTarget.PURIFY -> purifySelectionState.value.first
            ModelPickerTarget.SUMMARY -> summarySelectionState.value?.providerId.orEmpty()
        }
        val selectedProviderState = remember(target) {
            mutableStateOf(initialProviderId)
        }
        val manualModelState = remember(target) { mutableStateOf("") }
        val provider = providers.firstOrNull { it.id == selectedProviderState.value }
            ?: providers.firstOrNull()
        AlertDialog(
            onDismissRequest = { modelPickerState.value = null },
            title = { Text(modelPickerTitle(target)) },
            text = {
                if (providers.isEmpty()) {
                    Text(getString(R.string.ai_provider_empty_hint))
                } else {
                    Column {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(340.dp)
                        ) {
                            items(providers, key = { it.id }) { item ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedProviderState.value = item.id }
                                ) {
                                    RadioButton(
                                        selected = item.id == provider?.id,
                                        onClick = { selectedProviderState.value = item.id }
                                    )
                                    Text(item.name)
                                }
                                if (item.id == provider?.id) {
                                    val models = item.models
                                    models.forEach { model ->
                                        Text(
                                            text = model.name,
                                            fontSize = 13.sp,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    modelPickerState.value = null
                                                    saveSelection(target, item.id, model.id)
                                                }
                                                .padding(start = 32.dp, top = 6.dp, bottom = 6.dp)
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(
                                            value = manualModelState.value,
                                            onValueChange = { manualModelState.value = it },
                                            label = {
                                                Text(getString(R.string.ai_provider_model_manual))
                                            },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            onClick = {
                                                val id = manualModelState.value.trim()
                                                if (id.isNotBlank()) {
                                                    modelPickerState.value = null
                                                    saveSelection(target, item.id, id)
                                                }
                                            }
                                        ) { Text(getString(R.string.action_save)) }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { modelPickerState.value = null }) {
                    Text(getString(R.string.cancel))
                }
            }
        )
    }

    @Composable
    private fun modelPickerTitle(target: ModelPickerTarget): String {
        return when (target) {
            ModelPickerTarget.ASSISTANT -> getString(R.string.ai_model_assistant)
            ModelPickerTarget.PURIFY -> getString(R.string.ai_model_purify)
            ModelPickerTarget.SUMMARY -> getString(R.string.ai_model_summary)
        }
    }

    private enum class ModelPickerTarget { ASSISTANT, PURIFY, SUMMARY }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, AiProviderManageActivity::class.java))
        }
    }
}
