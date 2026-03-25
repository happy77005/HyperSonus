package com.example.first

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.example.first.engine.AudioEngineFactory
import com.example.first.engine.IAudioEngine
import com.example.first.engine.NativeHiResEngine
import com.example.first.engine.MediaPlayerEngine
import android.os.Binder
import android.os.Build
import android.os.CountDownTimer
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle

interface MusicServiceListener {
    fun onSongChanged(song: Song)
    fun onPlaybackStateChanged(isPlaying: Boolean)
    fun onPermissionRequired(permission: String)
}

class MusicService : Service(), UsbHelper.UsbListener {

    private val TAG = "MusicService"
    private var currentEngineType: AudioEngineFactory.EngineType = AudioEngineFactory.EngineType.NORMAL
    // Stores the engine that was active before USB override, so we can restore on detach
    private var lastNonUsbEngineType: AudioEngineFactory.EngineType = AudioEngineFactory.EngineType.HI_RES
    private var pendingUsbDevice: UsbDevice? = null
    private var pendingUsbConnection: UsbDeviceConnection? = null
    private var activeUsbDevice: UsbDevice? = null
    private var activeUsbConnection: UsbDeviceConnection? = null
    private var isCurrentlyBitPerfect: Boolean = false
    
    // V9: Unified Source of Truth for Playback State
    private var targetPlaybackState: Boolean = false
    
    // Once-only flag: discover already-connected USB devices on first onStartCommand.
    // We cannot call discoverDevices() in onCreate() (races with init), so we do it
    // on the first command — by then the service is fully initialized.
    private var hasDiscoveredDevices = false
    private lateinit var usbHelper: UsbHelper
    private var audioEngine: IAudioEngine? = null
    private var nextAudioEngine: IAudioEngine? = null
    private var nextSong: Song? = null
    private var equalizer: android.media.audiofx.Equalizer? = null
    private val binder = MusicBinder()
    
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        if (key == "pref_bit_perfect" || key == "pref_exclusive_mode") {
             if (currentEngineType == com.example.first.engine.AudioEngineFactory.EngineType.HI_RES && currentSong != null) {
                 Log.d(TAG, "Native tuning pref changed ($key), reloading engine...")
                 val pos = audioEngine?.getCurrentPosition() ?: 0
                 // Reload current song with same position
                 playSong(currentSong!!, pos)
             }
        }
    }
    
    private val listeners = mutableListOf<MusicServiceListener>()
 
    private var playlist: List<Song> = emptyList()
    private var queue: MutableList<Song> = mutableListOf()
    private var currentIndex: Int = -1
    private var currentSong: Song? = null
    private var playbackSpeed: Float = 1.0f

    // Sleep Timer
    private var sleepTimer: CountDownTimer? = null

    // Audio Focus
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false
 
    // MediaSession & Notifications
    private lateinit var mediaSession: MediaSessionCompat
    private val CHANNEL_ID = "music_channel"
    private val ERROR_CHANNEL_ID = "error_channel"
    private val NOTIFICATION_ID = 1
    private val ERR_NOTIFICATION_ID = 2
 
    private var wakeLock: PowerManager.WakeLock? = null
    private var heartbeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            val wlStatus = if (wakeLock?.isHeld == true) "ACQUIRED" else "RELEASED"
            Log.d(TAG, "Heartbeat: Service is alive (WL: $wlStatus, Engine: $currentEngineType, Playing: ${isPlaying()})")
            heartbeatHandler.postDelayed(this, 5000) // Increased frequency for debugging
        }
    }
 
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> Log.d(TAG, "Device Event: SCREEN_OFF")
                Intent.ACTION_SCREEN_ON -> Log.d(TAG, "Device Event: SCREEN_ON")
            }
        }
    }
    private var isFallbackActive = false

    companion object {
        const val ACTION_PLAY = "com.example.first.ACTION_PLAY"
        const val ACTION_PAUSE = "com.example.first.ACTION_PAUSE"
        const val ACTION_PREV = "com.example.first.ACTION_PREV"
        const val ACTION_NEXT = "com.example.first.ACTION_NEXT"
        const val ACTION_STOP = "com.example.first.ACTION_STOP"
        const val ACTION_COPY_ERROR = "com.example.first.ACTION_COPY_ERROR"
        const val ACTION_SET_BS2B = "com.example.first.ACTION_SET_BS2B"
        const val ACTION_SET_BS2B_LEVEL = "com.example.first.ACTION_SET_BS2B_LEVEL"
        
        internal var sessionToken: MediaSessionCompat.Token? = null
        fun getSessionToken(): MediaSessionCompat.Token? = sessionToken
    }

    private val volumeObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            syncUsbVolume()
        }
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnFocusGain) {
                    resumeSong()
                    resumeOnFocusGain = false
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                pauseSong()
                resumeOnFocusGain = false // Permanent loss
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (isPlaying()) {
                    pauseSong(abandonFocus = false) // Keep focus request to receive GAIN
                    resumeOnFocusGain = true
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Ignore notifications (Uninterrupted Music)
            }
        }
    }

    private val noisyReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent?.action) {
                // Check if user enabled "Pause on Disconnect"
                val prefs = getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                val shouldPause = prefs.getBoolean("pause_on_disconnect", true)
                
                if (shouldPause && isPlaying()) {
                    android.util.Log.d(TAG, "Audio becoming noisy and setting is ON, pausing playback")
                    pauseSong()
                }
            }
        }
    }

    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.example.first.ACTION_SET_PREAMP" -> {
                    val db = intent.getFloatExtra("db", 0f)
                    Log.d(TAG, "[HyperHiRes] Pre-Amp Broadcast RECEIVED: ${db}dB")
                    setPreAmp(db)
                }
                "com.example.first.ACTION_SET_RESONANCE" -> {
                    val enabled = intent.getBooleanExtra("enabled", false)
                    Log.d(TAG, "[HyperHiRes] Resonance Broadcast RECEIVED: enabled=$enabled")
                    setResonanceEnabled(enabled)
                }
                "com.example.first.ACTION_SET_SOXR" -> {
                    val enabled = intent.getBooleanExtra("enabled", true)
                    Log.d(TAG, "[HyperHiRes] Soxr Broadcast RECEIVED: enabled=$enabled")
                    audioEngine?.setResampleEngine(enabled)
                }
                "com.example.first.ACTION_SET_BS2B" -> {
                    val enabled = intent.getBooleanExtra("enabled", false)
                    Log.d(TAG, "[HyperHiRes] BS2B Broadcast RECEIVED: enabled=$enabled")
                    (audioEngine as? com.example.first.engine.NativeHiResEngine)?.updateBs2bState()
                }
                "com.example.first.ACTION_SET_BS2B_LEVEL" -> {
                    val level = intent.getIntExtra("level", 0)
                    Log.d(TAG, "[HyperHiRes] BS2B Level Broadcast RECEIVED: 0x${Integer.toHexString(level)}")
                    (audioEngine as? com.example.first.engine.NativeHiResEngine)?.setDspParameter(
                        com.example.first.engine.NativeHiResEngine.DSP_PARAM_BS2B_LEVEL,
                        level.toFloat()
                    )
                }
            }
        }
    }

    private val audioDeviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>?) {
            Log.d(TAG, "Audio devices added, probing capabilities...")
            probeActiveDevice()
            
            val deviceClass = com.example.first.engine.DeviceHelper.getCurrentDeviceClass(this@MusicService)
            val newEngine = com.example.first.engine.AudioEngineFactory.getEngineForClassification(deviceClass)
            
            // USB handled by UsbHelper callbacks
            if (newEngine != currentEngineType && newEngine != com.example.first.engine.AudioEngineFactory.EngineType.USB_DAC) {
                Log.d(TAG, "Device Add: Auto-routing to $newEngine for class ${deviceClass.id}")
                switchToEngine(newEngine)
                
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(this@MusicService, "Auto-Routing: Device Class ${deviceClass.id}", Toast.LENGTH_SHORT).show()
                }
            } else if (currentEngineType == com.example.first.engine.AudioEngineFactory.EngineType.HI_RES && newEngine == currentEngineType) {
                val dacInfo = com.example.first.engine.DacHelper.getCurrentDacInfo(this@MusicService)
                Log.d(TAG, "Routing Native Engine within HI_RES to device: ${dacInfo.name}")
                audioEngine?.setDeviceId(dacInfo.id)
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>?) {
            Log.d(TAG, "Audio device removed")
            
            val deviceClass = com.example.first.engine.DeviceHelper.getCurrentDeviceClass(this@MusicService)
            val newEngine = com.example.first.engine.AudioEngineFactory.getEngineForClassification(deviceClass)
            
            // USB detach handled by onUsbDeviceDetached
            if (newEngine != currentEngineType && currentEngineType != com.example.first.engine.AudioEngineFactory.EngineType.USB_DAC) {
                Log.d(TAG, "Device Remove: Auto-routing to $newEngine for class ${deviceClass.id}")
                
                // V11: Capture Position BEFORE any state changes or pauses
                val currentPos = getCurrentPosition()
                Log.d(TAG, "onAudioDevicesRemoved: Atomic capture of position: $currentPos")

                val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val shouldPause = prefs.getBoolean("pause_on_disconnect", true)
                
                // V9: Update Source of Truth first
                if (shouldPause) {
                    targetPlaybackState = false
                    Log.d(TAG, "onAudioDevicesRemoved: Pause on Disconnect is ON, target state set to FALSE")
                } else {
                    Log.d(TAG, "onAudioDevicesRemoved: Pause on Disconnect is OFF, preserving target state: $targetPlaybackState")
                }

                // V8: Pause IMMEDIATELY for absolute silence
                Log.d(TAG, "onAudioDevicesRemoved: Pausing current engine before shift")
                audioEngine?.pause()
                
                switchToEngine(newType = newEngine, isDisconnect = true, overridePos = currentPos)
                
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(this@MusicService, "Auto-Routing back to Device Class ${deviceClass.id}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun probeActiveDevice() {
        val dacInfo = com.example.first.engine.DacHelper.getCurrentDacInfo(this)
        if (dacInfo.type == com.example.first.engine.DacHelper.DacType.UNKNOWN) return

        // If Bluetooth is connected, check for permission to show Hi-Res info
        if (dacInfo.type == com.example.first.engine.DacHelper.DacType.BLUETOOTH) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Bluetooth permission missing, notifying listeners")
                    notifyPermissionRequired(android.Manifest.permission.BLUETOOTH_CONNECT)
                }
            }
        }

        // Probing can take a few seconds, do it in a background thread
        Thread {
            try {
                // Ensure native engine is initialized
                if (audioEngine == null) {
                    audioEngine = com.example.first.engine.AudioEngineFactory.createEngine(this, com.example.first.engine.AudioEngineFactory.EngineType.HI_RES)
                }
                
                Log.d(TAG, "Probing sample rates for device: ${dacInfo.name} (ID: ${dacInfo.id})")
                val supportedRates = audioEngine?.probeSampleRates(dacInfo.id) ?: intArrayOf()
                Log.d(TAG, "Probed rates: ${supportedRates.joinToString(", ")}")
                
                com.example.first.engine.DacHelper.setProbedRates(dacInfo.id, supportedRates)
            } catch (e: Exception) {
                Log.e(TAG, "Probing failed: ${e.message}")
            }
        }.start()
    }

    private fun notifyPermissionRequired(permission: String) {
        listeners.forEach { it.onPermissionRequired(permission) }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")

        // 🛡️ Global crash reporting to bypass logcat filters
        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "CRITICAL: FATAL EXCEPTION in thread ${thread.name}: ${throwable.message}", throwable)
            oldHandler?.uncaughtException(thread, throwable)
        }
        
        // Initialize WakeLock
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HyperAudio::PlaybackWakeLock")
            wakeLock?.setReferenceCounted(false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create WakeLock", e)
        }

        com.example.first.engine.DacHelper.initBluetoothListener(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        mediaSession = MediaSessionCompat(this, "MusicService").apply {
            setCallback(mediaSessionCallback)
            isActive = true
            MusicService.sessionToken = this.sessionToken
        }

        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        playbackSpeed = prefs.getFloat("playback_speed", 1.0f)

        val deviceClass = com.example.first.engine.DeviceHelper.getCurrentDeviceClass(this)
        currentEngineType = AudioEngineFactory.getEngineForClassification(deviceClass)
        lastNonUsbEngineType = if (currentEngineType != AudioEngineFactory.EngineType.USB_DAC)
                                   currentEngineType else AudioEngineFactory.EngineType.HI_RES
        audioEngine = AudioEngineFactory.createEngine(this, currentEngineType)

        val preAmpProgress = prefs.getInt("pref_preamp_progress", 220)
        setPreAmp((preAmpProgress - 200) / 10f)

        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        val filter = IntentFilter().apply {
            addAction("com.example.first.ACTION_SET_PREAMP")
            addAction("com.example.first.ACTION_SET_RESONANCE")
            addAction("com.example.first.ACTION_SET_SOXR")
            addAction("com.example.first.ACTION_SET_BS2B")
            addAction("com.example.first.ACTION_SET_BS2B_LEVEL")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(settingsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(settingsReceiver, filter)
        }

        val resonanceEnabled = prefs.getBoolean("pref_resonance_enabled", false)
        setResonanceEnabled(resonanceEnabled)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        }

        // USB helper — manages permission request and attach/detach callbacks
        usbHelper = UsbHelper(this, this)
        usbHelper.register()
        // NOTE: do NOT call discoverDevices() here.
        // USB probing must be event-driven (onStartCommand), not lifecycle-driven.
        // Probing in onCreate() races with service initialization and crashes on
        // devices where the DAC is already connected when the service starts.

        probeActiveDevice()
        
        // Volume sync for USB Bypass
        contentResolver.registerContentObserver(
            android.provider.Settings.System.CONTENT_URI,
            true, volumeObserver
        )

        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, screenFilter)

        heartbeatHandler.post(heartbeatRunnable)
    }
 
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        // On the very first command (any intent), discover already-connected USB devices.
        // Skip if this command IS a USB attach — that device is probed explicitly below.
        // hasDiscoveredDevices prevents redundant re-scanning on every play/pause/next.
        if (!hasDiscoveredDevices && action != UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            hasDiscoveredDevices = true
            Log.d(TAG, "[USB] First start command — scanning for already-connected USB devices")
            usbHelper.discoverDevices()
        }

        when (action) {
            null -> { /* cold OS restart, discover already handled above */ }
            ACTION_PLAY  -> resumeSong()
            ACTION_PAUSE -> pauseSong()
            ACTION_NEXT  -> playNext()
            ACTION_PREV  -> playPrevious()
            ACTION_STOP  -> stopService()
            // USB attach forwarded by UsbAttachReceiver
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                hasDiscoveredDevices = true  // explicit attach — no need for full scan
                val device: UsbDevice? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                device?.let { usbHelper.probeDevice(it) }
            }
            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                val device: UsbDevice? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                device?.let { onUsbDeviceDetached(it) }
            }
        }
        // START_STICKY: ensures the service is restarted if it's ever killed by the OS.
        // Critical for background stability on aggressive power managers.
        return START_STICKY
    }

    // ── UsbHelper.UsbListener ─────────────────────────────────────────────────

    override fun onUsbAccessGranted(device: UsbDevice, connection: UsbDeviceConnection) {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("pref_usb_dac_bypass", true)) {
            // Bypass is OFF — user chose not to route DAC through this engine
            Log.d(TAG, "[USB] Bypass is OFF — not taking DAC ownership")
            connection.close()
            return
        }

        Log.d(TAG, "[USB] Access granted: ${device.productName}")

        checkBatteryOptimization()

        // Remember which engine was active before the USB override
        if (currentEngineType != AudioEngineFactory.EngineType.USB_DAC) {
            lastNonUsbEngineType = currentEngineType
        }

        if (currentSong == null) {
            // No song is playing yet — defer engine creation until playSong() is called.
            // Store device+connection so playSong() picks them up immediately.
            Log.d(TAG, "[USB] No active song — deferring USB engine swap to next playSong()")
            pendingUsbConnection?.close()   // close any stale pending
            pendingUsbDevice     = device
            pendingUsbConnection = connection
            activeUsbConnection  = connection   // track for lifecycle close
            activeUsbDevice      = device       // MUST set this so playSong knows it's active!
            currentEngineType    = AudioEngineFactory.EngineType.USB_DAC
            Toast.makeText(this, "USB DAC: ${device.productName} — ready, press play",
                           Toast.LENGTH_SHORT).show()
            return
        }

        // Safe engine swap: pause → create USB engine → seekTo → resume
        val wasPlaying = isPlaying()
        val position   = getCurrentPosition()
        audioEngine?.pause()

        // Close any stale USB connection
        activeUsbConnection?.close()
        activeUsbConnection = connection
        activeUsbDevice = device

        val usbEngine = AudioEngineFactory.createUsbEngine(this, device, connection)
        
        // SET LISTENERS BEFORE setDataSource!
        usbEngine.setOnPreparedListener {
            usbEngine.seekTo(position)
            if (wasPlaying) usbEngine.play()
            showNotification("USB Direct")
            notifyPlaybackStateChanged(wasPlaying)
            
            // Critical: sync volume on start
            syncUsbVolume()
        }
        usbEngine.setOnCompletionListener { playNext() }
        usbEngine.setOnErrorListener { what, extra ->
            Log.e(TAG, "[USB] Engine error: $what/$extra")
            if (what == -3) {
                // Persistent failure — fallback to standard Hi-Res
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                     Toast.makeText(this, "USB Direct failed — falling back to standard Hi-Res", 
                                    Toast.LENGTH_LONG).show()
                     onUsbDeviceDetached(device) // Reuse detach logic to restore engine
                }
            }
        }

        usbEngine.setDataSource(this, currentSong!!.uri)

        audioEngine?.release()
        audioEngine = usbEngine
        currentEngineType = AudioEngineFactory.EngineType.USB_DAC
        Log.d(TAG, "[USB] Engine swapped to USB_DAC (will restore $lastNonUsbEngineType on detach)")

        Toast.makeText(this, "USB DAC: ${device.productName} — Direct mode",
                       Toast.LENGTH_SHORT).show()
    }

    override fun onUsbPermissionDenied(device: UsbDevice) {
        Log.w(TAG, "[USB] Permission denied: ${device.productName} — keeping current engine")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(this, "USB permission denied — using current audio engine",
                           Toast.LENGTH_SHORT).show()
        }
        // No engine change. Playback continues uninterrupted.
    }

    override fun onUsbDeviceDetached(device: UsbDevice) {
        if (device.vendorId == 10610 && device.productId == 258) { 
            // Optional: Special logging for JA11 if needed, but otherwise use generic check
        }
        
        val isActive = device.deviceId == activeUsbDevice?.deviceId
        val isPending = device.deviceId == pendingUsbDevice?.deviceId

        if (!isActive && !isPending) {
            // Only log if it's NOT the active device to avoid "Unrelated" spam for the primary DAC
            return
        }

        // Clear any pending deferred USB swap
        if (isPending) {
            Log.d(TAG, "[USB] Pending DAC detached: ${device.productName}")
            pendingUsbDevice = null
            pendingUsbConnection?.close()
            pendingUsbConnection = null
        }

        if (!isActive || currentEngineType != AudioEngineFactory.EngineType.USB_DAC) return
        // V11: Atomic Position Capture FIRST
        val position = getCurrentPosition()
        Log.d(TAG, "[USB] Atomic capture of position before DAC release: $position")

        // V11/V9: Update Source of Truth. 
        // Force Pause on USB DAC Pullout (V11 requirement: always pause on DAC disconnect)
        targetPlaybackState = false
        Log.d(TAG, "[USB] Mandatory Pause applied for DAC disconnect")
        
        // V8: Pause IMMEDIATELY for absolute silence
        Log.d(TAG, "onUsbDeviceDetached: Pausing current engine before release")
        audioEngine?.pause()
        
        Log.d(TAG, "[USB] Active DAC detached: ${device.productName} — pos captured=$position")

        audioEngine?.release()
        activeUsbConnection?.close()
        activeUsbConnection = null
        activeUsbDevice = null
        audioEngine = null

        // Restore previous engine
        currentEngineType = lastNonUsbEngineType
        val uri = currentSong?.uri
        if (uri == null) {
            // No song was playing — just reset engine type, nothing else to restore
            audioEngine = AudioEngineFactory.createEngine(this, currentEngineType)
            Toast.makeText(this, "USB DAC removed", Toast.LENGTH_SHORT).show()
            return
        }
        val restoredEngine = AudioEngineFactory.createEngine(this, currentEngineType)
        restoredEngine.setDataSource(this, uri)
        restoredEngine.setOnPreparedListener {
            restoredEngine.seekTo(position)
            
            // V9: Sync hardware to targetPlaybackState
            if (targetPlaybackState) {
                Log.d(TAG, "onUsbDeviceDetached: Resuming as per targetPlaybackState")
                restoredEngine.play()
                notifyPlaybackStateChanged(true)
            } else {
                Log.d(TAG, "onUsbDeviceDetached: Remaining paused as per targetPlaybackState")
                restoredEngine.pause()
                notifyPlaybackStateChanged(false)
            }
            showNotification()
        }
        restoredEngine.setOnCompletionListener { playNext() }
        audioEngine = restoredEngine

        Toast.makeText(this, "USB DAC removed — back to ${lastNonUsbEngineType.name}",
                       Toast.LENGTH_SHORT).show()
    }
 
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Music Playback"
            val descriptionText = "Controls for music playback"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val errName = "Engine Errors"
                val errDesc = "Notifications for audio engine errors"
                val errImportance = NotificationManager.IMPORTANCE_HIGH
                val errChannel = NotificationChannel(ERROR_CHANNEL_ID, errName, errImportance).apply {
                    description = errDesc
                }
                notificationManager.createNotificationChannel(errChannel)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val battName = "Optimization Warnings"
                val battDesc = "Warnings about battery settings affecting playback"
                val battImportance = NotificationManager.IMPORTANCE_HIGH
                val battChannel = NotificationChannel("batt_channel", battName, battImportance).apply {
                    description = battDesc
                }
                val notificationManager: NotificationManager =
                    getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(battChannel)
            }
        }
    }
 
    private var currentStatus: String? = null

    private fun showNotification(status: String? = null) {
        if (status != null) currentStatus = status
        
        val song = currentSong ?: return
        val isPlaying = isPlaying()
 
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
 
        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause, "Pause",
                getServicePendingIntent(ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play, "Play",
                getServicePendingIntent(ACTION_PLAY)
            )
        }
 
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setSubText(currentStatus)
            .setContentIntent(pendingIntent)
            .setOngoing(isPlaying)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
            .addAction(android.R.drawable.ic_media_previous, "Previous", getServicePendingIntent(ACTION_PREV))
            .addAction(playPauseAction)
            .addAction(android.R.drawable.ic_media_next, "Next", getServicePendingIntent(ACTION_NEXT))
            .build()
 
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
 
    private fun getServicePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MusicNotificationReceiver::class.java).apply {
            this.action = action
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(this, 0, intent, flags)
    }

    private fun showErrorNotification(error: String) {
        val copyIntent = Intent(this, MusicNotificationReceiver::class.java).apply {
            action = ACTION_COPY_ERROR
            putExtra("error_text", error)
        }
        val copyPendingIntent = PendingIntent.getBroadcast(
            this, 1, copyIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val notification = NotificationCompat.Builder(this, ERROR_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Audio Engine Error")
            .setContentText(error)
            .setStyle(NotificationCompat.BigTextStyle().bigText(error))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_edit, "Copy Details", copyPendingIntent)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ERR_NOTIFICATION_ID, notification)
    }

    private fun checkBatteryOptimization() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                Log.w(TAG, "[BATT] Optimization is ACTIVE - prompting user")
                showBatteryOptimizationNotification()
            }
        }
    }

    private fun showBatteryOptimizationNotification() {
        val intent = Intent(this, SettingsActivity::class.java).apply {
            putExtra("HIGHLIGHT_BATTERY", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 2, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "batt_channel")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Optimization Warning")
            .setContentText("Battery optimization is active. Playback might stop when screen is off.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "USB DAC connected. To prevent playback interruptions, please set Hyperplay to 'Unrestricted' in battery settings."
            ))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setColor(android.graphics.Color.RED)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_edit, "Fix Now", pendingIntent)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(3, notification)
    }

    private fun stopService() {
        pauseSong()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
        stopSelf()
    }

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    fun addListener(listener: MusicServiceListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            
            val result = audioManager.requestAudioFocus(focusRequest!!)
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    fun removeListener(listener: MusicServiceListener) {
        listeners.remove(listener)
    }

    private fun notifySongChanged(song: Song) {
        listeners.forEach { it.onSongChanged(song) }
    }

    private fun notifyPlaybackStateChanged(isPlaying: Boolean) {
        listeners.forEach { it.onPlaybackStateChanged(isPlaying) }
        updateMediaSessionState()
    }

    fun setPlaylist(songs: List<Song>, startIndex: Int) {
        this.playlist = songs
        this.currentIndex = startIndex
        if (startIndex >= 0 && startIndex < songs.size) {
            playSong(songs[startIndex])
        }
    }

    fun addToQueue(song: Song) {
        Log.d(TAG, "Adding song to queue: ${song.title}")
        queue.add(song)
    }

    private fun switchToEngine(newType: com.example.first.engine.AudioEngineFactory.EngineType, isDisconnect: Boolean = false, overridePos: Int = -1) {
        Log.d(TAG, "Switching engine to $newType (isDisconnect=$isDisconnect)")
        
        // V11: Use override position if provided (important for Oboe disconnects)
        val currentPos = if (overridePos >= 0) overridePos else getCurrentPosition()
        Log.d(TAG, "switchToEngine: Final capture of position before release: $currentPos (wasPlaying=$targetPlaybackState)")
        
        audioEngine?.release()
        nextAudioEngine?.release()
        nextAudioEngine = null
        nextSong = null
        
        currentEngineType = newType
        audioEngine = com.example.first.engine.AudioEngineFactory.createEngine(this, newType)
        
        currentSong?.let { song ->
            // Re-prepare the new engine with the current song
            if (audioEngine?.setDataSource(this, song.uri) == true) {
                audioEngine?.setOnPreparedListener {
                    audioEngine?.seekTo(currentPos)
                    
                    // V11: Small 150ms delay for Speakers to allow OS AudioServer to stabilize Routing Class 0
                    val routingDelay = if (currentEngineType == com.example.first.engine.AudioEngineFactory.EngineType.NORMAL) 150L else 0L
                    
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        // V9: Unified sync
                        if (targetPlaybackState) {
                            Log.d(TAG, "switchToEngine: Hardware Play (target=TRUE)")
                            audioEngine?.play()
                        } else {
                            Log.d(TAG, "switchToEngine: Hardware Pause (target=FALSE)")
                            audioEngine?.pause()
                        }
                        notifyPlaybackStateChanged(targetPlaybackState)
                    }, routingDelay)

                    initEqualizer(audioEngine?.getAudioSessionId() ?: 0)
                    applyPlaybackSpeed()
                    updateMediaSessionState()
                }
                audioEngine?.setOnCompletionListener {
                    if (nextAudioEngine != null) handleGaplessTransition() else playNext()
                }
                audioEngine?.setOnErrorListener { what, extra ->
                    Log.e(TAG, "Engine error during switch: $what, $extra")
                }
            }
        }
    }

    fun playSong(song: Song, startPosition: Int = 0) {
        Log.d(TAG, "Playing song: ${song.title} from pos: $startPosition")
        acquireWakeLock()
        Log.i(TAG, "[HyperHiRes] Requesting playback via Engine: $currentEngineType")
        
        // Request focus IMMEDIATELY before even loading the song
        if (!requestAudioFocus()) {
            Log.e(TAG, "Could not acquire audio focus. Playback may not work correctly.")
        }

        this.currentSong = song
        
        // Update index if song is in playlist
        val index = playlist.indexOfFirst { it.id == song.id }
        if (index != -1) {
            currentIndex = index
        }

        try {
            // If USB permission was granted before a song was selected, pick up the deferred
            // device + connection now and swap currentEngineType to USB_DAC before creation.
            val pDev = pendingUsbDevice
            val pCon = pendingUsbConnection
            if (pDev != null && pCon != null) {
                Log.d(TAG, "[USB] Consuming deferred USB engine for ${pDev.productName}")
                pendingUsbDevice     = null
                pendingUsbConnection = null
                activeUsbDevice      = pDev
                activeUsbConnection  = pCon
                
                audioEngine?.release()
                val usbEngine = AudioEngineFactory.createUsbEngine(this, pDev, pCon)
                
                // SET LISTENERS BEFORE setDataSource!
                usbEngine.setOnPreparedListener {
                    if (requestAudioFocus()) {
                        Log.d(TAG, "[USB] Deferred engine prepared, starting playback")
                        if (startPosition > 0) usbEngine.seekTo(startPosition)
                        usbEngine.play()
                        initEqualizer(usbEngine.getAudioSessionId())
                        applyPlaybackSpeed()
                        notifySongChanged(song)
                        updateMediaSessionMetadata(song)
                        notifyPlaybackStateChanged(true)
                        showNotification("USB Direct")
                        prepareNextMediaPlayer()
                        Log.d(TAG, "[USB] Calling syncUsbVolume from deferred engine prepared listener")
                        syncUsbVolume()
                    }
                }
                usbEngine.setOnCompletionListener { playNext() }
                usbEngine.setOnErrorListener { what, extra ->
                    Log.e(TAG, "[USB] Deferred engine error: $what/$extra")
                }

                usbEngine.setDataSource(this, song.uri)

                audioEngine = usbEngine
                currentEngineType = AudioEngineFactory.EngineType.USB_DAC
                return
            }

            audioEngine?.release()

            // If we are in USB_DAC mode, we MUST use createUsbEngine if device is available.
            val dev = activeUsbDevice
            val con = activeUsbConnection
            if (currentEngineType == com.example.first.engine.AudioEngineFactory.EngineType.USB_DAC && dev != null && con != null) {
                Log.d(TAG, "[USB] Using USB Direct engine for playSong")
                audioEngine = com.example.first.engine.AudioEngineFactory.createUsbEngine(this, dev, con)
            } else {
                audioEngine = com.example.first.engine.AudioEngineFactory.createEngine(this, currentEngineType)
            }

            audioEngine?.apply {
                if (this is com.example.first.engine.NativeHiResEngine) {
                    this.setOnBitPerfectListener { isPerfect, rate ->
                        isCurrentlyBitPerfect = isPerfect
                        val status = "${rate}Hz ${if (isPerfect) "⚡" else ""}"
                        Log.d(TAG, "Bit-Perfect callback: $status")
                        showNotification(status)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                             Toast.makeText(applicationContext, "Playback: $status", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                // Report "PLAYING" state to MediaSession immediately! 
                // Don't wait for prepared as OS might suspend us in the meantime.
                updateMediaSessionState(overrideState = PlaybackStateCompat.STATE_PLAYING)

                // SET LISTENERS BEFORE setDataSource!
                setOnPreparedListener { 
                    Log.d(TAG, "[DEBUG] onPrepared triggered")
                    // Still check focus but don't fail if we already have it from the start of playSong
                    requestAudioFocus() 
                    
                    Log.d(TAG, "[DEBUG] Starting playback logic")
                        try {
                            initEqualizer(getAudioSessionId())
                            applyPlaybackSpeed()
                            if (startPosition > 0) seekTo(startPosition)
                            
                            // V9: Unified adherence
                            targetPlaybackState = true
                            Log.d(TAG, "[DEBUG] Calling engine.play() for target state TRUE")
                            play() 
                            
                            notifySongChanged(song)
                            updateMediaSessionMetadata(song)
                            notifyPlaybackStateChanged(true)
                            registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
                            
                            val display = if (currentEngineType == com.example.first.engine.AudioEngineFactory.EngineType.USB_DAC) "USB Direct" else "Hi-Res Audio"
                            showNotification(display)
                            
                            if (currentEngineType == com.example.first.engine.AudioEngineFactory.EngineType.USB_DAC) syncUsbVolume()
                            
                            prepareNextMediaPlayer()
                        Log.d(TAG, "[DEBUG] onPrepared logic complete")
                    } catch (e: Exception) {
                        Log.e(TAG, "[CRITICAL] Error in onPrepared logic: ${e.message}", e)
                    }
                }
                setOnCompletionListener { 
                    if (nextAudioEngine != null) handleGaplessTransition() else playNext()
                }
                setOnErrorListener { what, extra ->
                    Log.e(TAG, "Engine error during playSong: $what, $extra")
                    targetPlaybackState = false
                    notifyPlaybackStateChanged(false)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        val msg = "Playback Error: $what/$extra. Internal engine failure."
                        Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
                        showErrorNotification(msg)
                    }
                }

                setDataSource(this@MusicService, song.uri)
            }
            
            // Clear any pending next player
            nextAudioEngine?.release()
            nextAudioEngine = null
            nextSong = null
                        
        } catch (e: Exception) {
            Log.e(TAG, "Error starting playback: ${e.message}", e)
            Toast.makeText(applicationContext, "Playback error: ${e.message}", Toast.LENGTH_SHORT).show()
            notifyPlaybackStateChanged(false)
        }
    }

    fun playNext() {
        if (queue.isNotEmpty()) {
            val nextFromQueue = queue.removeAt(0)
            Log.d(TAG, "Playing next from queue: ${nextFromQueue.title}")
            playSong(nextFromQueue)
            return
        }

        if (playlist.isEmpty()) return
        currentIndex = (currentIndex + 1) % playlist.size
        playSong(playlist[currentIndex])
    }

    fun playPrevious() {
        if (playlist.isEmpty()) return
        currentIndex = if (currentIndex - 1 < 0) playlist.size - 1 else currentIndex - 1
        playSong(playlist[currentIndex])
    }

    fun pauseSong(abandonFocus: Boolean = true) {
        Log.d(TAG, "Pausing playback (V9 target=FALSE)")
        targetPlaybackState = false
        releaseWakeLock()
        audioEngine?.pause()
        notifyPlaybackStateChanged(false)
        showNotification()
        if (abandonFocus) {
            abandonAudioFocus()
        }
    }

    fun resumeSong() {
        Log.d(TAG, "Resuming playback (V9 target=TRUE)")
        targetPlaybackState = true
        if (requestAudioFocus()) {
            acquireWakeLock()
            audioEngine?.resume()
            notifyPlaybackStateChanged(true)
            updateMediaSessionState()
            showNotification()
        } else {
            Log.e(TAG, "Failed to regain audio focus for resume")
        }
    }

    fun isPlaying(): Boolean {
        // V9: Standard source of truth
        return targetPlaybackState
    }

    fun getDuration(): Int {
        return audioEngine?.getDuration() ?: 0
    }

    fun getCurrentPosition(): Int {
        return audioEngine?.getCurrentPosition() ?: 0
    }

    fun seekTo(pos: Int) {
        audioEngine?.seekTo(pos)
    }

    private fun initEqualizer(audioSessionId: Int) {
        // Session 0 is returned by native (Oboe) engines — Android system EQ can't attach to it.
        if (audioSessionId == 0) {
            Log.d(TAG, "Skipping system Equalizer — session 0 (native engine, expected)")
            return
        }
        try {
            equalizer?.release()
            equalizer = android.media.audiofx.Equalizer(0, audioSessionId).apply {
                enabled = true
                val prefs = getSharedPreferences("equalizer_prefs", Context.MODE_PRIVATE)
                for (i in 0 until numberOfBands) {
                    val level = prefs.getInt("band_$i", 0).toShort()
                    setBandLevel(i.toShort(), level)
                }
            }
            Log.d(TAG, "Equalizer initialized for session $audioSessionId")
        } catch (e: Exception) {
            Log.w(TAG, "Equalizer init failed (non-fatal) session=$audioSessionId: ${e.message}")
        }
    }

    fun getEqualizer(): android.media.audiofx.Equalizer? = equalizer

    fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed
        getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit().putFloat("playback_speed", speed).apply()
        
        applyPlaybackSpeed()
    }

    fun getPlaybackSpeed(): Float = playbackSpeed

    fun getAudioTechnicalInfo(): String {
        val engine = audioEngine ?: return "No engine active"
        val sr = engine.getActualSampleRate()
        val ch = engine.getActualChannelCount()
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val bitPerfectEnabled = prefs.getBoolean("pref_bit_perfect", false)
        
        // Bit-perfect is active if enabled AND we are using a hi-res capable engine
        val isDirectBypass = currentEngineType == AudioEngineFactory.EngineType.USB_DAC && engine is com.example.first.engine.UsbBulkEngine
        val isOboeHiRes = currentEngineType == AudioEngineFactory.EngineType.HI_RES || (currentEngineType == AudioEngineFactory.EngineType.USB_DAC && engine is NativeHiResEngine)
        
        val bitPerfectStatus = if (bitPerfectEnabled && (isDirectBypass || isCurrentlyBitPerfect)) "ON" else "OFF"
        
        val engineDisplay = when (currentEngineType) {
            AudioEngineFactory.EngineType.USB_DAC -> if (isDirectBypass) "USB Direct Bypass" else "Native Hi-Res (Oboe via USB)"
            AudioEngineFactory.EngineType.HI_RES -> "Native Hi-Res (Oboe via Bluetooth)"
            AudioEngineFactory.EngineType.NORMAL -> "Native Hi-Res (Oboe via Speakers)"
        }

        val backend = if (engine is NativeHiResEngine) engine.getAudioBackend() else if (isDirectBypass) "USB Direct (Bulk)" else "Standard Android"
        val resampler = engine.getResamplerName()
        val limiterStatus = if (isDirectBypass) "Bypassed (Bit-Perfect)" else "Active (Safety)"
        val ditherStatus = if (isDirectBypass) "Bypassed (Bit-Perfect)" else "Active (TPDF)"

        val decoderName = when {
            engine is NativeHiResEngine -> engine.getDecoderName()
            engine is com.example.first.engine.UsbBulkEngine -> engine.getDecoderName()
            else -> "High-Precision Native Decoder"
        }

        val dacOutput = if (engine is com.example.first.engine.UsbBulkEngine) {
            val dacRate = engine.getActualSampleRate()
            val dacDepth = engine.getDacBitDepth()
            val dacCh = engine.getActualChannelCount()
            "${dacRate}Hz / ${dacDepth}-bit / ${dacCh}ch"
        } else {
            "System Managed"
        }

        var info = "Engine: $engineDisplay\nBackend: $backend\nDecoder: $decoderName\nResampler: $resampler\nOutput Rate: ${if (sr > 0) "${sr}Hz" else "System Default"}\nChannels: ${if (ch > 0) ch else "System Default"}\nBit-Perfect: $bitPerfectStatus\nBit Depth: 32-bit Float\nDAC Output: $dacOutput\nLimiter: $limiterStatus\nDither: $ditherStatus"
        
        if (com.example.first.engine.DacHelper.getCurrentDacInfo(this).type == com.example.first.engine.DacHelper.DacType.BLUETOOTH) {
            val btInfo = com.example.first.engine.DacHelper.getBluetoothCodecInfo(this)
            if (btInfo != null) {
                info += "\nBluetooth: $btInfo"
            }
        }
        
        return info
    }

    private fun applyPlaybackSpeed() {
        audioEngine?.setPlaybackSpeed(playbackSpeed)
    }

    fun setPreAmp(gainDb: Float) {
        if (currentEngineType == com.example.first.engine.AudioEngineFactory.EngineType.HI_RES) {
            val engine = audioEngine
            if (engine is NativeHiResEngine) {
                engine.setDspParameter(NativeHiResEngine.DSP_PARAM_PREAMP, gainDb)
            }
        }
    }

    fun setBandLevel(band: Short, level: Short) {
        try {
            // V10: All engines are now Oboe-capable
            val db = level / 100f 
            (audioEngine as? com.example.first.engine.NativeHiResEngine)?.setEqBand(band.toInt(), db)
            
            // Also update System EQ if session is non-zero (unlikely in Oboe but kept for safety)
            if (audioEngine?.getAudioSessionId() != 0) {
                equalizer?.setBandLevel(band, level)
            }
            // Persist
            getSharedPreferences("equalizer_prefs", Context.MODE_PRIVATE)
                .edit().putInt("band_$band", level.toInt()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error setting band level", e)
        }
    }



    fun resetEqualizer() {
        Log.d(TAG, "Resetting Equalizer for all engines")
        val isHiRes = currentEngineType == com.example.first.engine.AudioEngineFactory.EngineType.HI_RES
        val eqPrefs = getSharedPreferences("equalizer_prefs", Context.MODE_PRIVATE)
        val editor = eqPrefs.edit()

        // V10: All engines are now Oboe-based (Native EQ has 10 bands)
        val engine = audioEngine as? com.example.first.engine.NativeHiResEngine
        for (i in 0 until 10) {
            engine?.setEqBand(i, 0f)
            editor.putInt("band_$i", 0)
        }
        
        // Reset System EQ if active
        equalizer?.let { eq ->
            for (i in 0 until eq.numberOfBands) {
                eq.setBandLevel(i.toShort(), 0)
            }
        }
        editor.apply()
    }

    fun applyEqPreset(presetName: String) {
        Log.d(TAG, "Applying EQ Preset: $presetName")
        val isHiRes = currentEngineType == com.example.first.engine.AudioEngineFactory.EngineType.HI_RES
        
        // Frequencies: 31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k
        val gains = when (presetName) {
            "Bass" -> floatArrayOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f)
            "Treble" -> floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 2f, 4f, 5f, 6f)
            "Double Bass" -> floatArrayOf(10f, 8f, 6f, 4f, 2f, 0f, 0f, 0f, 0f, 0f)
            "Vocals" -> floatArrayOf(-2f, -1f, 0f, 2f, 4f, 5f, 4f, 2f, 0f, -1f)
            "Instrumentals" -> floatArrayOf(4f, 2f, 0f, 2f, 4f, 4f, 4f, 2f, 4f, 4f)
            else -> floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }

        if (isHiRes) {
            val engine = audioEngine as? com.example.first.engine.NativeHiResEngine
            gains.forEachIndexed { index, gain ->
                engine?.setEqBand(index, gain)
                // Persist
                getSharedPreferences("equalizer_prefs", Context.MODE_PRIVATE)
                    .edit().putInt("band_$index", (gain * 100).toInt()).apply()
            }
        } else {
            val eq = equalizer ?: return
            val numBands = eq.numberOfBands
            // For 5-band system EQ, we map our 10-band presets
            // System EQ usually: 60, 230, 910, 3600, 14000
            // Mapping: 
            // Band 0 (60)   <- 31/62 avg
            // Band 1 (230)  <- 125/250 avg
            // Band 2 (910)  <- 500/1k avg
            // Band 3 (3.6k) <- 2k/4k avg
            // Band 4 (14k)  <- 8k/16k avg
            
            for (i in 0 until numBands) {
                val gain = (gains[i*2] + gains[i*2 + 1]) / 2f
                val level = (gain * 100).toInt().toShort()
                eq.setBandLevel(i.toShort(), level)
                // Persist compatibly with EqualizerActivity
                getSharedPreferences("equalizer_prefs", Context.MODE_PRIVATE)
                    .edit().putInt("band_$i", level.toInt()).apply()
            }
        }
    }

    fun getPlaylist(): List<Song> {
        return playlist
    }

    fun getQueueIndex(): Int {
        return currentIndex
    }

    fun updatePlaylist(newList: List<Song>) {
        this.playlist = newList
        // Sync currentIndex with the currentSong's new position
        currentSong?.let { song ->
            val newIdx = playlist.indexOfFirst { it.id == song.id }
            if (newIdx != -1) {
                currentIndex = newIdx
            }
        }
    }

    fun getCurrentSong(): Song? {
        return currentSong
    }

    fun getCurrentEngineType(): com.example.first.engine.AudioEngineFactory.EngineType = currentEngineType

    private fun handleGaplessTransition() {
        Log.d(TAG, "Handling gapless transition")
        audioEngine?.release()
        audioEngine = nextAudioEngine
        currentSong = nextSong
        
        nextAudioEngine = null
        nextSong = null

        // Sync index
        val index = playlist.indexOfFirst { it.id == currentSong?.id }
        if (index != -1) {
            currentIndex = index
        }

        // Setup the new current player
        audioEngine?.let { engine ->
            engine.setOnCompletionListener { 
                if (nextAudioEngine != null) handleGaplessTransition() else playNext()
            }
            initEqualizer(engine.getAudioSessionId())
            applyPlaybackSpeed()
            // Metadata & UI
            currentSong?.let { 
                notifySongChanged(it)
                updateMediaSessionMetadata(it)
            }
        }

        // Prepare the next one
        prepareNextMediaPlayer()
    }

    private fun prepareNextMediaPlayer() {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        // Gapless is disabled when USB engine is active (timing model is incompatible)
        if (!prefs.getBoolean("gapless_playback", true)) return
        if (currentEngineType == AudioEngineFactory.EngineType.USB_DAC) return

        // Determine next song
        val next = if (queue.isNotEmpty()) {
            queue[0]
        } else if (playlist.isNotEmpty()) {
            playlist[(currentIndex + 1) % playlist.size]
        } else {
            null
        }

        if (next == null || next.id == currentSong?.id) return
        
        Log.d(TAG, "Pre-loading next song for gapless: ${next.title}")
        nextSong = next
        
        try {
            nextAudioEngine = MediaPlayerEngine().apply {
                setOnPreparedListener { 
                    Log.d(TAG, "Next engine prepared, setting as next")
                    audioEngine?.setNextEngine(this)
                }
                setOnErrorListener { _, _ ->
                    Log.e(TAG, "Error in next engine preparation")
                    nextAudioEngine = null
                    nextSong = null
                }
                setDataSource(applicationContext, next.uri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare next player", e)
        }
    }

    // MediaSession Logic
    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() = resumeSong()
        override fun onPause() = pauseSong()
        override fun onSkipToNext() = playNext()
        override fun onSkipToPrevious() = playPrevious()
        override fun onSeekTo(pos: Long) = this@MusicService.seekTo(pos.toInt())
    }

    private fun updateMediaSessionMetadata(song: Song) {
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, getDuration().toLong())

        // Load album art for lock screen
        val art = MetadataUtils.getAlbumArt(this, song.uri)
        if (art != null) {
            try {
                val inputStream = contentResolver.openInputStream(song.uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                inputStream?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error loading bitmap for MediaSession", e)
            }
        }

        mediaSession.setMetadata(metadataBuilder.build())
    }

    private fun updateMediaSessionState(overrideState: Int? = null) {
        val state = overrideState ?: if (isPlaying()) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val position = getCurrentPosition().toLong()
        
        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or 
                PlaybackStateCompat.ACTION_PAUSE or 
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or 
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or 
                PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(state, position, playbackSpeed)
        
        mediaSession.setPlaybackState(stateBuilder.build())
    }

    fun startSleepTimer(minutes: Int) {
        cancelSleepTimer()
        val millis = minutes * 60 * 1000L
        sleepTimer = object : CountDownTimer(millis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                // Could emit state here if needed
            }

            override fun onFinish() {
                pauseSong()
                sleepTimer = null
                Toast.makeText(this@MusicService, "Sleep timer finished. Playback paused.", Toast.LENGTH_SHORT).show()
            }
        }.start()
        Toast.makeText(this, "Sleep timer set for $minutes minutes", Toast.LENGTH_SHORT).show()
    }

    fun cancelSleepTimer() {
        sleepTimer?.cancel()
        sleepTimer = null
    }

    fun isSleepTimerActive(): Boolean = sleepTimer != null

    override fun onDestroy() {
        Log.d(TAG, "Service being destroyed")
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        releaseWakeLock()
        super.onDestroy()
        try { unregisterReceiver(screenReceiver) } catch (e: Exception) { /* not registered */ }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        }
        try { unregisterReceiver(noisyReceiver) } catch (e: Exception) { /* not registered */ }
        try { unregisterReceiver(settingsReceiver) } catch (e: Exception) { /* not registered */ }

        getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)

        // USB cleanup — must close connection before engine release
        usbHelper.unregister()
        activeUsbConnection?.close()
        activeUsbConnection = null

        audioEngine?.release()
        audioEngine = null
        nextAudioEngine?.release()
        nextAudioEngine = null
        sleepTimer?.cancel()
        sleepTimer = null
        equalizer?.release()
        equalizer = null
        abandonAudioFocus()
        listeners.clear()
        contentResolver.unregisterContentObserver(volumeObserver)
        mediaSession.isActive = false
        mediaSession.release()
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(24 * 60 * 60 * 1000L /* 24 hours max */)
                Log.d(TAG, "WakeLock ACQUIRED")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock RELEASED")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock", e)
        }
    }

    /**
     * Set USB DAC volume directly (0.0 = silent, 1.0 = max).
     * Called by the volume slider in PlayerActivity.
     * Bypasses system AudioManager — our USB pipeline is independent.
     */
    fun setUsbDirectVolume(linear: Float) {
        if (currentEngineType != com.example.first.engine.AudioEngineFactory.EngineType.USB_DAC) return
        val clamped = linear.coerceIn(0f, 1f)

        // Try hardware volume first (preserves full bit depth)
        val engine = audioEngine
        if (engine is com.example.first.engine.UsbBulkEngine) {
            val hwVol = engine.getVolumeControl()
            if (hwVol != null) {
                hwVol.setVolume(clamped)
                Log.d(TAG, "[USB] HW volume set to ${"%.2f".format(clamped)}")
                return
            }
        }

        // Fallback: software volume
        // Squared curve for natural perceptual volume
        val gain = clamped * clamped
        com.example.first.usb.NativeHiResEngineUsbBridge.setUsbVolume(gain)
        Log.d(TAG, "[USB] SW volume set: linear=${"%.2f".format(clamped)} gain=${"%.4f".format(gain)}")
    }

    /** Legacy system-volume observer callback — still syncs on boot/reconnect */
    private fun syncUsbVolume() {
        if (currentEngineType == com.example.first.engine.AudioEngineFactory.EngineType.USB_DAC) {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val linear = if (max > 0) current.toFloat() / max.toFloat() else 1.0f
            setUsbDirectVolume(linear)
        }
    }

    private fun setResonanceEnabled(enabled: Boolean) {
        val engine = audioEngine
        if (engine is com.example.first.engine.NativeHiResEngine) {
            engine.setDspParameter(
                com.example.first.engine.NativeHiResEngine.DSP_PARAM_RESONANCE_ENABLE,
                if (enabled) 1.0f else 0.0f
            )
    }
}
}
