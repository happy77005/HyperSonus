package com.example.first

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: SongAdapter
    private lateinit var folderAdapter: FolderAdapter
    private val allSongs = mutableListOf<Song>()
    private lateinit var songCache: SongCache
    private var isScanning = false
    private var isGridView = false

    private var musicService: MusicService? = null
    private var isBound = false
    private var currentSong: Song? = null

    private lateinit var miniPlayer: View
    private lateinit var tvMiniTitle: TextView
    private lateinit var tvMiniArtist: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnPrev: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnFolderView: ImageButton
    private lateinit var btnSort: ImageButton
    private lateinit var btnSearch: ImageButton
    private lateinit var searchContainer: View
    private lateinit var etSearch: android.widget.EditText
    private lateinit var btnCloseSearch: ImageButton
    private lateinit var sideBarContainer: LinearLayout
    private lateinit var loadingLayout: View

    private val musicListener = object : MusicServiceListener {
        override fun onSongChanged(song: Song) {
            runOnUiThread {
                updateMiniPlayer(song)
            }
        }

        override fun onPlaybackStateChanged(isPlaying: Boolean) {
            runOnUiThread {
                btnPlayPause.setImageResource(if (isPlaying) 
                    R.drawable.ic_pause_sharp else R.drawable.ic_play_sharp)
            }
        }

        override fun onPermissionRequired(permission: String) {
            if (permission == android.Manifest.permission.BLUETOOTH_CONNECT) {
                runOnUiThread {
                    showBluetoothPermissionDialog()
                }
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d(TAG, "Bluetooth permission granted")
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            Log.d(TAG, "Service Connected - Enabling interactions")
            val binder = service as MusicService.MusicBinder
            val serviceInstance = binder.getService()
            musicService = serviceInstance
            isBound = true

            // Set up listener for automatic song changes
            serviceInstance.addListener(musicListener)
            
            // Sync initial state
            serviceInstance.getCurrentSong()?.let { (it as? Song)?.let { s -> updateMiniPlayer(s) } }
            btnPlayPause.setImageResource(if (serviceInstance.isPlaying()) 
                R.drawable.ic_pause_sharp else R.drawable.ic_play_sharp)
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            Log.d(TAG, "Service Disconnected")
            isBound = false
            musicService = null
        }
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recyclerView)
        miniPlayer = findViewById(R.id.miniPlayer)
        tvMiniTitle = findViewById(R.id.tvMiniTitle)
        tvMiniArtist = findViewById(R.id.tvMiniArtist)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnNext = findViewById(R.id.btnNext)
        btnPrev = findViewById(R.id.btnPrevious)
        btnSettings = findViewById(R.id.btnSettings)
        btnSort = findViewById(R.id.btnSort)
        btnSearch = findViewById(R.id.btnSearch)
        btnFolderView = findViewById(R.id.btnFolderView)
        
        folderAdapter = FolderAdapter(emptyList()) { folder ->
            // Launch separate activity for folder contents
            val intent = Intent(this, FolderShowActivity::class.java)
            intent.putExtra("FOLDER_NAME", folder.name)
            startActivity(intent)
        }

        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        isGridView = prefs.getBoolean("IS_GRID_VIEW", false)

        btnFolderView.setOnClickListener {
            isGridView = !isGridView
            prefs.edit().putBoolean("IS_GRID_VIEW", isGridView).apply()
            updateViewMode()
        }
        // Search variables removed
        sideBarContainer = findViewById(R.id.sideBarContainer)
        loadingLayout = findViewById(R.id.loadingLayout)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = SongAdapter { song ->
            playSong(song)
        }
        recyclerView.adapter = adapter

        // Initially disable until service is bound
        
        btnPlayPause.setOnClickListener {
            togglePlayback()
        }

        btnNext.setOnClickListener {
            musicService?.playNext()
        }

        btnPrev.setOnClickListener {
            musicService?.playPrevious()
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnSort.setOnClickListener {
            showSortMenu()
        }

        btnSearch.setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }

        // Inline search logic removed in favor of SearchActivity

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
                    Toast.makeText(this@MainActivity, "Added to queue: ${song.title}", Toast.LENGTH_SHORT).show()
                }
                adapter.notifyItemChanged(position) // Reset swipe state
            }
        }

        ItemTouchHelper(itemTouchHelperCallback).attachToRecyclerView(recyclerView)

        setupFastScroller()

        // Handle window insets to avoid overlapping with system status bar and navigation bar
        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            
            // Push the title below the status bar
            val tvAppTitle: TextView = findViewById(R.id.tvAppTitle)
            tvAppTitle.setPadding(
                tvAppTitle.paddingLeft,
                systemBars.top + 20.dpToPx(), // systemBars.top handles the notification bar
                tvAppTitle.paddingRight,
                tvAppTitle.paddingBottom
            )



            // Ensure miniPlayer is not hidden by the gesture navigation bar
            miniPlayer.setPadding(0, 0, 0, systemBars.bottom)
            insets
        }

        Intent(this, MusicService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
            startService(intent) // Also start it so it keeps running
        }

        songCache = SongCache(this)
        checkIntentAndLoad()
        
        // Apply saved view mode once data starts loading (or after checkIntentAndLoad)
        updateViewMode()

        // Proactive Bluetooth permission check
        checkBluetoothPermissionOnStartup()
    }

    private fun checkBluetoothPermissionOnStartup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
                val appLaunchCount = prefs.getInt("app_launch_count", 0) + 1
                prefs.edit().putInt("app_launch_count", appLaunchCount).apply()
                
                // Request on first launch or every 5th launch to ensure user is aware
                if (appLaunchCount == 1 || appLaunchCount % 5 == 0) {
                    requestPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }
        }
    }

    private fun playSong(song: Song) {
        Log.d(TAG, "playSong sequence started for: ${song.title} (URI: ${song.uri})")
        
        if (!isBound || musicService == null) {
            Log.e(TAG, "playSong failed: MusicService NOT bound")
            Toast.makeText(this, "Service not ready", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d(TAG, "playSong: MusicService is bound, updating playlist")

        // Pass the full list to the service to enable next/previous
        val songIndex = allSongs.indexOfFirst { it.id == song.id }
        if (songIndex != -1) {
            Log.d(TAG, "playSong: Song found in list at index $songIndex, setting playlist")
            musicService?.setPlaylist(allSongs, songIndex)
        } else {
            Log.w(TAG, "playSong: Song not in current list, playing single track")
            musicService?.playSong(song)
        }
        
        runOnUiThread {
            Log.d(TAG, "playSong: Updating UI on main thread")
            updateMiniPlayer(song)
            btnPlayPause.setImageResource(R.drawable.ic_pause_sharp)
        }
    }

    private fun updateViewMode() {
        if (isGridView) {
            val priorityFolders = listOf("Downloads", "Music", "Songs")
            val folders = allSongs.groupBy { song ->
                val rel = song.relativeDir ?: ""
                if (rel.contains("/")) rel.substringBefore("/") else rel.ifEmpty { "Music" }
            }
                .map { (name, songs) -> FolderItem(name, songs) }
                .sortedWith(compareByDescending<FolderItem> { item ->
                    priorityFolders.any { it.equals(item.name, ignoreCase = true) }
                }.thenBy { it.name.lowercase() })
            
            folderAdapter.setData(folders)
            recyclerView.layoutManager = GridLayoutManager(this, 3)
            recyclerView.adapter = folderAdapter
            btnFolderView.setImageResource(android.R.drawable.ic_menu_agenda) // Switch back icon
            sideBarContainer.visibility = View.GONE
        } else {
            recyclerView.layoutManager = LinearLayoutManager(this)
            recyclerView.adapter = adapter
            btnFolderView.setImageResource(android.R.drawable.ic_menu_directions)
            sideBarContainer.visibility = View.VISIBLE
            
            val priorityFolders = listOf("Downloads", "Music", "Songs")
            val groupedItems = mutableListOf<Any>()
            
            // Check if we should index by folder (Grid View state or scan type)
            val scanType = intent.getStringExtra("SCAN_TYPE") ?: "FULL"
            
            if (adapter.isFolderIndexing || scanType == "FULL") {
                val folderGroups = allSongs.groupBy { song ->
                    val rel = song.relativeDir ?: ""
                    if (scanType == "FOLDER") {
                        // For a specific folder scan, group by immediate subfolders relative to that root
                        val currentFolderPath = songCache.loadStore()?.rootPath ?: ""
                        val relative = if (currentFolderPath.isNotEmpty() && rel.startsWith(currentFolderPath, ignoreCase = true)) {
                             rel.substring(currentFolderPath.length).trimStart('/')
                        } else rel
                        
                        if (relative.contains("/")) relative.substringBefore("/") else "Root"
                    } else {
                        // For a Full Scan, group by the top-level folder segment
                        if (rel.contains("/")) rel.substringBefore("/") else rel.ifEmpty { "Music" }
                    }
                }
                
                val sortedGroupNames = folderGroups.keys.sortedWith(compareByDescending<String> { name ->
                    priorityFolders.any { it.equals(name, ignoreCase = true) }
                }.thenBy { it.lowercase() })
                
                sortedGroupNames.forEach { folderName ->
                    val folderSongs = folderGroups[folderName] ?: emptyList()
                    groupedItems.add(folderName)
                    groupedItems.addAll(applySort(folderSongs))
                }
                adapter.setData(groupedItems)
            } else {
                adapter.setData(applySort(allSongs))
            }
        }
    }

    private fun updateMiniPlayer(song: Song) {
        Log.d(TAG, "updateMiniPlayer: Updating for ${song.title}")
        currentSong = song
        miniPlayer.visibility = View.VISIBLE
        tvMiniTitle.text = song.title
        tvMiniArtist.text = song.artist
        btnPlayPause.setImageResource(if (musicService?.isPlaying() == true) 
            R.drawable.ic_pause_sharp else R.drawable.ic_play_sharp)
        
        // Update adapter to highlight current song
        adapter.setPlayingSongId(song.id)
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

    private fun checkIntentAndLoad() {
        val forceRescan = intent.getBooleanExtra("FORCE_RESCAN", false)
        val scanType = intent.getStringExtra("SCAN_TYPE") ?: "FULL"
        val folderUriString = intent.getStringExtra("FOLDER_URI")
        val tvAppTitle: TextView = findViewById(R.id.tvAppTitle)

        // Reset title
        if (scanType == "FULL") {
             tvAppTitle.text = "My Music"
        }
        adapter.isFolderIndexing = true

        // 1. Try Instant-Load if NOT forcing a rescan
        if (!forceRescan) {
            val store = songCache.loadStore()
            val currentRoot = if (scanType == "FOLDER") {
                if (folderUriString != null && folderUriString.startsWith("content://")) {
                     getPathFromUri(Uri.parse(folderUriString))
                } else folderUriString
            } else null
            
            if (store != null && store.scanType == scanType && store.rootPath == currentRoot) {
                Log.d(TAG, "checkIntentAndLoad: Instant-loading ${store.songs.size} songs from persistent store")
                val sorted = applySort(store.songs)
                allSongs.clear()
                allSongs.addAll(sorted)
                
                updateViewMode() // Use persistent view mode
                hideLoading()
                setupFastScroller()
                return // SUCCESS: Instant-Load complete
            }
        }

        // 2. Perform Fresh Scan
        if (isScanning) return
        isScanning = true
        
        loadingLayout.visibility = View.VISIBLE
        
        allSongs.clear()
        adapter.setData(emptyList()) // Clear adapter too

        Thread {
        try {
            if (scanType == "FOLDER" && folderUriString != null) {
                var folderPath: String? = if (folderUriString.startsWith("content://")) {
                    getPathFromUri(Uri.parse(folderUriString))
                } else {
                    folderUriString
                }
                
                if (folderPath != null && !folderPath.endsWith("/")) {
                    folderPath = "$folderPath/"
                }

                if (folderPath == null) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Error: Could not resolve folder path.", Toast.LENGTH_LONG).show()
                        hideLoading()
                    }
                    return@Thread
                }

                val folderName = folderPath.trimEnd('/').substringAfterLast('/')
                Log.d(TAG, "checkIntentAndLoad: folderPath resolved: $folderPath, folderName: $folderName")
                runOnUiThread {
                    tvAppTitle.text = "Music in $folderName"
                    adapter.isFolderIndexing = true
                }

                val songs = fetchSongs(folderPath) { chunk ->
                    Log.d(TAG, "checkIntentAndLoad: Chunk loaded with sorted size: ${chunk.size}")
                    runOnUiThread {
                        if (allSongs.isEmpty()) hideLoading() // Hide on first chunk
                        allSongs.addAll(chunk)
                        
                        // For folder scan, group by subfolder name relative to the selected root
                        val groupedItems = mutableListOf<Any>()
                        val currentFolderPath = folderPath ?: ""
                        
                        val folderGroups = allSongs.groupBy { song ->
                            val path = song.path ?: ""
                            val relative = if (path.startsWith(currentFolderPath, ignoreCase = true)) {
                                path.substring(currentFolderPath.length).trimStart('/')
                            } else path.trimStart('/')
                            
                            if (relative.contains("/")) relative.substringBefore("/") else "Root"
                        }

                        folderGroups.forEach { (folderName, folderSongs) ->
                            groupedItems.add(folderName)
                            groupedItems.addAll(folderSongs)
                        }
                        
                        adapter.setData(groupedItems)
                        setupFastScroller() // Refresh scroller with new letters
                    }
                }

                runOnUiThread {
                    if (allSongs.isEmpty()) hideLoading() // Ensure hide even if empty
                    
                    val sorted = applySort(allSongs)
                    allSongs.clear()
                    allSongs.addAll(sorted)
                    
                    val currentRoot = if (scanType == "FOLDER") {
                        if (folderUriString != null && folderUriString.startsWith("content://")) {
                             getPathFromUri(Uri.parse(folderUriString))
                        } else folderUriString
                    } else null
                    
                    songCache.saveStore(SongStore(
                        scanType = scanType,
                        rootPath = currentRoot,
                        songs = allSongs,
                        lastUpdated = System.currentTimeMillis()
                    ))
                    
                    Toast.makeText(this@MainActivity, "Loaded ${allSongs.size} songs", Toast.LENGTH_SHORT).show()
                    updateViewMode()
                    isScanning = false
                }
            } else {
                runOnUiThread {
                    tvAppTitle.text = "My Music"
                    adapter.isFolderIndexing = false
                }
                val songs = fetchSongs(null) { chunk ->
                    runOnUiThread {
                        if (allSongs.isEmpty()) hideLoading() // Hide on first chunk
                        allSongs.addAll(chunk)
                        adapter.addSongs(chunk)
                        setupFastScroller() // Refresh scroller
                    }
                }
                
                runOnUiThread {
                    if (allSongs.isEmpty()) hideLoading() // Ensure hide even if empty
                    
                    val sorted = applySort(allSongs)
                    allSongs.clear()
                    allSongs.addAll(sorted)
                    
                    adapter.setData(allSongs)
                    
                    songCache.saveStore(SongStore(
                        scanType = scanType,
                        rootPath = null,
                        songs = allSongs,
                        lastUpdated = System.currentTimeMillis()
                    ))
                    
                    Toast.makeText(this@MainActivity, "Loaded ${allSongs.size} songs", Toast.LENGTH_SHORT).show()
                    updateViewMode()
                    isScanning = false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning music", e)
            runOnUiThread {
                isScanning = false
                Toast.makeText(this, "Scan failed: ${e.message}", Toast.LENGTH_LONG).show()
                hideLoading()
            }
        }
    }.start()
}

    private fun showBluetoothPermissionDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Bluetooth Hi-Res Details")
            .setMessage("HyperPlay has detected a Bluetooth device. To show technical details like LDAC 96kHz and bit depth, the app needs 'Nearby Devices' permission. Would you like to enable it?")
            .setPositiveButton("Settings") { _, _ ->
                requestPermissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
            }
            .setNegativeButton("Maybe Later", null)
            .show()
    }

    private fun hideLoading() {
        loadingLayout.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                loadingLayout.visibility = View.GONE
            }
    }


    private fun fetchSongs(filterPath: String?, onChunkLoaded: ((List<Song>) -> Unit)? = null): List<Song> {
    val songs = mutableListOf<Song>()
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.DATA,
        MediaStore.Audio.Media.DATE_ADDED,
        MediaStore.Audio.Media.SIZE
    )
    
    // Resolve column indices once using a sample query
    var idCol = 0; var titleCol = 0; var artistCol = 0; var albumCol = 0; var albumIdCol = 0
    var durationCol = 0; var dataCol = 0; var dateAddedCol = 0; var sizeCol = 0
    contentResolver.query(collection, projection, "1=0", null, null)?.use { cursor ->
        idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
        dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
        sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
    }

    // Selection: Be more inclusive with audio files. Some might not be tagged as music but are playable.
    val baseSelection = "(${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.IS_AUDIOBOOK} != 0 OR ${MediaStore.Audio.Media.IS_PODCAST} != 0)"

    // Diagnostic: Check if we can find ANY audio at all to verify volume
    if (filterPath != null) {
        contentResolver.query(collection, arrayOf(MediaStore.Audio.Media.DATA), baseSelection, null, null)?.use { c ->
            if (c.moveToFirst()) {
                Log.d(TAG, "fetchSongs Diagnostic: Found example audio in MediaStore at: ${c.getString(0)}")
            } else {
                Log.w(TAG, "fetchSongs Diagnostic: NO audio found in MediaStore at all for volume: $collection")
            }
        }
    }

    val pathPatterns = mutableListOf<String>()
    if (filterPath != null) {
        val cleanPath = filterPath.trimEnd('/')
        pathPatterns.add("$cleanPath/%")
        // Fallback for /sdcard vs /storage/emulated/0
        if (cleanPath.startsWith("/storage/emulated/0")) {
            pathPatterns.add(cleanPath.replace("/storage/emulated/0", "/sdcard") + "/%")
        } else if (cleanPath.startsWith("/sdcard")) {
            pathPatterns.add(cleanPath.replace("/sdcard", "/storage/emulated/0") + "/%")
        }
    }

    val selection = if (filterPath != null) {
        val patternGroup = pathPatterns.joinToString(" OR ") { "${MediaStore.Audio.Media.DATA} LIKE ?" }
        "$baseSelection AND ($patternGroup)"
    } else {
        baseSelection
    }
    
    val selectionArgs = if (filterPath != null) {
        pathPatterns.toTypedArray()
    } else {
        null
    }

    // PRIORITY SCAN LOGIC
    if (filterPath == null) {
        val priorityPaths = listOf("/storage/emulated/0/Downloads", "/storage/emulated/0/Music", "/storage/emulated/0/Songs", "/sdcard/Downloads", "/sdcard/Music", "/sdcard/Songs")
        val prioritySelection = "$baseSelection AND (${priorityPaths.joinToString(" OR ") { "${MediaStore.Audio.Media.DATA} LIKE ?" }})"
        val priorityArgs = priorityPaths.map { "$it/%" }.toTypedArray()
        
        Log.d(TAG, "fetchSongs: Priority scan started")
        contentResolver.query(collection, projection, prioritySelection, priorityArgs, "${MediaStore.Audio.Media.DATA} ASC")?.use { cursor ->
            processLinearChunks(cursor, cursor.count, onChunkLoaded, songs, idCol, titleCol, artistCol, albumCol, albumIdCol, durationCol, dataCol, dateAddedCol, sizeCol, null)
        }
        
        // Exclude prioritized from main query
        val mainSelection = "$baseSelection AND NOT (${priorityPaths.joinToString(" OR ") { "${MediaStore.Audio.Media.DATA} LIKE ?" }})"
        contentResolver.query(collection, projection, mainSelection, priorityArgs, "${MediaStore.Audio.Media.DATA} ASC")?.use { cursor ->
            processLinearChunks(cursor, cursor.count, onChunkLoaded, songs, idCol, titleCol, artistCol, albumCol, albumIdCol, durationCol, dataCol, dateAddedCol, sizeCol, null)
        }
        return songs
    }

    // Sort by path for easier subfolder grouping if filtering by path
    val sortOrder = if (filterPath != null) {
        "${MediaStore.Audio.Media.DATA} ASC"
    } else {
        "${MediaStore.Audio.Media.TITLE} ASC"
    }

    Log.d(TAG, "fetchSongs: query started with filterPath: $filterPath")
    Log.d(TAG, "fetchSongs: selection: $selection")
    Log.d(TAG, "fetchSongs: selectionArgs: ${selectionArgs?.joinToString()}")
    
    contentResolver.query(
        collection,
        projection,
        selection,
        selectionArgs,
        sortOrder
    )?.use { cursor ->
        val total = cursor.count
        Log.d(TAG, "fetchSongs: cursor count: $total")
        if (total == 0) return emptyList()

        if (filterPath != null) {
            // SCENARIO-BASED FOLDER SCAN
            // Optimization: Detect subfolders on the fly rather than a full first pass
            // But for simplicity of logic, we'll keep the current approach but make it more robust
            val paths = mutableListOf<String>()
            while (cursor.moveToNext()) {
                paths.add(cursor.getString(dataCol))
            }
            cursor.moveToPosition(-1) // Reset

            val cleanFilterPath = filterPath.trimEnd('/')
            val subfolders = paths.map { path ->
                // Try to find which pattern matched and get relative path
                var relativePath = ""
                pathPatterns.forEach { pattern ->
                    val base = pattern.trimEnd('/', '%')
                    if (path.startsWith(base, ignoreCase = true)) {
                        relativePath = path.substring(base.length).trimStart('/')
                        return@forEach
                    }
                }
                if (relativePath.contains("/")) relativePath.substringBefore("/") else ""
            }.filter { it.isNotEmpty() }.distinct()

            if (subfolders.isNotEmpty()) {
                // Scenario 1: Selected folder has subfolders (Albums)
                Log.d(TAG, "fetchSongs: Detected subfolders: ${subfolders.size}")
                val folderChunkSize = (subfolders.size / 4).coerceAtLeast(1)
                var currentFolderIdx = 0
                val currentChunk = mutableListOf<Song>()
                
                val cleanBase = filterPath.trimEnd('/')
                
                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataCol)
                    val relativePath = if (path.startsWith(cleanBase, ignoreCase = true)) {
                        path.substring(cleanBase.length).trimStart('/')
                    } else path.trimStart('/')
                    
                    val folderName = if (relativePath.contains("/")) relativePath.substringBefore("/") else ""
                    
                    val song = createSongFromCursor(cursor, idCol, titleCol, artistCol, albumCol, albumIdCol, durationCol, dataCol, dateAddedCol, sizeCol, filterPath)
                    songs.add(song)
                    currentChunk.add(song)

                    // If we finished a folder AND we've reached a quarter of FOLDERS
                    val nextFolderName = if (cursor.moveToNext()) {
                        val nextPath = cursor.getString(dataCol)
                        cursor.moveToPrevious() // go back
                        val nextRelative = if (nextPath.startsWith(cleanBase, ignoreCase = true)) {
                            nextPath.substring(cleanBase.length).trimStart('/')
                        } else nextPath.trimStart('/')
                        if (nextRelative.contains("/")) nextRelative.substringBefore("/") else ""
                    } else null

                    if (folderName != nextFolderName) {
                        // Finished one subfolder
                        if (folderName.isNotEmpty()) currentFolderIdx++
                        
                        // Delivery logic: if we hit a quarter-boundary of folders, OR it's the last folder
                        if ((currentFolderIdx > 0 && currentFolderIdx % folderChunkSize == 0 || nextFolderName == null) && onChunkLoaded != null) {
                            if (currentChunk.isNotEmpty()) {
                                onChunkLoaded(currentChunk.toList())
                                currentChunk.clear()
                            }
                        }
                    }
                }
            } else {
                // Scenario 2: All songs are linear in the selected folder
                Log.d(TAG, "fetchSongs: Linear songs in folder")
                processLinearChunks(cursor, total, onChunkLoaded, songs, idCol, titleCol, artistCol, albumCol, albumIdCol, durationCol, dataCol, dateAddedCol, sizeCol, filterPath)
            }
        } else {
            // FULL SCAN - Standard Linear Quarters
            Log.d(TAG, "fetchSongs: Full scan linear loading")
            processLinearChunks(cursor, total, onChunkLoaded, songs, idCol, titleCol, artistCol, albumCol, albumIdCol, durationCol, dataCol, dateAddedCol, sizeCol, null)
        }
    }
    return songs
}

private fun processLinearChunks(
    cursor: android.database.Cursor,
    total: Int,
    onChunkLoaded: ((List<Song>) -> Unit)?,
    songs: MutableList<Song>,
    idCol: Int, titleCol: Int, artistCol: Int, albumCol: Int, albumIdCol: Int, durationCol: Int, dataCol: Int, dateAddedCol: Int, sizeCol: Int,
    rootPath: String?
) {
    val chunkSize = (total / 4).coerceAtLeast(1)
    val currentChunk = mutableListOf<Song>()
    
    while (cursor.moveToNext()) {
        val song = createSongFromCursor(cursor, idCol, titleCol, artistCol, albumCol, albumIdCol, durationCol, dataCol, dateAddedCol, sizeCol, rootPath)
        songs.add(song)
        currentChunk.add(song)
        
        if (currentChunk.size >= chunkSize && onChunkLoaded != null && songs.size < total) {
            onChunkLoaded(currentChunk.toList())
            currentChunk.clear()
        }
    }
    
    if (currentChunk.isNotEmpty() && onChunkLoaded != null) {
        onChunkLoaded(currentChunk.toList())
    }
}

private fun createSongFromCursor(
    cursor: android.database.Cursor,
    idCol: Int, titleCol: Int, artistCol: Int, albumCol: Int, albumIdCol: Int, durationCol: Int, dataCol: Int, dateAddedCol: Int, sizeCol: Int,
    rootPath: String? = null
): Song {
    val id = cursor.getLong(idCol)
    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    val size = cursor.getLong(sizeCol)
    // Memory Optimization: Avoid heavy I/O in scan if possible, or keep it minimal
    val props = MetadataUtils.getAudioProperties(this, uri)
    val path = cursor.getString(dataCol)
    val relativeDir = if (rootPath != null && path.startsWith(rootPath, ignoreCase = true)) {
        val rel = path.substring(rootPath.length).trimStart('/')
        if (rel.contains("/")) rel.substringBeforeLast('/') else ""
    } else if (rootPath == null) {
        // Full Scan Fallback: derive relative path from storage root
        val storageRoot = "/storage/emulated/0/"
        val sdcardRoot = "/sdcard/"
        when {
            path.startsWith(storageRoot, ignoreCase = true) -> {
                val rel = path.substring(storageRoot.length)
                if (rel.contains("/")) rel.substringBeforeLast('/') else ""
            }
            path.startsWith(sdcardRoot, ignoreCase = true) -> {
                val rel = path.substring(sdcardRoot.length)
                if (rel.contains("/")) rel.substringBeforeLast('/') else ""
            }
            else -> null
        }
    } else null

    return Song(
        id,
        cursor.getString(titleCol),
        cursor.getString(artistCol),
        cursor.getString(albumCol),
        cursor.getLong(albumIdCol),
        cursor.getInt(durationCol),
        uri,
        cursor.getLong(dateAddedCol),
        path,
        props.first,
        props.second,
        size,
        relativeDir
    )
}

    private fun getPathFromUri(uri: Uri): String? {
        return try {
            if ("com.android.externalstorage.documents" == uri.authority) {
                val docId = DocumentsContract.getTreeDocumentId(uri)
                val split = docId.split(":")
                if (split.size >= 2) {
                    val type = split[0]
                    val path = split[1]
                    if ("primary".equals(type, ignoreCase = true)) {
                        "/storage/emulated/0/$path"
                    } else {
                        "/storage/$type/$path"
                    }
                } else null
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "getPathFromUri failed", e)
            null
        }
    }


    private fun showSortMenu() {
        val popup = androidx.appcompat.widget.PopupMenu(this, btnSort)
        val menu = popup.menu

        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val currentMode = prefs.getString("sort_mode", "TITLE") ?: "TITLE"
        val isAsc = prefs.getBoolean("sort_is_asc", true)
        
        // Define base titles
        val titles = mapOf(
            "TITLE" to "Sort by Name",
            "ARTIST" to "Sort by Artist",
            "DATE" to "Sort by Date Added",
            "DURATION" to "Sort by Duration",
            "SIZE" to "Sort by Size (High Quality)"
        )
        
        // Helper to get display title with arrow
        fun getTitle(mode: String): String {
            val base = titles[mode] ?: ""
            return if (mode == currentMode) {
                if (isAsc) "$base ▲" else "$base ▼"
            } else {
                base
            }
        }

        menu.add(0, 101, 1, getTitle("TITLE"))
        menu.add(0, 102, 2, getTitle("ARTIST"))
        menu.add(0, 103, 3, getTitle("DATE"))
        menu.add(0, 104, 4, getTitle("DURATION"))
        menu.add(0, 105, 5, getTitle("SIZE"))
        
        popup.setOnMenuItemClickListener { item ->
            val selectedMode = when (item.itemId) {
                101 -> "TITLE"
                102 -> "ARTIST"
                103 -> "DATE"
                104 -> "DURATION"
                105 -> "SIZE"
                else -> "TITLE"
            }
            
            // Toggle logic: If clicking same mode, flip order. Else default to Ascending.
            val newIsAsc = if (selectedMode == currentMode) !isAsc else true
            
            prefs.edit()
                .putString("sort_mode", selectedMode)
                .putBoolean("sort_is_asc", newIsAsc)
                .apply()
            
            val sortedList = applySort(allSongs)
            allSongs.clear()
            allSongs.addAll(sortedList)
            
            if (adapter.isFolderIndexing) {
                // For folder view, we might need adjustments, but let's try direct sort update first
                 // If structure changes (folders), re-load might be safer but potentially slower.
                 // Current implementation just updates list.
                // However, folder grouping logic in checkIntentAndLoad might need re-running if we want folders sorted too?
                // For now, update the flat list or re-run load. Re-running load is safer for folder indexing.
                 checkIntentAndLoad()
            } else {
                adapter.setData(allSongs)
                setupFastScroller()
            }
            true
        }
        popup.show()
    }

    private fun applySort(songs: List<Song>): List<Song> {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val mode = prefs.getString("sort_mode", "TITLE") ?: "TITLE"
        val isAsc = prefs.getBoolean("sort_is_asc", true)
        
        val sorted = when (mode) {
            "TITLE" -> songs.sortedBy { it.title.lowercase() }
            "ARTIST" -> songs.sortedBy { it.artist.lowercase() }
            "DATE" -> songs.sortedBy { it.dateAdded } // Base Ascending (Oldest First)
            "DURATION" -> songs.sortedBy { it.duration } // Base Ascending (Shortest First)
            "SIZE" -> songs.sortedBy { it.size } // Base Ascending (Smallest First)
            else -> songs.sortedBy { it.title.lowercase() }
        }
        
        return if (isAsc) sorted else sorted.reversed()
    }

    // Search logic removed


    private fun setupFastScroller() {
        val activeSections = adapter.getAvailableSections()
        if (activeSections.isEmpty()) {
            sideBarContainer.visibility = View.GONE
            return
        }
        sideBarContainer.visibility = View.VISIBLE
        
        sideBarContainer.removeAllViews()
        
        activeSections.forEach { char ->
            val tv = TextView(this).apply {
                text = char.toString()
                textSize = 9f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 
                    0, 
                    1f
                )
                setTextColor(android.graphics.Color.DKGRAY)
                setPadding(0, 2, 0, 2)
            }
            sideBarContainer.addView(tv)
        }

        sideBarContainer.setOnTouchListener { v, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN || 
                event.action == android.view.MotionEvent.ACTION_MOVE) {
                
                val y = event.y
                val height = v.height
                val index = ((y / height) * activeSections.size).toInt()
                
                if (index in activeSections.indices) {
                    val letter = activeSections[index]
                    val pos = adapter.getPositionForSection(letter)
                    if (pos != -1) {
                        (recyclerView.layoutManager as? LinearLayoutManager)
                            ?.scrollToPositionWithOffset(pos, 0)
                    }
                }
                true
            } else {
                false
            }
        }
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
