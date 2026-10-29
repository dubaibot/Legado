package io.legado.app.base.adapter

import androidx.core.os.bundleOf

/**
 * 管理页多选样板收敛(R1d):全选/反选/取值集合的逐字节相同实现从 6 个 adapter 收进此接口默认方法。
 *
 * 由 [RecyclerAdapter] 子类实现。选中态存 [selectedKeys](调用方声明为成员,生命周期随 adapter),
 * [keyOf] 把元素映射到选中 key。绝大多数页面 key 即元素本身,直接实现 [SimpleSelectableAdapter];
 * 以 id 作 key 的页面(如 AutoTask)实现本接口并指定 `KEY=String` + 覆写 [keyOf]。
 *
 * 泛型分 ITEM/KEY 两参而非单参 `Any`:拖拽多选的 [io.legado.app.ui.widget.recycler.DragSelectTouchHelper]
 * .AdvanceCallback<KEY> 的 currentSelectedId() 需返回强类型的 [selectedKeys] 本体(快照原选中集),
 * `Any` 集合无法满足其 `MutableSet<KEY>` 契约。
 *
 * 依赖 RecyclerAdapter 的 getItems()/getItemCount()/notifyItemRangeChanged,故只能由其子类实现。
 */
interface SelectableAdapter<ITEM, KEY> {

    /** 选中 key 集合,调用方声明为 adapter 成员(`override val selectedKeys = linkedSetOf<X>()`) */
    val selectedKeys: MutableSet<KEY>

    /** 元素→选中 key 的映射 */
    fun keyOf(item: ITEM): KEY

    /** 选中集合变化后的回调,通常 `callBack.upCountView()` */
    fun onSelectionChanged()

    // 下列三个由 RecyclerAdapter / RecyclerView.Adapter 提供,接口在默认方法里调用
    fun getItems(): List<ITEM>
    fun getItemCount(): Int
    fun notifyItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?)

    /** 当前选中项(按 items 现序过滤,与列表顺序一致) */
    val selection: List<ITEM>
        get() = getItems().filter { selectedKeys.contains(keyOf(it)) }

    fun isSelected(item: ITEM): Boolean = selectedKeys.contains(keyOf(item))

    fun setSelected(item: ITEM, selected: Boolean) {
        if (selected) selectedKeys.add(keyOf(item)) else selectedKeys.remove(keyOf(item))
    }

    fun selectAll() {
        getItems().forEach { selectedKeys.add(keyOf(it)) }
        notifyItemRangeChanged(0, getItemCount(), bundleOf(Pair("selected", null)))
        onSelectionChanged()
    }

    fun revertSelection() {
        getItems().forEach {
            val key = keyOf(it)
            if (selectedKeys.contains(key)) selectedKeys.remove(key) else selectedKeys.add(key)
        }
        notifyItemRangeChanged(0, getItemCount(), bundleOf(Pair("selected", null)))
        onSelectionChanged()
    }
}

/** key 即元素本身的常见情形(依赖元素 equals/hashCode),省去 [keyOf] 覆写 */
interface SimpleSelectableAdapter<ITEM> : SelectableAdapter<ITEM, ITEM> {
    override fun keyOf(item: ITEM): ITEM = item
}
