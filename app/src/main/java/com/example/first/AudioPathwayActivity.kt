package com.example.first

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.first.engine.DacHelper

class AudioPathwayActivity : AppCompatActivity() {

    private var musicService: MusicService? = null
    private var isBound = false
    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updatePathway()
            handler.postDelayed(this, 1000)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isBound = true
            updatePathway()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio_pathway)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        Intent(this, MusicService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(updateRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    private fun updatePathway() {
        val service = musicService ?: return
        val song = service.getCurrentSong() ?: return
        val currentEngine = service.getCurrentEngineType()
        val techInfo = service.getAudioTechnicalInfo()
        val infoLines = techInfo.split("\n")
        
        // --- Technical Stats Card ---
        val engineDisplay = infoLines.find { it.startsWith("Engine:") }?.removePrefix("Engine: ") ?: "Unknown"
        val backend = infoLines.find { it.startsWith("Backend:") }?.removePrefix("Backend: ") ?: "Unknown"
        val bitPerfect = infoLines.find { it.startsWith("Bit-Perfect:") }?.removePrefix("Bit-Perfect: ") ?: "OFF"
        val bitDepth = infoLines.find { it.startsWith("Bit Depth:") }?.removePrefix("Bit Depth: ") ?: "32-bit Float"
        
        findViewById<TextView>(R.id.tv_stat_engine).text = "Engine: $engineDisplay"
        findViewById<TextView>(R.id.tv_stat_backend).text = "Backend: $backend"
        findViewById<TextView>(R.id.tv_stat_precision).text = "Precision: $bitDepth"
        findViewById<TextView>(R.id.tv_stat_bitperfect).text = "Bit-Perfect: $bitPerfect"

        val dacInfo = DacHelper.getCurrentDacInfo(this)

        // 1. Source Stage
        val sourceFreq = if (song.sampleRate != null) "${song.sampleRate}Hz" else "Unknown Rate"
        val sourceBitrate = if (song.bitrate != null) "${song.bitrate / 1000}kbps" else "Unknown Bitrate"
        updateStage(R.id.stage_source, "Source: ${song.title}", "$sourceFreq • $sourceBitrate", R.drawable.ic_play_sharp)
        
        // 2. Decoder Stage
        val decoderName = infoLines.find { it.startsWith("Decoder:") }?.removePrefix("Decoder: ") ?: "FFmpeg"
        val classification = if ((song.sampleRate ?: 0) >= 48000) "HI-RES AUDIO" else "STANDARD"
        updateStage(R.id.stage_decoder, "Decoder: $decoderName ($classification)", "${song.artist} • 32-bit PCM", R.drawable.ic_queue_sharp)

        // 3. Resampler Stage
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val soxrEnabled = prefs.getBoolean("pref_soxr_enabled", true)
        val resamplerView = findViewById<android.view.View>(R.id.stage_resampler)
        if (soxrEnabled) {
            resamplerView.visibility = android.view.View.VISIBLE
            val resamplerName = infoLines.find { it.startsWith("Resampler:") }?.removePrefix("Resampler: ") ?: "None"
            val outputRate = infoLines.find { it.startsWith("Output Rate:") }?.removePrefix("Output Rate: ") ?: "System"
            val channels = infoLines.find { it.startsWith("Channels:") }?.removePrefix("Channels: ") ?: "2"
            updateStage(R.id.stage_resampler, "Resampler: $resamplerName", "$outputRate • $channels Channels • VHQ Mode", R.drawable.ic_timer_sharp)
        } else {
            resamplerView.visibility = android.view.View.GONE
        }

        // 4. DSP Stage
        val isResonance = prefs.getBoolean("pref_resonance_enabled", false)
        val resonanceStr = if (isResonance) "Low-Pass ON" else "Bypassed"
        updateStage(R.id.stage_dsp, "DSP Processor", "Floating Point Pipeline • $resonanceStr", R.drawable.ic_equalizer_sharp)

        // 4.5 Cross-feed Stage (bs2b)
        val crossfeedView = findViewById<android.view.View>(R.id.layout_stage_crossfeed)
        val bs2bEnabled = prefs.getBoolean("pref_bs2b_enabled", false)
        val isBluetooth = dacInfo.type == com.example.first.engine.DacHelper.DacType.BLUETOOTH
        
        if (bs2bEnabled && isBluetooth) {
            crossfeedView.visibility = android.view.View.VISIBLE
            updateStage(R.id.stage_crossfeed, "Headphone Cross-feed (bs2b)", "Binaural Simulation • TWS Active", R.drawable.ic_equalizer_sharp)
        } else {
            crossfeedView.visibility = android.view.View.GONE
        }

        // 4.6 Limiter Stage
        val limiterStatus = infoLines.find { it.startsWith("Limiter:") }?.removePrefix("Limiter: ") ?: "Unknown"
        updateStage(R.id.stage_limiter, "Safety Limiter", "Hard Clipping Protection • $limiterStatus", R.drawable.ic_equalizer_sharp)

        // 4.7 Dither Stage
        val ditherStatus = infoLines.find { it.startsWith("Dither:") }?.removePrefix("Dither: ") ?: "Unknown"
        updateStage(R.id.stage_dither, "TPDF Dither", "Quantization Noise Shaping • $ditherStatus", R.drawable.ic_equalizer_sharp)

        // 5. Output Stage
        val btInfo = infoLines.find { it.startsWith("Bluetooth:") }?.removePrefix("Bluetooth: ")
        val dacOutput = infoLines.find { it.startsWith("DAC Output:") }?.removePrefix("DAC Output: ")
        
        val outputTitle = if (btInfo != null) "Output: Bluetooth (TWS)" else "Output: ${dacInfo.name}"
        val outputDetail = if (dacOutput != null && dacOutput != "System Managed") {
            "DAC: $dacOutput • ID: ${dacInfo.id}"
        } else {
            btInfo ?: "Direct Path • ID: ${dacInfo.id}"
        }
        updateStage(R.id.stage_output, outputTitle, outputDetail, R.drawable.ic_next_sharp)

        // Probed Rates Footer
        val probed = dacInfo.probedSampleRates
        val probedStr = if (probed.isNotEmpty()) "Supported Rates (Probed): ${probed.joinToString(", ") { "${it/1000}k" }}" else "Supported Rates: Hardware Bound"
        findViewById<TextView>(R.id.tv_probed_rates).text = probedStr
    }

    private fun updateStage(id: Int, title: String, detail: String, iconRes: Int) {
        val view = findViewById<android.view.View>(id)
        view.findViewById<TextView>(R.id.tv_stage_name).text = title
        view.findViewById<TextView>(R.id.tv_stage_detail).text = detail
        view.findViewById<ImageView>(R.id.iv_icon).setImageResource(iconRes)
    }
}
