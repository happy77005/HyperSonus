package com.example.first

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import coil.load
import kotlin.math.abs

class PlayerActivity : AppCompatActivity() {

    private val TAG = "PlayerActivity"
    private var musicService: MusicService? = null
    private var isBound = false

    private lateinit var tvTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var tvHiResBadge: TextView
    private lateinit var ivAlbumArt: ImageView
    private lateinit var seekBar: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnPrev: ImageButton
    private lateinit var btnBack: ImageButton
    private lateinit var btnOpenQueue: ImageButton
    private lateinit var btnEqualizer: ImageButton
    private lateinit var btnSleepTimer: ImageButton
    private lateinit var tvPlaybackSpeed: TextView
    private lateinit var btnAudioInfo: TextView
    private lateinit var btnVolume: ImageButton
    private lateinit var gestureDetector: GestureDetector

    private val musicListener = object : MusicServiceListener {
        override fun onSongChanged(song: Song) {
            runOnUiThread {
                updateUI(song)
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
            // Refresh technical info if granted
            Log.d(TAG, "Bluetooth permission granted")
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val updateSeekBarRunnable = object : Runnable {
        override fun run() {
            musicService?.let {
                if (it.isPlaying()) {
                    val currentPos = it.getCurrentPosition()
                    seekBar.progress = currentPos
                    tvCurrentTime.text = formatTime(currentPos)
                }
            }
            handler.postDelayed(this, 1000)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as MusicService.MusicBinder
            val serviceInstance = binder.getService()
            musicService = serviceInstance
            isBound = true

            // Set up listener for automatic song changes
            serviceInstance.addListener(musicListener)

            // Sync with current service state
            serviceInstance.getCurrentSong()?.let { updateUI(it) }
            tvPlaybackSpeed.text = "${serviceInstance.getPlaybackSpeed()}x"
            btnPlayPause.setImageResource(if (serviceInstance.isPlaying()) 
                R.drawable.ic_pause_sharp else R.drawable.ic_play_sharp)
            
            // Start updating seekbar
            handler.post(updateSeekBarRunnable)
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            musicService = null
            handler.removeCallbacks(updateSeekBarRunnable)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player_v2)

        setupGestureDetector()

        tvTitle = findViewById(R.id.tvPlayerTitle)
        tvArtist = findViewById(R.id.tvPlayerArtist)
        tvHiResBadge = findViewById(R.id.tvHiResBadge)
        ivAlbumArt = findViewById(R.id.ivPlayerAlbumArt)
        seekBar = findViewById(R.id.playerSeekBar)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        btnPlayPause = findViewById(R.id.btnPlayerPlayPause)
        btnNext = findViewById(R.id.btnPlayerNext)
        btnPrev = findViewById(R.id.btnPlayerPrev)
        btnBack = findViewById(R.id.btnBack)
        btnOpenQueue = findViewById(R.id.btnOpenQueue)
        btnEqualizer = findViewById(R.id.btnEqualizer)
        btnSleepTimer = findViewById(R.id.btnSleepTimer)
        tvPlaybackSpeed = findViewById(R.id.tvPlaybackSpeed)
        btnAudioInfo = findViewById(R.id.btnAudioInfo)
        btnVolume = findViewById(R.id.btnVolume)

        btnBack.setOnClickListener { finish() }
        
        btnOpenQueue.setOnClickListener {
            val intent = Intent(this, QueueManagementActivity::class.java)
            startActivity(intent)
        }

        btnEqualizer.setOnClickListener {
            val intent = Intent(this, EqualizerActivity::class.java)
            startActivity(intent)
        }

        btnSleepTimer.setOnClickListener {
            showSleepTimerMenu()
        }

        tvPlaybackSpeed.setOnClickListener {
            showSpeedMenu()
        }

        btnAudioInfo.setOnClickListener {
            val intent = Intent(this, AudioPathwayActivity::class.java)
            startActivity(intent)
        }

        btnVolume.setOnClickListener {
            showVolumeDialog()
        }

        btnPlayPause.setOnClickListener {
            musicService?.let {
                if (it.isPlaying()) {
                    it.pauseSong()
                } else {
                    it.resumeSong()
                }
            }
        }

        btnNext.setOnClickListener {
            musicService?.playNext()
        }

        btnPrev.setOnClickListener {
            musicService?.playPrevious()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvCurrentTime.text = formatTime(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                handler.removeCallbacks(updateSeekBarRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let {
                    musicService?.seekTo(it.progress)
                }
                handler.post(updateSeekBarRunnable)
            }
        })

        // Bind to service
        Intent(this, MusicService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
        
        // Attach gesture detector to root view
        findViewById<android.view.View>(android.R.id.content).setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 100
            private val SWIPE_VELOCITY_THRESHOLD = 100

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                if (abs(diffX) > abs(diffY)) {
                    if (abs(diffX) > SWIPE_THRESHOLD && abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                        if (diffX > 0) {
                            Log.d(TAG, "Swipe Right -> Play Previous")
                            musicService?.playPrevious()
                        } else {
                            Log.d(TAG, "Swipe Left -> Play Next")
                            musicService?.playNext()
                        }
                        return true
                    }
                }
                return false
            }

            override fun onDown(e: MotionEvent): Boolean = true
        })
    }
    private fun updateUI(song: Song) {
        tvTitle.text = song.title
        tvArtist.text = song.artist
        
        // Hi-Res/Audio Quality Detection
        val sampleRate = song.sampleRate ?: 0
        val bitrate = song.bitrate ?: 0
        
        if (sampleRate >= 48000 || bitrate > 320000) {
            tvHiResBadge.visibility = android.view.View.VISIBLE
            tvHiResBadge.text = if (sampleRate >= 48000) "HI-RES ${sampleRate/1000}kHz" else "HQ ${bitrate/1000}kbps"
        } else {
            tvHiResBadge.visibility = android.view.View.GONE
        }
        
        // Load album art
        val art = MetadataUtils.getAlbumArt(this, song.uri)
        ivAlbumArt.load(art) {
            crossfade(true)
            placeholder(android.R.drawable.ic_menu_report_image)
            error(android.R.drawable.ic_menu_report_image)
        }
        
        btnPlayPause.setImageResource(if (musicService?.isPlaying() == true) 
            R.drawable.ic_pause_sharp else R.drawable.ic_play_sharp)
        
        val duration = musicService?.getDuration() ?: 0
        seekBar.max = duration
        tvTotalTime.text = formatTime(duration)
        
        val currentPos = musicService?.getCurrentPosition() ?: 0
        seekBar.progress = currentPos
        tvCurrentTime.text = formatTime(currentPos)
        
        tvPlaybackSpeed.text = "${musicService?.getPlaybackSpeed() ?: 1.0f}x"
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


    private fun showVolumeDialog() {
        val service = musicService ?: return

        val linearSlider = SeekBar(this).apply {
            this.max = 100
            this.progress = 100  // Start at max
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(60, 40, 60, 40)
        }

        val container = android.widget.FrameLayout(this)
        container.addView(linearSlider)

        linearSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sbox: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val linear = progress.toFloat() / 100f
                    service.setUsbDirectVolume(linear)
                }
            }
            override fun onStartTrackingTouch(p0: SeekBar?) {}
            override fun onStopTrackingTouch(p0: SeekBar?) {}
        })

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("USB DAC Volume")
            .setView(container)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun showSpeedMenu() {
        val popup = androidx.appcompat.widget.PopupMenu(this, tvPlaybackSpeed)
        val menu = popup.menu
        
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f)
        speeds.forEachIndexed { index, speed ->
            menu.add(0, index, index, "${speed}x")
        }
        
        popup.setOnMenuItemClickListener { item ->
            val selectedSpeed = speeds[item.itemId]
            musicService?.setPlaybackSpeed(selectedSpeed)
            tvPlaybackSpeed.text = "${selectedSpeed}x"
            true
        }
        popup.show()
    }

    private fun showEqPresetMenu() {
        val popup = androidx.appcompat.widget.PopupMenu(this, btnEqualizer)
        val menu = popup.menu
        
        val presets = listOf("Bass", "Treble", "Double Bass", "Vocals", "Instrumentals")
        presets.forEachIndexed { index, preset ->
            menu.add(0, index, index, preset)
        }
        
        menu.add(1, 100, 100, "Manual Settings...")

        popup.setOnMenuItemClickListener { item ->
            if (item.groupId == 1) {
                val intent = Intent(this, EqualizerActivity::class.java)
                startActivity(intent)
            } else {
                val presetName = presets[item.itemId]
                musicService?.applyEqPreset(presetName)
                android.widget.Toast.makeText(this, "Preset Applied: $presetName", android.widget.Toast.LENGTH_SHORT).show()
            }
            true
        }
        popup.show()
    }

    private fun showSleepTimerMenu() {
        val service = musicService ?: return
        val popup = androidx.appcompat.widget.PopupMenu(this, btnSleepTimer)
        val menu = popup.menu

        val intervals = listOf(5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60)
        intervals.forEach { min ->
            menu.add(0, min, 0, "$min minutes")
        }

        if (service.isSleepTimerActive()) {
            menu.add(0, -1, 100, "Turn off timer").apply {
                // Could make it red or bold
            }
        }

        popup.setOnMenuItemClickListener { item ->
            if (item.itemId == -1) {
                service.cancelSleepTimer()
                android.widget.Toast.makeText(this, "Sleep timer cancelled", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                service.startSleepTimer(item.itemId)
            }
            true
        }
        popup.show()
    }

    private fun formatTime(ms: Int): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            musicService?.removeListener(musicListener)
            unbindService(connection)
            isBound = false
        }
        handler.removeCallbacks(updateSeekBarRunnable)
    }
}
