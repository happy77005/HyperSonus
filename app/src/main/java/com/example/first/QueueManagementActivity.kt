package com.example.first

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior

class QueueManagementActivity : AppCompatActivity() {

    private lateinit var rvQueue: RecyclerView
    private lateinit var adapter: QueueAdapter
    private lateinit var btnBack: ImageButton
    
    private var musicService: MusicService? = null
    private var isBound = false

    private var fullPlaylist: List<Song> = emptyList()
    private var startIndex: Int = 0

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as MusicService.MusicBinder
            val serviceInstance = binder.getService()
            musicService = serviceInstance
            isBound = true
            
            // Load current queue starting from current song
            fullPlaylist = serviceInstance.getPlaylist()
            startIndex = serviceInstance.getQueueIndex()
            
            if (startIndex in fullPlaylist.indices) {
                val subList = fullPlaylist.subList(startIndex, fullPlaylist.size)
                adapter.updateSongs(subList)
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            musicService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_queue_management)

        val container = findViewById<View>(R.id.bottomSheetContainer)
        val behavior = BottomSheetBehavior.from(container)
        
        // Configure BottomSheet
        behavior.peekHeight = (resources.displayMetrics.heightPixels * 0.6).toInt()
        behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        behavior.isHideable = true
        
        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                    finish()
                }
            }
            override fun onSlide(bottomSheet: View, slideOffset: Float) {}
        })

        rvQueue = findViewById(R.id.rvQueue)
        btnBack = findViewById(R.id.btnQueueBack)

        btnBack.setOnClickListener { finish() }

        rvQueue.layoutManager = LinearLayoutManager(this)
        adapter = QueueAdapter(mutableListOf())
        rvQueue.adapter = adapter

        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                adapter.onItemMove(viewHolder.adapterPosition, target.adapterPosition)
                
                // Update service after reorder, preserving the "history"
                val updatedSubList = adapter.getSongs()
                val newFullPlaylist = mutableListOf<Song>()
                
                // Add songs before current
                if (startIndex > 0) {
                    newFullPlaylist.addAll(fullPlaylist.subList(0, startIndex))
                }
                // Add reordered current+future songs
                newFullPlaylist.addAll(updatedSubList)
                
                musicService?.updatePlaylist(newFullPlaylist)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            }
        })

        itemTouchHelper.attachToRecyclerView(rvQueue)

        // Bind to service
        Intent(this, MusicService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}
