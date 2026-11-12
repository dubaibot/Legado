package io.legado.app.ui.video

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.data.video.VideoItem
import io.legado.app.ui.widget.image.CoverImageView

/**
 * 换源 Tab 列表适配器：展示其他源搜索该片名的结果。
 */
class VideoSourceSwitchAdapter(
    private var items: List<VideoItem>,
    private var sourceNames: List<String>,
    private val onClick: (VideoItem, Int) -> Unit,
) : RecyclerView.Adapter<VideoSourceSwitchAdapter.SourceSwitchViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SourceSwitchViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video_source_switch, parent, false)
        return SourceSwitchViewHolder(view)
    }

    override fun onBindViewHolder(holder: SourceSwitchViewHolder, position: Int) {
        holder.bind(items[position], sourceNames.getOrNull(position) ?: "")
    }

    override fun getItemCount(): Int = items.size

    fun updateData(newItems: List<VideoItem>, newSourceNames: List<String>) {
        items = newItems
        sourceNames = newSourceNames
        notifyDataSetChanged()
    }

    inner class SourceSwitchViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivCover: CoverImageView = itemView.findViewById(R.id.iv_cover)
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_title)
        private val tvRemarks: TextView = itemView.findViewById(R.id.tv_remarks)
        private val tvSource: TextView = itemView.findViewById(R.id.tv_source)

        fun bind(item: VideoItem, sourceName: String) {
            tvTitle.text = item.title
            tvRemarks.text = item.remarks
            tvSource.text = sourceName
            if (item.pic.isNotBlank()) {
                ivCover.load(item.pic, item.title)
            }
            itemView.setOnClickListener { onClick(item, bindingAdapterPosition) }
        }
    }
}
