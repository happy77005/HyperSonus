package com.example.first

import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class FolderPickerActivity : AppCompatActivity() {
    private val TAG = "FolderPickerActivity"
    private lateinit var rvFolders: RecyclerView
    private lateinit var progressBar: View
    private lateinit var tvEmpty: View
    private lateinit var tvCurrentPath: TextView
    private lateinit var btnSelectFolder: View

    private var currentPath: String = "/storage/emulated/0"
    private val rootPath: String = "/storage/emulated/0"

    private data class MusicFolder(val path: String, val name: String, var songCount: Int, val isUp: Boolean = false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folder_picker)

        rvFolders = findViewById(R.id.rvFolders)
        progressBar = findViewById(R.id.progressBar)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvCurrentPath = findViewById(R.id.tvCurrentPath)
        btnSelectFolder = findViewById(R.id.btnSelectFolder)

        rvFolders.layoutManager = LinearLayoutManager(this)

        btnSelectFolder.setOnClickListener {
            Log.d(TAG, "Folder selected: $currentPath")
            val resultIntent = Intent()
            resultIntent.putExtra("FOLDER_PATH", currentPath)
            setResult(RESULT_OK, resultIntent)
            finish()
        }

        loadFolders(currentPath)
    }

    private fun loadFolders(path: String) {
        currentPath = path
        tvCurrentPath.text = currentPath
        Log.d(TAG, "loadFolders: Navigating to $path")
        
        progressBar.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        
        Thread {
            try {
                val folderList = mutableListOf<MusicFolder>()
                val directory = File(path)
                
                // Add ".." folder if not at root
                if (path != rootPath) {
                    val parent = directory.parentFile
                    if (parent != null) {
                        folderList.add(MusicFolder(parent.absolutePath, ".. (Go Up)", 0, true))
                    }
                }

                val files = directory.listFiles { file -> file.isDirectory && !file.name.startsWith(".") }
                
                if (files != null) {
                    for (file in files) {
                        val count = getSongCountForFolder(file.absolutePath)
                        folderList.add(MusicFolder(file.absolutePath, file.name, count))
                    }
                }

                val sortedFolders = folderList.sortedWith(compareBy({ !it.isUp }, { it.name.lowercase() }))

                runOnUiThread {
                    progressBar.visibility = View.GONE
                    if (sortedFolders.isEmpty()) {
                        tvEmpty.visibility = View.VISIBLE
                    }
                    rvFolders.adapter = FolderAdapter(sortedFolders) { selectedFolder ->
                        if (selectedFolder.isUp || File(selectedFolder.path).isDirectory) {
                            loadFolders(selectedFolder.path)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadFolders: Error listing directories", e)
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    tvEmpty.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private fun getSongCountForFolder(folderPath: String): Int {
        var count = 0
        try {
            val projection = arrayOf("count(*)")
            val selection = "(${MediaStore.Audio.Media.IS_MUSIC} != 0) AND ${MediaStore.Audio.Media.DATA} LIKE ?"
            val selectionArgs = arrayOf("$folderPath/%")
            
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    count = cursor.getInt(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getSongCountForFolder: Error counting songs for $folderPath", e)
        }
        return count
    }

    override fun onBackPressed() {
        if (currentPath != rootPath) {
            val parent = File(currentPath).parentFile
            if (parent != null && parent.absolutePath.startsWith(rootPath)) {
                loadFolders(parent.absolutePath)
                return
            }
        }
        super.onBackPressed()
    }

    private class FolderAdapter(
        private val folders: List<MusicFolder>,
        private val onFolderClick: (MusicFolder) -> Unit
    ) : RecyclerView.Adapter<FolderAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvFolderName)
            val tvPath: TextView = view.findViewById(R.id.tvFolderPath)
            val tvCount: TextView = view.findViewById(R.id.tvSongCount)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_folder_picker, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val folder = folders[position]
            holder.tvName.text = folder.name
            holder.tvPath.text = if (folder.isUp) "" else folder.path
            holder.tvCount.text = if (folder.isUp) "" else "${folder.songCount} items"
            holder.itemView.setOnClickListener { onFolderClick(folder) }
        }

        override fun getItemCount() = folders.size
    }
}
