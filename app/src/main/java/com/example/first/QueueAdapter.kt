package com.example.first

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections

class QueueAdapter(private var songs: MutableList<Song>) : RecyclerView.Adapter<QueueAdapter.QueueViewHolder>() {

    class QueueViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvQueueSongTitle)
        val tvArtist: TextView = view.findViewById(R.id.tvQueueSongArtist)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_queue_song, parent, false)
        return QueueViewHolder(view)
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        val song = songs[position]
        holder.tvTitle.text = song.title
        if (position == 0) {
            holder.tvArtist.text = "▶ NOW PLAYING • ${song.artist}"
            holder.tvArtist.setTextColor(android.graphics.Color.parseColor("#FFD700")) // Gold
        } else {
            holder.tvArtist.text = song.artist
            holder.tvArtist.setTextColor(android.graphics.Color.parseColor("#BDBDBD"))
        }
    }

    override fun getItemCount() = songs.size

    fun onItemMove(fromPosition: Int, toPosition: Int) {
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                Collections.swap(songs, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                Collections.swap(songs, i, i - 1)
            }
        }
        notifyItemMoved(fromPosition, toPosition)
    }
    
    fun getSongs(): List<Song> = songs
    
    fun updateSongs(newSongs: List<Song>) {
        songs = newSongs.toMutableList()
        notifyDataSetChanged()
    }
}
