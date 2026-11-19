package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index

/**
 * 短剧浏览行为记录。
 *
 * 每行是一条行为事件：曝光、开始播放、播放进度、完播、快速划走。
 * 主键包含 timestamp，因此同一系列同一行为可多次记录，用于计算曝光衰减与偏好。
 * 记录只存本地，随「清除浏览记录」一并清理。
 */
@Entity(
    tableName = "shortDramaInteractions",
    primaryKeys = ["seriesId", "action", "timestamp"],
    indices = [Index("timestamp"), Index("sourceId"), Index("seriesId")],
)
data class ShortDramaInteraction(
    var seriesId: String = "",
    var sourceId: String = "",
    var episodeId: String = "",
    /** exposure | start | progress | complete | skip */
    var action: String = "",
    /** 逗号分隔的标签串 */
    var tags: String = "",
    var position: Long = 0L,
    var duration: Long = 0L,
    var timestamp: Long = System.currentTimeMillis(),
)

object ShortDramaAction {
    const val EXPOSURE = "exposure"
    const val START = "start"
    const val PROGRESS = "progress"
    const val COMPLETE = "complete"
    const val SKIP = "skip"
}