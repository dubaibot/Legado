package io.legado.app.utils

internal fun <T, K> mergeFilteredOrder(
    allItems: List<T>,
    orderedItems: List<T>,
    keyOf: (T) -> K
): List<T> {
    val currentItemsByKey = allItems.associateBy(keyOf)
    val ordered = orderedItems
        .mapNotNull { currentItemsByKey[keyOf(it)] }
        .distinctBy(keyOf)
    val orderedKeys = ordered.mapTo(hashSetOf(), keyOf)
    val orderedIterator = ordered.iterator()
    return allItems.map { item ->
        if (keyOf(item) in orderedKeys && orderedIterator.hasNext()) {
            orderedIterator.next()
        } else {
            item
        }
    }
}
