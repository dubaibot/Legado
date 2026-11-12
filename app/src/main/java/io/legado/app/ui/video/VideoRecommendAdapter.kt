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
 * 推荐 Tab 网格适配器：展示同源推荐影视卡片。
 */
class VideoRecommendAdapter(
    private var items: List<VideoItem>,
    private val onClick: (VideoItem) -> Unit,
) : RecyclerView.Adapter<VideoRecommendAdapter.RecommendViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecommendViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video_recommend, parent, false)
        return RecommendViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecommendViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    fun updateData(newItems: List<VideoItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    inner class RecommendViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivCover: CoverImageView = itemView.findViewById(R.id.iv_cover)
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_title)
        private val tvRemarks: TextView = itemView.findViewById(R.id.tv_remarks)

        fun bind(item: VideoItem) {
            tvTitle.text = item.title
            tvRemarks.text = item.remarks
            if (item.pic.isNotBlank()) {
                ivCover.load(item.pic, item.title)
            }
            itemView.setOnClickListener { onClick(item) }
        }
    }
}
