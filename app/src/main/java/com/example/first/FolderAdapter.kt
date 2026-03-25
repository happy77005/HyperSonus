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
import coil.transform.RoundedCornersTransformation

data class FolderItem(val name: String, val songs: List<Song>)

class FolderAdapter(
    private var folders: List<FolderItem>,
    private val onFolderClick: (FolderItem) -> Unit
) : RecyclerView.Adapter<FolderAdapter.FolderViewHolder>() {

    fun setData(newFolders: List<FolderItem>) {
        folders = newFolders
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_folder, parent, false)
        return FolderViewHolder(view)
    }

    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        val folder = folders[position]
        holder.bind(folder, onFolderClick)
    }

    override fun getItemCount(): Int = folders.size

    class FolderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvFolderName: TextView = view.findViewById(R.id.tvFolderName)
        private val ivArts = listOf<ImageView>(
            view.findViewById(R.id.ivArt1),
            view.findViewById(R.id.ivArt2),
            view.findViewById(R.id.ivArt3),
            view.findViewById(R.id.ivArt4)
        )

        fun bind(folder: FolderItem, onClick: (FolderItem) -> Unit) {
            tvFolderName.text = folder.name
            
            // Get up to 4 unique album IDs for thumbnails
            val uniqueAlbums = folder.songs
                .distinctBy { it.albumId }
                .take(4)
            
            ivArts.forEachIndexed { index, imageView ->
                if (index < uniqueAlbums.size) {
                    val albumId = uniqueAlbums[index].albumId
                    val artUri = Uri.parse("content://media/external/audio/albumart/$albumId")
                    
                    imageView.load(artUri) {
                        crossfade(true)
                        placeholder(android.R.drawable.ic_menu_report_image)
                        error(android.R.drawable.ic_menu_report_image)
                    }
                } else if (uniqueAlbums.isNotEmpty()) {
                    // Repeat first art if we have less than 4 unique albums? 
                    // Or just clear? Let's clear/placeholder
                    imageView.setImageResource(android.R.drawable.ic_menu_report_image)
                } else {
                    imageView.setImageResource(android.R.drawable.ic_menu_report_image)
                }
            }

            itemView.setOnClickListener { onClick(folder) }
        }
    }
}
