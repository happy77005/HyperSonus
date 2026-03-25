package com.example.first

import android.content.ContentUris
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class SongAdapter(private val onSongClick: (Song) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items = mutableListOf<Any>()
    private var playingSongId: Long = -1

    var isFolderIndexing: Boolean = false

    companion object {
        private const val TYPE_SONG = 0
        private const val TYPE_ALBUM_HEADER = 1
    }

    class SongViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvTitle)
        val tvArtist: TextView = view.findViewById(R.id.tvArtist)
        val tvHqBadge: TextView = view.findViewById(R.id.tvHqBadge)
        val ivThumbnail: ImageView = view.findViewById(R.id.ivThumbnail)
    }

    class AlbumViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvAlbumName: TextView = view.findViewById(R.id.tvAlbumName)
    }

    fun setData(newItems: List<Any>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun addSongs(newSongs: List<Song>) {
        val startPos = items.size
        items.addAll(newSongs)
        notifyItemRangeInserted(startPos, newSongs.size)
    }

    override fun getItemViewType(position: Int): Int {
        return if (items[position] is Song) TYPE_SONG else TYPE_ALBUM_HEADER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_SONG) {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
            SongViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_album_header, parent, false)
            AlbumViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (holder is SongViewHolder && item is Song) {
            holder.tvTitle.text = item.title
            holder.tvArtist.text = item.artist
            
            val path = item.path ?: ""
            val isHq = path.endsWith(".flac", true) || 
                       path.endsWith(".wav", true) || 
                       path.endsWith(".aiff", true) || 
                       path.endsWith(".dsd", true) || 
                       path.endsWith(".dsf", true) || 
                       path.endsWith(".alac", true)
            
            holder.tvHqBadge.visibility = if (isHq) View.VISIBLE else View.GONE
            
            // Optimization: Clear previous art to avoid flickering
            holder.ivThumbnail.setImageResource(android.R.drawable.ic_menu_report_image)

            // Optimized Loading: Use system album art URI which is background-friendly and cached.
            val artUri = ContentUris.withAppendedId(
                Uri.parse("content://media/external/audio/albumart"),
                item.albumId
            )
            
            holder.ivThumbnail.load(artUri) {
                crossfade(true)
                placeholder(android.R.drawable.ic_menu_report_image)
                error(android.R.drawable.ic_menu_report_image)
            }
            
            // Highlight playing song
            if (item.id == playingSongId) {
                holder.itemView.setBackgroundColor(0x33000000.toInt()) // Subtle dark overlay
            } else {
                holder.itemView.background = null
            }
            
            holder.itemView.setOnClickListener { onSongClick(item) }
        } else if (holder is AlbumViewHolder && item is String) {
            holder.tvAlbumName.text = item
        }
    }

    fun setPlayingSongId(id: Long) {
        if (playingSongId == id) return
        
        val oldId = playingSongId
        playingSongId = id
        
        // Find and update the old playing song
        val oldIndex = items.indexOfFirst { it is Song && it.id == oldId }
        if (oldIndex != -1) {
            notifyItemChanged(oldIndex)
        }
        
        // Find and update the new playing song
        val newIndex = items.indexOfFirst { it is Song && it.id == id }
        if (newIndex != -1) {
            notifyItemChanged(newIndex)
        }
    }

    fun getPositionForSection(char: Char): Int {
        val uppercaseChar = char.uppercaseChar()
        val hasHeaders = items.any { it is String }

        for (i in items.indices) {
            val item = items[i]
            if (isFolderIndexing && hasHeaders) {
                // Folder Mode: Explicitly look for Header Strings
                if (item is String) {
                    val firstChar = item.firstOrNull()?.uppercaseChar() ?: continue
                    if (uppercaseChar == '#') {
                        if (!firstChar.isLetter()) return i
                    } else if (firstChar == uppercaseChar) {
                        return i
                    }
                }
            } else {
                // Song Mode: Explicitly look for Song Titles
                if (item is Song) {
                    val firstChar = item.title.firstOrNull()?.uppercaseChar() ?: continue
                    if (uppercaseChar == '#') {
                        if (!firstChar.isLetter()) return i
                    } else if (firstChar == uppercaseChar) {
                        return i
                    }
                }
            }
        }
        return -1
    }

    fun getAvailableSections(): List<Char> {
        val sections = mutableSetOf<Char>()
        val hasHeaders = items.any { it is String }

        for (item in items) {
            val firstChar = if (isFolderIndexing && hasHeaders) {
                (item as? String)?.firstOrNull()
            } else {
                (item as? Song)?.title?.firstOrNull()
            }?.uppercaseChar() ?: continue

            if (firstChar.isLetter()) {
                sections.add(firstChar)
            } else {
                sections.add('#')
            }
        }
        return sections.toList().sorted()
    }

    fun getSongAt(position: Int): Song? {
        return items.getOrNull(position) as? Song
    }

    override fun getItemCount() = items.size
}
