package com.livewallpaper.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.livewallpaper.video.databinding.ItemVideoBinding
import java.util.Collections
import java.util.concurrent.Executors

/** Liste der importierten Videos inklusive Vorschaubild. */
class VideoAdapter(
    private val context: Context,
    private val onRemove: (VideoItem) -> Unit
) : RecyclerView.Adapter<VideoAdapter.Holder>() {

    private val items = mutableListOf<VideoItem>()
    private val thumbs = HashMap<String, Bitmap?>()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun submit(newItems: List<VideoItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun currentItems(): List<VideoItem> = items.toList()

    fun move(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices) return
        Collections.swap(items, from, to)
        notifyItemMoved(from, to)
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.binding.name.text = item.name
        holder.binding.meta.text = buildString {
            if (item.durationMs > 0) {
                append(formatDuration(item.durationMs))
                append("  ·  ")
            }
            append(orientationLabel(item.aspect))
        }
        holder.binding.remove.setOnClickListener {
            val index = holder.bindingAdapterPosition
            if (index != RecyclerView.NO_POSITION) onRemove(items[index])
        }
        loadThumb(item, holder)
    }

    private fun loadThumb(item: VideoItem, holder: Holder) {
        holder.binding.thumb.tag = item.id
        val cached = thumbs[item.id]
        if (thumbs.containsKey(item.id)) {
            holder.binding.thumb.setImageBitmap(cached)
            if (cached == null) holder.binding.thumb.setImageResource(R.drawable.ic_video)
            return
        }
        holder.binding.thumb.setImageResource(R.drawable.ic_video)
        executor.execute {
            val file = Storage.thumbFile(context, item)
            val bitmap = if (file.exists()) {
                runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            } else {
                null
            }
            mainHandler.post {
                thumbs[item.id] = bitmap
                if (holder.binding.thumb.tag == item.id && bitmap != null) {
                    holder.binding.thumb.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun orientationLabel(aspect: Float): String = when {
        aspect <= 0f -> context.getString(R.string.orientation_unknown)
        aspect > 1.05f -> context.getString(R.string.orientation_landscape)
        aspect < 0.95f -> context.getString(R.string.orientation_portrait)
        else -> context.getString(R.string.orientation_square)
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(java.util.Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    fun shutdown() {
        executor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
    }

    class Holder(val binding: ItemVideoBinding) : RecyclerView.ViewHolder(binding.root)
}
