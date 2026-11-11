package com.lanyue.reader.core.shell

/**
 * 页面要展示的四种基本状态。
 *
 * T10 要求"状态容器（ViewModel + Repository）"。容器本身是 Android 类型，
 * 无法在 JVM 测试里直接跑；但**容器里真正容易出错的部分是状态归约**：
 * 什么时候该显示空态、错误可不可重试、重试期间旧数据要不要留着。
 * 这些与 Android 无关，抽成纯函数就能被单测覆盖。
 *
 * 之所以显式区分 [Empty] 与 [Content]，是因为把"空列表"当成 Content
 * 会让每个页面各自判断 `items.isEmpty()`，判断标准迟早不一致——
 * 常见结果是刷新出空数据时显示一片白，用户分不清"没有数据"和"还没加载"。
 */
sealed interface ScreenState<out T> {

    /** 首次加载中。 */
    data object Loading : ScreenState<Nothing>

    /** 加载成功，但结果为空。 */
    data object Empty : ScreenState<Nothing>

    /**
     * 加载失败。
     *
     * [retryable] 区分"网络抖动，重试有用"与"书源格式错误，重试无用"。
     * 不区分的话，界面只能一律给重试按钮，用户会在必然失败的操作上反复点击。
     */
    data class Error(val message: String, val retryable: Boolean = true) :
        ScreenState<Nothing>

    /** 有内容。 */
    data class Content<T>(val items: List<T>) : ScreenState<T>
}

object ScreenStateReducer {

    /** 从一个已成功取回的列表归约出状态：空列表是 Empty，不是 Content。 */
    fun <T> fromItems(items: List<T>): ScreenState<T> =
        if (items.isEmpty()) ScreenState.Empty else ScreenState.Content(items)

    /**
     * 重试时的状态。
     *
     * 保留已有内容（[previous] 是 Content 时原样返回）：把内容清空再转圈，
     * 会让下拉刷新看起来像整个页面被重置。首次加载（无内容）才回到 Loading。
     */
    fun <T> onRetry(previous: ScreenState<T>): ScreenState<T> =
        when (previous) {
            is ScreenState.Content -> previous
            else -> ScreenState.Loading
        }

    /**
     * 把异常翻译成错误态。
     *
     * 只有 [retryable] 为真的异常才给重试机会，其余（例如解析失败）
     * 直接告知不可重试，避免用户做无用功。
     */
    fun fromFailure(error: Throwable?, retryable: Boolean = true): ScreenState<Nothing> {
        val message = error?.message?.takeIf { it.isNotBlank() }
            ?: error?.javaClass?.simpleName
            ?: "未知错误"
        return ScreenState.Error(message, retryable)
    }

    /** 当前是否应当显示"加载中"的转圈。 */
    fun isLoading(state: ScreenState<*>): Boolean = state is ScreenState.Loading

    /** 当前是否应当显示空态文案。 */
    fun isEmpty(state: ScreenState<*>): Boolean = state is ScreenState.Empty
}
