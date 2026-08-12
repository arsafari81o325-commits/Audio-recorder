package com.example.audiorecorder

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class RecordingAdapter(
    private val onPlay: (RecordingEntity) -> Unit,
    private val onDelete: (RecordingEntity) -> Unit,
    private val onInfo: (RecordingEntity) -> Unit,
    private val onRename: (RecordingEntity) -> Unit,
    private val onShare: (RecordingEntity) -> Unit
) : ListAdapter<RecordingEntity, RecordingAdapter.ViewHolder>(DiffCallback()) {

    class DiffCallback : DiffUtil.ItemCallback<RecordingEntity>() {
        override fun areItemsTheSame(old: RecordingEntity, new: RecordingEntity) = old.id == new.id
        override fun areContentsTheSame(old: RecordingEntity, new: RecordingEntity) = old == new
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tvName)
        val tvInfo: TextView = itemView.findViewById(R.id.tvInfo)
        val btnPlay: ImageButton = itemView.findViewById(R.id.btnPlay)
        val btnMore: ImageButton = itemView.findViewById(R.id.btnMore)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recording, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.tvName.text = item.displayName

        val duration = String.format(
            "%02d:%02d",
            TimeUnit.MILLISECONDS.toMinutes(item.durationMs),
            TimeUnit.MILLISECONDS.toSeconds(item.durationMs) % 60
        )
        val sizeMB = item.fileSizeBytes / (1024.0 * 1024.0)
        val date = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
            .format(Date(item.createdAt))
        val bookmarkCount = item.bookmarks.split(",").count { it.isNotBlank() }
        val bookmarkSuffix = if (bookmarkCount > 0) "  |  🔖 $bookmarkCount" else ""

        holder.tvInfo.text = "$duration  |  %.1f MB  |  $date$bookmarkSuffix".format(sizeMB)

        holder.btnPlay.setOnClickListener { onPlay(item) }
        holder.tvInfo.setOnClickListener { onInfo(item) }

        holder.btnMore.setOnClickListener { anchor ->
            val popup = PopupMenu(anchor.context, anchor)
            popup.menu.add(0, 1, 0, "🔖 نمایش علامت‌ها")
            popup.menu.add(0, 2, 1, "✏️ تغییر نام")
            popup.menu.add(0, 3, 2, "📤 اشتراک‌گذاری")
            popup.menu.add(0, 4, 3, "🗑 حذف")
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    1 -> onInfo(item)
                    2 -> onRename(item)
                    3 -> onShare(item)
                    4 -> onDelete(item)
                }
                true
            }
            popup.show()
        }
    }
}
