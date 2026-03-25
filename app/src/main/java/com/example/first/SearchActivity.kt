package com.example.first

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.first.MusicService.MusicBinder

class SearchActivity : AppCompatActivity() {

    private val TAG = "SearchActivity"
    private lateinit var rvSearchResults: RecyclerView
    private lateinit var etSearch: EditText
    private lateinit var btnBack: ImageButton
    private lateinit var btnClear: ImageButton
    private lateinit var tvNoResults: TextView
    private lateinit var adapter: SongAdapter
    private val allSongs = mutableListOf<Song>()
    private lateinit var songCache: SongCache
    private var musicService: MusicService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as MusicBinder
            musicService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            musicService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        // Bind music service
        Intent(this, MusicService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }

        songCache = SongCache(this)

        rvSearchResults = findViewById(R.id.rvSearchResults)
        etSearch = findViewById(R.id.etSearch)
        btnBack = findViewById(R.id.btnBack)
        btnClear = findViewById(R.id.btnClear)
        tvNoResults = findViewById(R.id.tvNoResults)

        rvSearchResults.layoutManager = LinearLayoutManager(this)
        adapter = SongAdapter { song ->
            playSong(song)
        }
        rvSearchResults.adapter = adapter

        btnBack.setOnClickListener { finish() }
        btnClear.setOnClickListener { etSearch.text.clear() }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterSongs(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Load songs
        loadSongs()
        
        // Focus search immediately
        etSearch.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(etSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun loadSongs() {
        val cached = songCache.loadSongs()
        if (cached != null) {
            allSongs.addAll(cached)
            // No need to show full list immediately since it's search
        }
    }

    private fun filterSongs(query: String) {
        if (query.isEmpty()) {
            adapter.setData(emptyList()) // Show nothing initially? Or all? User said "lists the songs based on user search"
            // Usually showing empty or recent is better. But user might expect filtering.
            // If I show all, it's just a duplicate list view.
            // But usually search starts empty.
            btnClear.visibility = View.GONE
            tvNoResults.visibility = View.GONE
            return
        } else {
            btnClear.visibility = View.VISIBLE
        }

        val filtered = allSongs.filter {
            it.title.contains(query, ignoreCase = true) ||
            it.artist.contains(query, ignoreCase = true)
        }

        if (filtered.isEmpty()) {
            tvNoResults.visibility = View.VISIBLE
            rvSearchResults.visibility = View.GONE
        } else {
            tvNoResults.visibility = View.GONE
            rvSearchResults.visibility = View.VISIBLE
            adapter.setData(filtered)
        }
    }

    private fun playSong(song: Song) {
        if (!isBound || musicService == null) return
        
        // Pass context to main activity? Or just play via service.
        // Playing via service is fine, but we might want to update MainActivity UI.
        // MainActivity uses listener.
        
        // We need to set the playlist correctly.
        // Since we are filtering, playlist context is tricky.
        // Should we set ONLY this song? Or the filtered list?
        // Usually, playing from search sets the context to the Search Results.
        
        // Get current filtered list
        val currentList = (0 until adapter.itemCount).mapNotNull { adapter.getSongAt(it) }
        val index = currentList.indexOfFirst { it.id == song.id }
        
        if (index != -1) {
            musicService?.setPlaylist(currentList, index)
        } else {
            musicService?.playSong(song)
        }
        
        // Maybe open player?
        // User didn't specify. Standard behavior is to start playing.
        // MainActivity will update mini player via listener (if it's running).
        // SearchActivity doesn't have mini player.
        // Maybe finish search and go back? Or stay?
        // User said "onclick back goes back to the songs listing page".
        // Usually clicking a song -> Play.
        // I will finish() to go back to main list, or show PlayerActivity?
        // Most apps open PlayerActivity.
        // I will open PlayerActivity.
        val intent = Intent(this, PlayerActivity::class.java)
        startActivity(intent)
        // Optionally finish search? Some apps do. I'll keep it open.
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}
