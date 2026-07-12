package com.jaowzin.stickers.ui

import android.graphics.drawable.Animatable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.jaowzin.stickers.R
import com.jaowzin.stickers.databinding.ItemStickerBinding
import com.jaowzin.stickers.model.StickerItem

class StickerAdapter(
    private val onClick: (StickerItem) -> Unit,
    private val onLoadThumbnail: (StickerItem, ItemStickerBinding) -> Unit
) : ListAdapter<StickerItem, StickerAdapter.StickerViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StickerViewHolder {
        val binding = ItemStickerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return StickerViewHolder(binding)
    }

    override fun onBindViewHolder(holder: StickerViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: StickerViewHolder) {
        (holder.binding.thumbnail.drawable as? Animatable)?.stop()
        holder.binding.thumbnail.setImageResource(R.drawable.ic_file_unknown)
        super.onViewRecycled(holder)
    }

    inner class StickerViewHolder(val binding: ItemStickerBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: StickerItem) = with(binding) {
            thumbnail.tag = item.path
            thumbnail.setImageResource(R.drawable.ic_file_unknown)
            fileName.text = item.name.takeLast(12)
            format.text = buildString {
                append(item.format)
                if (item.animated) append(" • animado")
            }
            videoBadge.visibility = if (item.category == StickerItem.Category.VIDEO) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
            root.setOnClickListener { onClick(item) }
            onLoadThumbnail(item, this)
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<StickerItem>() {
        override fun areItemsTheSame(oldItem: StickerItem, newItem: StickerItem): Boolean = oldItem.path == newItem.path
        override fun areContentsTheSame(oldItem: StickerItem, newItem: StickerItem): Boolean = oldItem == newItem
    }
}
