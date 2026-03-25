package com.example.first

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class FolderShowActivity : AppCompatActivity() {

    private val TAG = "FolderShowActivity"
    private lateinit var rvFolderSongs: RecyclerView
    private lateinit var adapter: SongAdapter
    private lateinit var tvFolderName: TextView
    private lateinit var btnBack: ImageButton

    private lateinit var miniPlayer: View
    private lateinit var tvMiniTitle: TextView
    private lateinit var tvMiniArtist: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnPrevious: ImageButton

    private var musicService: MusicService? = null
    private var isBound = false
    private val folderSongs = mutableListOf<Song>()
    private lateinit var songCache: SongCache

    private val musicListener = object : MusicServiceListener {
        override fun onSongChanged(song: Song) {
            runOnUiThread { updateMiniPlayer(song) }
        }

        override fun onPlaybackStateChanged(isPlaying: Boolean) {
            runOnUiThread {
                btnPlayPause.setImageResource(if (isPlaying) 
                    R.drawable.ic_pause_sharp else R.drawable.ic_play_sharp)
            }
        }

        override fun onPermissionRequired(permission: String) {
            // Permissions usually handled in MainActivity/LandingActivity
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isBound = true
            musicService?.addListener(musicListener)
            
            // Sync mini player
            musicService?.getCurrentSong()?.let { (it as? Song)?.let { s -> updateMiniPlayer(s) } }
            btnPlayPause.setImageResource(if (musicService?.isPlaying() == true) 
                R.drawable.ic_pause_sharp else R.drawable.ic_play_sharp)
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            musicService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folder_show)

        songCache = SongCache(this)
        val folderName = intent.getStringExtra("FOLDER_NAME") ?: "Songs"

        tvFolderName = findViewById(R.id.tvFolderName)
        tvFolderName.text = folderName
        btnBack = findViewById(R.id.btnBack)
        btnBack.setOnClickListener { finish() }

        rvFolderSongs = findViewById(R.id.rvFolderSongs)
        rvFolderSongs.layoutManager = LinearLayoutManager(this)
        
        adapter = SongAdapter { song ->
            playSong(song)
        }
        rvFolderSongs.adapter = adapter

        // Setup Mini Player
        miniPlayer = findViewById(R.id.miniPlayer)
        tvMiniTitle = findViewById(R.id.tvMiniTitle)
        tvMiniArtist = findViewById(R.id.tvMiniArtist)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnNext = findViewById(R.id.btnNext)
        btnPrevious = findViewById(R.id.btnPrevious)

        btnPlayPause.setOnClickListener { togglePlayback() }
        btnNext.setOnClickListener { musicService?.playNext() }
        btnPrevious.setOnClickListener { musicService?.playPrevious() }

        miniPlayer.setOnClickListener {
            val intent = Intent(this, PlayerActivity::class.java)
            startActivity(intent)
        }

        val itemTouchHelperCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val song = adapter.getSongAt(position)
                if (song != null) {
                    musicService?.addToQueue(song)
                    Toast.makeText(this@FolderShowActivity, "Added to queue: ${song.title}", Toast.LENGTH_SHORT).show()
                }
                adapter.notifyItemChanged(position) // Reset swipe state
            }
        }

        ItemTouchHelper(itemTouchHelperCallback).attachToRecyclerView(rvFolderSongs)

        // Adjust for system bars (top notification and bottom navigation)
        val headerView: View = findViewById(R.id.header)
        val root: View = findViewById(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            
            // 1. Position header below notification bar
            headerView.setPadding(headerView.paddingLeft, systemBars.top + 20.dpToPx(), headerView.paddingRight, headerView.paddingBottom)
            
            // 2. Position miniPlayer above navigation bar
            miniPlayer.setPadding(0, 0, 0, systemBars.bottom)
            
            insets
        }

        loadFolderSongs(folderName)
        
        // Bind to MusicService
        val intent = Intent(this, MusicService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun loadFolderSongs(folderName: String) {
        val allSongs = songCache.loadSongs() ?: return
        val filtered = allSongs.filter { song ->
            val rel = song.relativeDir ?: ""
            rel.equals(folderName, ignoreCase = true) || rel.startsWith("$folderName/", ignoreCase = true)
        }
        
        folderSongs.clear()
        folderSongs.addAll(filtered.sortedBy { it.title.lowercase() })
        
        // Grouping for hierarchical view in FolderShowActivity
        val groupedItems = mutableListOf<Any>()
        val folderGroups = filtered.groupBy { song ->
            val rel = song.relativeDir ?: ""
            if (rel.equals(folderName, ignoreCase = true)) {
                "Root"
            } else {
                // Extract next level: Music/Pop/Deeper -> "Pop"
                val sub = rel.substring(folderName.length).trimStart('/')
                if (sub.contains("/")) sub.substringBefore("/") else sub
            }
        }
        
        adapter.isFolderIndexing = true
        val sortedGroupNames = folderGroups.keys.sortedBy { it.lowercase() }
        
        sortedGroupNames.forEach { subName ->
            val subSongs = folderGroups[subName] ?: emptyList()
            groupedItems.add(subName)
            groupedItems.addAll(subSongs.sortedBy { it.title.lowercase() })
        }
        
        adapter.setData(groupedItems)
    }

    private fun playSong(song: Song) {
        val songIndex = folderSongs.indexOfFirst { it.id == song.id }
        if (songIndex != -1) {
            musicService?.setPlaylist(folderSongs, songIndex)
        } else {
            musicService?.playSong(song)
        }
        updateMiniPlayer(song)
        btnPlayPause.setImageResource(R.drawable.ic_pause_sharp)
    }

    private fun togglePlayback() {
        musicService?.let {
            if (it.isPlaying()) {
                it.pauseSong()
                btnPlayPause.setImageResource(R.drawable.ic_play_sharp)
            } else {
                it.resumeSong()
                btnPlayPause.setImageResource(R.drawable.ic_pause_sharp)
            }
        }
    }

    private fun updateMiniPlayer(song: Song) {
        miniPlayer.visibility = View.VISIBLE
        tvMiniTitle.text = song.title
        tvMiniArtist.text = song.artist
        btnPlayPause.setImageResource(if (musicService?.isPlaying() == true) 
            R.drawable.ic_pause_sharp else R.drawable.ic_play_sharp)
        adapter.setPlayingSongId(song.id)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            musicService?.removeListener(musicListener)
            unbindService(connection)
            isBound = false
        }
    }
    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()
}
