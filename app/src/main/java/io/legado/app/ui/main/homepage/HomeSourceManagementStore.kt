package io.legado.app.ui.main.homepage

import android.content.Context
import io.legado.app.data.video.FongMiConfigParser
import io.legado.app.data.video.FongMiTxtParser
import io.legado.app.data.video.SourceType
import io.legado.app.data.video.VideoSourceRegistry
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx

/** Management metadata is separate from imported source files. */
object HomeSourceManagementStore {
    data class Source(
        val id: String,
        val name: String,
        val type: String,
        val parentId: String = "",
        val parentName: String = "",
        val enabled: Boolean = true,
        val siteKey: String = "",
    ) {
        val key: String get() = "$type:$id"
        val deletionKey: String get() = key
    }

    data class Groups(
        val names: List<String> = emptyList(),
        val assignments: Map<String, String> = emptyMap(),
    )

    private val preferences get() = appCtx.getSharedPreferences("homepage_source_management", Context.MODE_PRIVATE)

    fun sources(): List<Source> {
        val configs = HomeSourceStore.listVideoSources()
        val parents = configs.associate { it.id to it.name }
        val registered = VideoSourceRegistry.listSources(includeHidden = true)
        val represented = registered.map { it.parentId.ifEmpty { it.id } }.toSet()
        val visible = registered.filterNot(VideoSourceRegistry::isSourceHidden).map {
            Source(it.id, it.name, it.type.name, it.parentId, parents[it.parentId].orEmpty(),
                isEnabled("${it.type.name}:${it.id}"), it.siteKey)
        } + configs.filter { meta ->
            if (meta.id in represented) return@filter false
            val content = HomeSourceStore.readVideoSource(meta.id, meta.type).orEmpty()
            // 多站点配置始终按站点管理，全部站点隐藏后也不回退为父配置删除入口。
            !((meta.type == "json" && FongMiConfigParser.isFongMiConfig(content)) ||
                (meta.type == "txt" && FongMiTxtParser.isFongMiTxt(content)))
        }.map {
            Source(it.id, it.name, "CONFIG", enabled = isEnabled("CONFIG:${it.id}"))
        } + HomeSourceStore.listHtml().map {
            Source(it.id, it.name, "WEB", enabled = isEnabled("WEB:${it.id}"))
        }
        return visible.sortedBy { source ->
            val typeScope = if (source.type == SourceType.PY.name) "PY" else "TVBOX"
            val orderedKeys = JSONArray(preferences.getString("order:$typeScope", null) ?: "[]")
            (0 until orderedKeys.length()).indexOfFirst { orderedKeys.getString(it) == source.key }
                .takeIf { it >= 0 } ?: Int.MAX_VALUE
        }
    }

    fun isEnabled(key: String): Boolean = preferences.getBoolean("enabled:$key", true)

    @Synchronized
    fun setEnabled(key: String, enabled: Boolean) {
        check(preferences.edit().putBoolean("enabled:$key", enabled).commit()) { "启用状态保存失败，请重试" }
    }

    @Synchronized
    fun saveOrder(type: String, keys: List<String>) {
        val scope = if (type == SourceType.PY.name) "PY" else "TVBOX"
        check(preferences.edit().putString("order:$scope", JSONArray(keys).toString()).commit()) {
            "源顺序保存失败，请重试"
        }
    }

    /** type 为 PY 或 TVBOX；分组名称按类型隔离，源的分组映射仍共用稳定 key。 */
    fun groups(type: String? = null): Groups {
        val root = JSONObject(preferences.getString("groups", null) ?: "{}")
        val names = root.optJSONArray("names") ?: JSONArray()
        val assignments = root.optJSONObject("assignments") ?: JSONObject()
        val mapping = assignments.keys().asSequence().associateWith { assignments.getString(it) }
        val scopedNames = type?.let { preferences.getString("group_names:$it", null) }
        val visibleNames = when {
            type == null -> (0 until names.length()).map { names.getString(it) } +
                listOf("PY", "TVBOX").flatMap { scope ->
                    val scoped = JSONArray(preferences.getString("group_names:$scope", null) ?: "[]")
                    (0 until scoped.length()).map { scoped.getString(it) }
                }
            scopedNames != null -> JSONArray(scopedNames).let { scoped ->
                (0 until scoped.length()).map { scoped.getString(it) }
            }
            else -> mapping.filterKeys { (it.startsWith("PY:")) == (type == "PY") }.values.toList()
        }
        return Groups(
            (visibleNames + mapping.filterKeys {
                type == null || it.startsWith("PY:") == (type == "PY")
            }.values).distinct(), mapping,
        )
    }

    @Synchronized
    fun saveGroups(groups: Groups, type: String? = null) {
        val names = if (type == null) groups.names else {
            val oldNames = this.groups(type).names
            val otherNames = this.groups(if (type == "PY") "TVBOX" else "PY").names
            this.groups().names.filter { it !in oldNames || it in groups.names || it in otherNames }
        }
        val root = JSONObject().put("names", JSONArray(names))
            .put("assignments", JSONObject(groups.assignments))
        val editor = preferences.edit().putString("groups", root.toString())
        if (type != null) editor.putString("group_names:$type", JSONArray(groups.names).toString())
        check(editor.commit()) { "分组保存失败，请重试" }
    }

    fun affected(source: Source, sources: List<Source>): List<Source> =
        sources.filter { it.key == source.key }

    @Synchronized
    fun delete(source: Source, confirmed: List<Source> = listOf(source)) {
        val current = sources()
        val actual = affected(source, current)
        check(actual.map { it.key }.toSet() == confirmed.map { it.key }.toSet() && actual.size == 1) {
            "源列表已变化，请重新确认删除范围"
        }
        check(actual.single().parentId == source.parentId && actual.single().siteKey == source.siteKey) {
            "站点配置已变化，请刷新后重试"
        }
        val success = when {
            source.type == "WEB" -> HomeSourceStore.deleteHtml(source.id)
            source.type == "CONFIG" -> {
                // 避免过期 CONFIG 行在配置已展开后删除所有子站点。
                check(VideoSourceRegistry.listSources(includeHidden = true).none {
                    it.id == source.id || it.parentId == source.id
                }) { "配置已变化，请刷新后逐站点删除" }
                val meta = HomeSourceStore.listVideoSources().find { it.id == source.id }
                    ?: error("源配置已不存在")
                val content = HomeSourceStore.readVideoSource(meta.id, meta.type).orEmpty()
                check(!((meta.type == "json" && FongMiConfigParser.isFongMiConfig(content)) ||
                    (meta.type == "txt" && FongMiTxtParser.isFongMiTxt(content)))) {
                    "多站点配置请逐站点删除"
                }
                HomeSourceStore.deleteVideoSource(source.id)
            }
            else -> VideoSourceRegistry.deleteSource(VideoSourceRegistry.SourceInfo(
                source.id, source.name, SourceType.valueOf(source.type), source.parentId, source.siteKey,
            ))
        }
        check(success) { "删除失败，请重试" }
        check(sources().none { it.deletionKey == source.deletionKey }) { "源文件删除未完成，请检查存储权限后重试" }
        actual.forEach { VideoSourceRegistry.invalidateSource(it.id) }
        check(preferences.edit().remove("enabled:${source.key}").commit()) { "启用状态清理失败" }
        val groups = groups()
        saveGroups(groups.copy(assignments = groups.assignments - actual.map { it.key }.toSet()))
    }
}
