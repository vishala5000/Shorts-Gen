package com.shortsgen.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.shortsgen.app.R
import com.shortsgen.app.model.GeneratedVideo
import java.io.File
import java.util.Locale

/** List of finished clips with play / save / share actions. */
class VideoAdapter(
    private val onPlay: (GeneratedVideo) -> Unit,
    private val onSave: (GeneratedVideo) -> Unit,
    private val onShare: (GeneratedVideo) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {

    private val items = ArrayList<GeneratedVideo>()

    fun submit(list: List<GeneratedVideo>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
        return VideoViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.video_title)
        private val subtitle: TextView = itemView.findViewById(R.id.video_subtitle)
        private val play: MaterialButton = itemView.findViewById(R.id.video_play)
        private val save: MaterialButton = itemView.findViewById(R.id.video_save)
        private val share: MaterialButton = itemView.findViewById(R.id.video_share)

        fun bind(video: GeneratedVideo) {
            title.text = video.fileName
            subtitle.text = String.format(
                Locale.US,
                "%s  •  %.1fs  •  %.2f MB  •  1080×1920 H.264",
                video.line.take(48) + if (video.line.length > 48) "…" else "",
                video.durationMs / 1000f,
                video.sizeBytes / 1_048_576f
            )
            val exists = File(video.path).exists()
            play.isEnabled = exists
            save.isEnabled = exists
            share.isEnabled = exists
            play.setOnClickListener { onPlay(video) }
            save.setOnClickListener { onSave(video) }
            share.setOnClickListener { onShare(video) }
        }
    }
}
