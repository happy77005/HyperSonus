package com.example.first

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.os.Build
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.TextView
import android.widget.SeekBar
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.google.android.material.switchmaterial.SwitchMaterial
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
import android.graphics.Color

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

        // --- Back Button ---
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
        }

        // --- Library & Scan ---
        findViewById<View>(R.id.btnChangeFolder).setOnClickListener {
            val songCache = SongCache(this)
            songCache.clearCache()
            prefs.edit().clear().apply()
            
            val intent = Intent(this, LandingActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }

        findViewById<View>(R.id.btnRefreshScan).setOnClickListener {
            val songCache = SongCache(this)
            songCache.clearCache()
            
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("FORCE_RESCAN", true)
            intent.putExtra("SCAN_TYPE", prefs.getString("SCAN_TYPE", "FULL"))
            intent.putExtra("FOLDER_URI", prefs.getString("FOLDER_URI", null))
            
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }

        // --- Playback Behavior ---
        val switchPause = findViewById<SwitchMaterial>(R.id.switchPauseOnDisconnect)
        switchPause.isChecked = prefs.getBoolean("pause_on_disconnect", true)
        switchPause.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("pause_on_disconnect", isChecked).apply()
        }

        val switchGapless = findViewById<SwitchMaterial>(R.id.switchGapless)
        switchGapless.isChecked = prefs.getBoolean("gapless_playback", true)
        switchGapless.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("gapless_playback", isChecked).apply()
        }

        // --- Audio Engine Selection (Auto-Managed) ---
        val rbNormal = findViewById<RadioButton>(R.id.rbEngineNormal)
        val rbHiRes = findViewById<RadioButton>(R.id.rbEngineHiRes)
        val rbUsbDirect = findViewById<RadioButton>(R.id.rbEngineUsbDirect)
        val btnNormal = findViewById<CardView>(R.id.btnEngineNormal)
        val btnHiRes = findViewById<CardView>(R.id.btnEngineHiRes)
        val btnUsbDirect = findViewById<CardView>(R.id.btnEngineUsbDirect)

        val deviceClass = com.example.first.engine.DeviceHelper.getCurrentDeviceClass(this)
        val engineEnum = com.example.first.engine.AudioEngineFactory.getEngineForClassification(deviceClass)
        val currentEngine = engineEnum.name
        
        rbNormal.isChecked = currentEngine == "NORMAL"
        rbHiRes.isChecked = currentEngine == "HI_RES"
        rbUsbDirect.isChecked = currentEngine == "USB_DAC"

        // --- Tuning Components ---
        val switchResonance = findViewById<SwitchMaterial>(R.id.switchResonance)
        val switchBitPerfect = findViewById<SwitchMaterial>(R.id.switchBitPerfect)
        val switchSoxr = findViewById<SwitchMaterial>(R.id.switchSoxrResampler)
        val switchBs2b = findViewById<SwitchMaterial>(R.id.switchBs2bEnabled)
        val sbPreAmp = findViewById<SeekBar>(R.id.sbPreAmp)
        val tvPreAmpLabel = findViewById<TextView>(R.id.tvPreAmpLabel)
        val layoutResampling = findViewById<LinearLayout>(R.id.layoutResamplingProfiles)
        val layoutBs2bIntensity = findViewById<LinearLayout>(R.id.layoutBs2bIntensity)
        val tvResHeader = findViewById<TextView>(R.id.tvResamplingHeader)

        // Resampling Radios
        val rbResAuto = findViewById<RadioButton>(R.id.rbResampleAuto)
        val rbResExtreme = findViewById<RadioButton>(R.id.rbResampleExtreme)
        val rbResStandard = findViewById<RadioButton>(R.id.rbResampleStandard)
        val btnResAuto = findViewById<CardView>(R.id.btnResampleAuto)
        val btnResExtreme = findViewById<CardView>(R.id.btnResampleExtreme)
        val btnResStandard = findViewById<CardView>(R.id.btnResampleStandard)

        // BS2B Radios
        val rbBs2bLow = findViewById<RadioButton>(R.id.rbBs2bLow)
        val rbBs2bMedium = findViewById<RadioButton>(R.id.rbBs2bMedium)
        val rbBs2bHigh = findViewById<RadioButton>(R.id.rbBs2bHigh)
        val btnBs2bLow = findViewById<CardView>(R.id.btnBs2bLow)
        val btnBs2bMedium = findViewById<CardView>(R.id.btnBs2bMedium)
        val btnBs2bHigh = findViewById<CardView>(R.id.btnBs2bHigh)

        // --- Tuning Logic ---
        
        fun updateTuningVisibility() {
            val isSoxr = switchSoxr.isChecked
            val isBs2b = switchBs2b.isChecked
            layoutResampling.visibility = if (isSoxr) View.VISIBLE else View.GONE
            layoutBs2bIntensity.visibility = if (isBs2b) View.VISIBLE else View.GONE
        }

        fun updateNativeTuningUI(isHiRes: Boolean) {
            val deviceClass = com.example.first.engine.DeviceHelper.getCurrentDeviceClass(this)
            val isHardcodedPath = deviceClass != com.example.first.engine.DeviceHelper.DeviceClass.UNKNOWN

            val layoutNative = findViewById<LinearLayout>(R.id.layoutNativeTuning)
            
            if (isHardcodedPath) {
                // Block tweaks as requested for hardcoded paths
                layoutNative.alpha = 0.4f
                val tuningViews = listOf(
                    switchResonance, switchBitPerfect, switchSoxr, switchBs2b, sbPreAmp,
                    btnResAuto, btnResExtreme, btnResStandard,
                    btnBs2bLow, btnBs2bMedium, btnBs2bHigh
                )
                tuningViews.forEach { it.isEnabled = false }
                
                val blockedListener = View.OnClickListener {
                    Toast.makeText(this, "Tuning tweaks are blocked for the current hardware path.", Toast.LENGTH_SHORT).show()
                }
                layoutNative.setOnClickListener(blockedListener)
                return
            }

            layoutNative.alpha = if (isHiRes) 1.0f else 0.4f
            
            val tuningViews = listOf(
                switchResonance, switchBitPerfect, switchSoxr, switchBs2b, sbPreAmp,
                btnResAuto, btnResExtreme, btnResStandard,
                btnBs2bLow, btnBs2bMedium, btnBs2bHigh
            )
            tuningViews.forEach { it.isEnabled = isHiRes }

            if (!isHiRes) {
                val lockedListener = View.OnClickListener {
                    Toast.makeText(this, "Requires Hi-Res Engine", Toast.LENGTH_SHORT).show()
                }
                layoutNative.setOnClickListener(lockedListener)
            } else {
                layoutNative.setOnClickListener(null)
            }
        }

        // --- Listeners: Tuning ---

        switchResonance.isChecked = prefs.getBoolean("pref_resonance_enabled", false)
        switchResonance.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("pref_resonance_enabled", isChecked).apply()
            sendBroadcast(Intent("com.example.first.ACTION_SET_RESONANCE").apply {
                setPackage(packageName)
                putExtra("enabled", isChecked)
            })
        }

        switchBitPerfect.isChecked = prefs.getBoolean("pref_bit_perfect", false)
        switchBitPerfect.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("pref_bit_perfect", isChecked).apply()
            // Bit-perfect changes usually require track reload, which MusicService handles via PrefChangeListener
        }

        switchSoxr.isChecked = prefs.getBoolean("pref_soxr_enabled", true)
        switchSoxr.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("pref_soxr_enabled", isChecked).apply()
            updateTuningVisibility()
            sendBroadcast(Intent("com.example.first.ACTION_SET_SOXR").apply {
                setPackage(packageName)
                putExtra("enabled", isChecked)
            })
        }

        switchBs2b.isChecked = prefs.getBoolean("pref_bs2b_enabled", false)
        switchBs2b.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("pref_bs2b_enabled", isChecked).apply()
            updateTuningVisibility()
            sendBroadcast(Intent("com.example.first.ACTION_SET_BS2B").apply {
                setPackage(packageName)
                putExtra("enabled", isChecked)
            })
        }

        // Resampling Profile Selection
        fun selectResample(key: String) {
            rbResAuto.isChecked = key == "auto"
            rbResExtreme.isChecked = key == "extreme"
            rbResStandard.isChecked = key == "standard"
            prefs.edit().putString("pref_resampling_profile", key).apply()
        }
        val currentRes = prefs.getString("pref_resampling_profile", "auto")
        selectResample(currentRes ?: "auto")
        btnResAuto.setOnClickListener { selectResample("auto") }
        btnResExtreme.setOnClickListener { selectResample("extreme") }
        btnResStandard.setOnClickListener { selectResample("standard") }

        // BS2B Intensity Selection
        fun getBs2bLevelInt(key: String): Int = when (key) {
            "low" -> 700 or (30 shl 16)
            "high" -> 700 or (60 shl 16)
            else -> 700 or (45 shl 16)
        }
        fun selectBs2b(key: String) {
            rbBs2bLow.isChecked = key == "low"
            rbBs2bMedium.isChecked = key == "medium"
            rbBs2bHigh.isChecked = key == "high"
            val level = getBs2bLevelInt(key)
            prefs.edit().putString("pref_bs2b_intensity", key).apply()
            prefs.edit().putInt("pref_bs2b_level", level).apply()
            sendBroadcast(Intent("com.example.first.ACTION_SET_BS2B_LEVEL").apply {
                setPackage(packageName)
                putExtra("level", level)
            })
        }
        val currentBs2b = prefs.getString("pref_bs2b_intensity", "medium")
        selectBs2b(currentBs2b ?: "medium")
        btnBs2bLow.setOnClickListener { selectBs2b("low") }
        btnBs2bMedium.setOnClickListener { selectBs2b("medium") }
        btnBs2bHigh.setOnClickListener { selectBs2b("high") }

        // Pre-Amp SeekBar
        val savedPreAmp = prefs.getInt("pref_preamp_progress", 220)
        sbPreAmp.progress = savedPreAmp
        val updatePreAmpText = { progress: Int ->
            val db = (progress - 200) / 10f
            tvPreAmpLabel.text = "Pre-Amp Boost (${if (db > 0) "+" else ""}${String.format("%.1f", db)} dB)"
            db
        }
        updatePreAmpText(savedPreAmp)
        sbPreAmp.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val db = updatePreAmpText(p)
                if (fromUser) {
                    if (Build.VERSION.SDK_INT >= 27) s?.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    prefs.edit().putInt("pref_preamp_progress", p).apply()
                    sendBroadcast(Intent("com.example.first.ACTION_SET_PREAMP").apply {
                        setPackage(packageName)
                        putExtra("db", db)
                    })
                }
            }
            override fun onStartTrackingTouch(p0: SeekBar?) {}
            override fun onStopTrackingTouch(p0: SeekBar?) {}
        })

        // --- Engine Selection Logic (Disabled - Auto Managed) ---
        val autoManagedListener = View.OnClickListener {
            Toast.makeText(this, "Audio Path is automatically managed based on connected device.", Toast.LENGTH_SHORT).show()
        }
        btnNormal.setOnClickListener(autoManagedListener)
        btnHiRes.setOnClickListener(autoManagedListener)
        btnUsbDirect.setOnClickListener(autoManagedListener)
        
        rbNormal.isClickable = false
        rbHiRes.isClickable = false
        rbUsbDirect.isClickable = false
        btnNormal.alpha = if (currentEngine == "NORMAL") 1.0f else 0.5f
        btnHiRes.alpha = if (currentEngine == "HI_RES") 1.0f else 0.5f
        btnUsbDirect.alpha = if (currentEngine == "USB_DAC") 1.0f else 0.5f

        // --- USB DAC Bypass ---
        val switchUsbBypass = findViewById<SwitchMaterial>(R.id.switchUsbDacBypass)
        switchUsbBypass.isChecked = prefs.getBoolean("pref_usb_dac_bypass", true)
        switchUsbBypass.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("pref_usb_dac_bypass", isChecked).apply()
        }

        // --- Background Stability ---
        findViewById<View>(R.id.btnBatteryOptimization).setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }
        findViewById<View>(R.id.btnSystemAppInfo).setOnClickListener {
            openAppInfo()
        }

        // Initial UI State
        updateNativeTuningUI(currentEngine == "HI_RES" || currentEngine == "USB_DAC")
        updateTuningVisibility()
        updateBatteryOptUI()

        // --- Handle Highlight Intent ---
        if (intent.getBooleanExtra("HIGHLIGHT_BATTERY", false)) {
            val btnBattery = findViewById<CardView>(R.id.btnBatteryOptimization)
            val scrollView = findViewById<android.widget.ScrollView>(R.id.settingsScrollView)
            
            // Highlight effect using a subtle color animation or permanent red shadow
            btnBattery.setCardBackgroundColor(android.graphics.Color.parseColor("#33FF0000")) // Subtle red tint
            
            // Post calculation to ensure layout is ready
            btnBattery.post {
                scrollView.smoothScrollTo(0, btnBattery.top)
                
                // Optional: Pulse animation or dynamic color reset after a few seconds
                btnBattery.postDelayed({
                    btnBattery.setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
                }, 3000)
            }
        }
    }

    private fun openAppInfo() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open app info", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateBatteryOptUI()
    }

    private fun updateBatteryOptUI() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoring = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(packageName)
        } else true

        val tvStatus = findViewById<TextView>(R.id.tvBatteryOptStatus)
        val tvDesc = findViewById<TextView>(R.id.tvBatteryOptDesc)
        val btn = findViewById<View>(R.id.btnBatteryOptimization)

        if (isIgnoring) {
            tvStatus.text = "Optimized (OFF)"
            tvStatus.setTextColor(Color.parseColor("#4CAF50"))
            tvDesc.text = "Background playback is stable"
            btn.alpha = 0.6f
            btn.isEnabled = false
        } else {
            tvStatus.text = "Active"
            tvStatus.setTextColor(Color.RED)
            tvDesc.text = "Tap to fix background playback stopping"
            btn.alpha = 1.0f
            btn.isEnabled = true
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e2: Exception) {
                    Toast.makeText(this, "Could not open battery settings", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
