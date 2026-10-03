package io.legado.app.data.entities.rule

/**
 * 发现分类树节点,由用户从筛选页面板手动构建,最多三级
 * type 取 ExploreKind.Type.url / Type.select / Type.button
 */
data class ExploreCatNode(
    val name: String = "",
    val type: String = ExploreKind.Type.url,
    val kindTitle: String = "",
    val url: String? = null,
    val option: String? = null,
    val action: String? = null,
    val children: List<ExploreCatNode> = emptyList()
) {

    companion object {
        /** 分段标题节点(预填产物):无url,点击加载段首子级url */
        const val TYPE_HEADER = "header"

        /** 同名称不同引用显示"名称（序号）" */
        fun displayNames(nodes: List<ExploreCatNode>): List<String> {
            val groups = nodes.groupBy { it.name }
            return nodes.map { node ->
                val group = groups[node.name].orEmpty()
                if (group.size > 1) {
                    "${node.name}（${group.indexOf(node) + 1}）"
                } else {
                    node.name
                }
            }
        }
    }

    /** 引用同一性:显示名+类型+全部引用字段一致才视为同一节点(同名不同引用允许共存) */
    fun sameRefAs(other: ExploreCatNode): Boolean {
        return type == other.type
                && kindTitle == other.kindTitle
                && url == other.url
                && option == other.option
                && action == other.action
    }

    override fun equals(other: Any?): Boolean {
        return other is ExploreCatNode && sameRefAs(other) && name == other.name
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + kindTitle.hashCode()
        result = 31 * result + (url?.hashCode() ?: 0)
        result = 31 * result + (option?.hashCode() ?: 0)
        result = 31 * result + (action?.hashCode() ?: 0)
        return result
    }

}
