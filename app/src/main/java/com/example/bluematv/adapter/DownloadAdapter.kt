package com.example.bluematv.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.bluematv.R
import com.example.bluematv.databinding.ItemDownloadBinding
import com.example.bluematv.model.DownloadItem

class DownloadAdapter(
    private val items: MutableList<DownloadItem>,
    private val onItemClick: (DownloadItem) -> Unit
) : RecyclerView.Adapter<DownloadAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemDownloadBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DownloadItem) {
            binding.tvItemTitle.text = item.title
            binding.progressBar.progress = item.progress
            binding.tvItemPercent.text = "${item.progress}%"

            val statusText = when (item.status) {
                "queued" -> "Queued..."
                "downloading" -> "Downloading..."
                "completed" -> "Completed ✓"
                "error" -> "Error: ${item.error ?: "Unknown"}"
                else -> item.status
            }
            binding.tvItemStatus.text = statusText

            // Status-specific text colors
            val statusColor = when (item.status) {
                "completed" -> itemView.context.getColor(R.color.success)
                "error" -> itemView.context.getColor(R.color.error)
                "downloading" -> itemView.context.getColor(R.color.primary_light)
                else -> itemView.context.getColor(R.color.text_secondary)
            }
            binding.tvItemStatus.setTextColor(statusColor)

            // Load thumbnail
            if (!item.thumbnail.isNullOrEmpty()) {
                Glide.with(itemView.context)
                    .load(item.thumbnail)
                    .placeholder(R.drawable.ic_video_placeholder)
                    .centerCrop()
                    .into(binding.ivItemThumbnail)
            }

            itemView.setOnClickListener { onItemClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDownloadBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    fun updateItem(downloadId: String, progress: Int, status: String) {
        val index = items.indexOfFirst { it.downloadId == downloadId }
        if (index >= 0) {
            items[index].progress = progress
            items[index].status = status
            notifyItemChanged(index)
        }
    }

    fun addItem(item: DownloadItem) {
        items.add(0, item)
        notifyItemInserted(0)
    }
}
