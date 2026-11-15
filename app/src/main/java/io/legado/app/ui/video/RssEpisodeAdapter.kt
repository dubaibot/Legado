package io.legado.app.ui.video

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.data.entities.RssEpisode
import io.legado.app.lib.theme.ThemeStore.Companion.accentColor

/**
 * R1 多集选择播放：订阅源集数列表适配器
 *
 * 结构与 ChapterAdapter 对称，但数据类型为 RssEpisode，点击回调用 rssEpisodeIndex。
 * 传统模式集数列表使用 item_video_episode（chips 样式，选中实心主题色高亮）；
 * 沉浸式模式使用 item_video_chapter（CardView 小格子）。
 */
class RssEpisodeAdapter(
    private var episodes: List<RssEpisode>,
    private var selectedPosition: Int = -1,
    private val verticalLayout: Boolean = false,
    private val onEpisodeClick: (RssEpisode, Int) -> Unit,
) : RecyclerView.Adapter<RssEpisodeAdapter.EpisodeViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
        // 传统模式集数列表使用 chips 样式，沉浸式模式使用 CardView 小格子
        val res = if (verticalLayout) {
            R.layout.item_video_episode
        } else {
            R.layout.item_video_chapter
        }
        val view = LayoutInflater.from(parent.context)
            .inflate(res, parent, false)
        if ((parent as? RecyclerView)?.layoutManager is androidx.recyclerview.widget.GridLayoutManager) {
            view.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        }
        return EpisodeViewHolder(view)
    }

    override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
        if (position >= 0 && position < episodes.size) {
            holder.bind(episodes[position], position == selectedPosition)
        }
    }

    override fun getItemCount(): Int = episodes.size

    /**
     * F183（2026-09-21）：已看集的原始 URL 集合（与 `RssEpisode.url` 比对）。
     * 默认空集 ⇒ **既有调用点零改动、不标已看**；赋值即整表重绑（集数列表规模小，无需局部刷新）。
     */
    @SuppressLint("NotifyDataSetChanged")
    var watchedUrls: Set<String> = emptySet()
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    fun updateSelectedPosition(newPosition: Int) {
        if (newPosition < 0 || newPosition >= episodes.size) {
            return
        }
        val oldPosition = selectedPosition
        selectedPosition = newPosition
        if (oldPosition >= 0 && oldPosition < episodes.size) {
            notifyItemChanged(oldPosition)
        }
        notifyItemChanged(newPosition)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateData(newEpisodes: List<RssEpisode>?) {
        this.episodes = newEpisodes ?: return
        notifyDataSetChanged()
    }

    inner class EpisodeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvChapterName: TextView = itemView.findViewById(R.id.tvChapterName)

        fun bind(episode: RssEpisode, isSelected: Boolean) {
            // F183：三态——当前（accent）/ 已看（次级观感 + ✓ 后缀）/ 未看（默认）
            val watched = !isSelected && watchedUrls.contains(episode.url)
            tvChapterName.text = if (watched) {
                episode.title + itemView.context.getString(R.string.video_episode_watched_mark)
            } else {
                episode.title
            }
            if (verticalLayout) {
                // chips 样式：由 bg_video_chip 状态列表 drawable 自动处理选中高亮
                itemView.isSelected = isSelected
                tvChapterName.alpha = if (watched) 0.6f else 1f
            } else {
                // 沉浸式 CardView 样式：编程式设置选中高亮
                if (isSelected) {
                    tvChapterName.alpha = 1f
                    tvChapterName.setTextColor(accentColor)
                    val accent = accentColor
                    tvChapterName.background = GradientDrawable().apply {
                        cornerRadius = 12f * itemView.resources.displayMetrics.density
                        setColor(Color.argb(0x33, Color.red(accent), Color.green(accent), Color.blue(accent)))
                    }
                } else {
                    tvChapterName.setTextColor(Color.WHITE)
                    tvChapterName.setBackgroundResource(R.drawable.bg_overlay_button)
                    tvChapterName.alpha = if (watched) 0.6f else 1f
                }
            }
            itemView.setOnClickListener {
                val previousPosition = selectedPosition
                selectedPosition = bindingAdapterPosition
                if (previousPosition >= 0) {
                    notifyItemChanged(previousPosition)
                }
                if (selectedPosition >= 0) {
                    notifyItemChanged(selectedPosition)
                }
                onEpisodeClick(episode, selectedPosition)
            }
        }
    }
}
