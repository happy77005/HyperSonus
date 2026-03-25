package com.example.first

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class EqualizerActivity : AppCompatActivity() {

    private var musicService: MusicService? = null
    private var isBound = false
    private lateinit var bandsContainer: LinearLayout
    private lateinit var btnReset: View

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isBound = true
            setupEqualizerUI()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            musicService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_equalizer)

        bandsContainer = findViewById(R.id.bandsContainer)
        btnReset = findViewById(R.id.btnReset)
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.topDimmer).setOnClickListener { finish() }

        btnReset.setOnClickListener {
            resetEqualizer()
        }

        setupPresetChips()

        Intent(this, MusicService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun setupPresetChips() {
        val chipGroup = findViewById<com.google.android.material.chip.ChipGroup>(R.id.presetChipGroup)
        val chips = mapOf(
            R.id.chipBass to "Bass",
            R.id.chipTreble to "Treble",
            R.id.chipDoubleBass to "Double Bass",
            R.id.chipVocals to "Vocals",
            R.id.chipInstrumentals to "Instrumentals"
        )

        chips.forEach { (id, name) ->
            findViewById<com.google.android.material.chip.Chip>(id).setOnClickListener {
                musicService?.applyEqPreset(name)
                setupEqualizerUI() // Refresh sliders
            }
        }
    }

    private fun setupEqualizerUI() {
        val isHiRes = musicService?.getCurrentEngineType() == com.example.first.engine.AudioEngineFactory.EngineType.HI_RES
        bandsContainer.removeAllViews()

        if (isHiRes) {
            // 10-band Native EQ
            val bands = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
            val descriptors = listOf("Sub-Bass", "Bass", "Low-Bass", "Low-Mids", "Mids / Vocals", "Center Mids", "Instruments", "Presence", "Treble", "Air")
            for (i in bands.indices) {
                val label = "${bands[i]} Hz - ${descriptors[i]}"
                addBandToUI(i.toShort(), label, -1200, 1200) // +/- 12dB
            }
        } else {
            val eq = musicService?.getEqualizer() ?: return
            val numBands = eq.numberOfBands
            val minLevel = eq.bandLevelRange[0]
            val maxLevel = eq.bandLevelRange[1]
            val descriptors = listOf("Bass", "Low-Mids", "Mids / Vocals", "High-Mids", "Treble")

            for (i in 0 until numBands) {
                val band = i.toShort()
                val freq = eq.getCenterFreq(band) / 1000 // Convert to Hz
                val desc = if (i < descriptors.size) " - ${descriptors[i]}" else ""
                val freqLabel = if (freq >= 1000) "${freq/1000} kHz$desc" else "$freq Hz$desc"
                addBandToUI(band, freqLabel, minLevel, maxLevel)
            }
        }
    }

    private fun addBandToUI(band: Short, labelText: String, min: Short, max: Short) {
        val range = max - min
        
        // Dynamic UI Generation logic... (same as before but using helper)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 32, 0, 0) }
        }

        val label = TextView(this).apply {
            text = labelText
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(8, 0, 0, 8)
        }

        val seekBar = SeekBar(this).apply {
            this.max = range.toInt()
            // Pull existing value if it exists
            progress = (getBandLevel(band) - min).toInt()
            progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sPath: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val level = (p + min).toShort()
                        musicService?.setBandLevel(band, level)
                    }
                }
                override fun onStartTrackingTouch(p0: SeekBar?) {}
                override fun onStopTrackingTouch(p0: SeekBar?) {}
            })
        }

        row.addView(label)
        row.addView(seekBar)
        bandsContainer.addView(row)
    }

    private fun getBandLevel(band: Short): Short {
        val prefs = getSharedPreferences("equalizer_prefs", Context.MODE_PRIVATE)
        return prefs.getInt("band_$band", 0).toShort()
    }

    private fun resetEqualizer() {
        musicService?.resetEqualizer()
        
        // Refresh UI
        setupEqualizerUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}
