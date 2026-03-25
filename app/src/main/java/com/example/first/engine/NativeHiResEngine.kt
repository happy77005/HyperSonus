package com.example.first.engine

import android.content.Context
import android.net.Uri
import com.example.first.Song
import com.example.first.usb.NativeHiResEngineUsbBridge

class NativeHiResEngine : IAudioEngine {

    private val TAG = "NativeHiResEngine"
    private var onPreparedListener: (() -> Unit)? = null
    private var onCompletionListener: (() -> Unit)? = null
    private var onErrorListener: ((Int, Int) -> Unit)? = null
    private var contextRef: Context? = null

    init {
        initEngine()
    }

    override fun setDataSource(context: Context, uri: Uri): Boolean {
        this.contextRef = context.applicationContext
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val bitPerfect = prefs.getBoolean("pref_bit_perfect", false)
        // Bluetooth requires SHARED mode, USB/Internal can use EXCLUSIVE
        val deviceClass = DeviceHelper.getCurrentDeviceClass(context)
        nativeSetDeviceClass(deviceClass.id)
        
        val isBluetooth = deviceClass == DeviceHelper.DeviceClass.BLUETOOTH
        nativeSetExclusiveMode(!isBluetooth)

        // Handle Resampling Engine
        val soxrEnabled = prefs.getBoolean("pref_soxr_enabled", true)
        setResampleEngine(soxrEnabled)

        // Handle bs2b Cross-feed
        updateBs2bState()

        // Resolve MIME type for decoder selection in native code
        val mimeType: String? = if (uri.scheme == "content") {
            context.contentResolver.getType(uri)
        } else {
            null
        }

        val deviceId = prefs.getInt("selected_device_id", -1)

        val path = when (uri.scheme) {
            "content" -> {
                try {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        val fd = pfd.fd
                        nativeSetDataSource("/proc/self/fd/$fd", bitPerfect, deviceId, mimeType)
                    } ?: false
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Error opening content URI: ${e.message}")
                    false
                }
            }
            "file" -> {
                nativeSetDataSource(uri.path ?: uri.toString(), bitPerfect, deviceId, mimeType)
            }
            else -> {
                nativeSetDataSource(uri.toString(), bitPerfect, deviceId, mimeType)
            }
        }
        return path as? Boolean ?: false
    }

    override fun play() {
        nativePlay()
    }

    override fun pause() {
        nativePause()
    }

    override fun resume() {
        nativeResume()
    }

    override fun stop() {
        nativeStop()
    }

    override fun seekTo(positionMs: Int) {
        nativeSeekTo(positionMs)
    }

    override fun release() {
        nativeRelease()
    }

    override fun setOnPreparedListener(listener: () -> Unit) {
        onPreparedListener = listener
    }

    override fun setOnCompletionListener(listener: () -> Unit) {
        onCompletionListener = listener
    }

    override fun setOnErrorListener(listener: (what: Int, extra: Int) -> Unit) {
        onErrorListener = listener
    }

    override fun isPlaying(): Boolean = nativeIsPlaying()

    override fun getCurrentPosition(): Int = nativeGetCurrentPosition()

    override fun getDuration(): Int = nativeGetDuration()

    override fun getAudioSessionId(): Int = 0 // Not used in native engine for now

    override fun setNextEngine(nextEngine: IAudioEngine?) {
        // Gapless support implemented in MusicService for now
    }

    override fun setPlaybackSpeed(speed: Float) {
        nativeSetPlaybackSpeed(speed)
    }

    override fun getActualSampleRate(): Int = nativeGetSampleRate()
    override fun getActualChannelCount(): Int = nativeGetChannelCount()
    override fun probeSampleRates(deviceId: Int): IntArray = nativeProbeSampleRates(deviceId)
    override fun getResamplerName(): String = nativeGetResamplerName()
    fun getDecoderName(): String = nativeGetDecoderName()

    fun getAudioBackend(): String = nativeGetAudioBackend()
    
    fun setDspParameter(paramId: Int, value: Float) {
        nativeSetDspParameter(paramId, value)
    }

    fun setEqBand(band: Int, gainDb: Float) {
        nativeSetEqBand(band, gainDb)
    }

    // JNI Callbacks (called from C++)
    private fun onNativePrepared() {
        onPreparedListener?.invoke()
    }

    private fun onNativeCompletion() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.util.Log.d(TAG, "onNativeCompletion: posting to main thread")
            onCompletionListener?.invoke()
        }
    }

    private fun onNativeError(what: Int, extra: Int) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            onErrorListener?.invoke(what, extra)
        }
    }

    private var onBitPerfectListener: ((Boolean, Int) -> Unit)? = null

    fun setOnBitPerfectListener(listener: (Boolean, Int) -> Unit) {
        onBitPerfectListener = listener
    }

    // Called from JNI
    private fun onNativeBitPerfect(isBitPerfect: Boolean, sampleRate: Int) {
         android.os.Handler(android.os.Looper.getMainLooper()).post {
             onBitPerfectListener?.invoke(isBitPerfect, sampleRate)
         }
    }

    // Native methods
    private external fun initEngine()
    private external fun nativeSetDataSource(path: String, bitPerfect: Boolean, deviceId: Int, mimeType: String?): Boolean
    private external fun nativePlay()
    private external fun nativePause()
    private external fun nativeResume()
    private external fun nativeStop()
    private external fun nativeSeekTo(positionMs: Int)
    private external fun nativeRelease()
    private external fun nativeIsPlaying(): Boolean
    private external fun nativeGetCurrentPosition(): Int
    private external fun nativeGetDuration(): Int
    private external fun nativeSetPlaybackSpeed(speed: Float)
    private external fun nativeGetSampleRate(): Int
    private external fun nativeGetChannelCount(): Int
    private external fun nativeGetAudioBackend(): String
    private external fun nativeProbeSampleRates(deviceId: Int): IntArray
    private external fun nativeSetDspParameter(paramId: Int, value: Float)
    override fun setDeviceId(deviceId: Int) {
        // When device ID changes, re-evaluate if we need Shared Mode (Bluetooth)
        contextRef?.let {
            val deviceClass = DeviceHelper.getCurrentDeviceClass(it)
            nativeSetDeviceClass(deviceClass.id)
            nativeSetExclusiveMode(deviceClass != DeviceHelper.DeviceClass.BLUETOOTH)
            updateBs2bState()
        }
        nativeSetDeviceId(deviceId)
    }

    fun setExclusiveMode(exclusive: Boolean) {
        nativeSetExclusiveMode(exclusive)
    }

    private external fun nativeSetDeviceId(deviceId: Int)
    private external fun nativeSetDeviceClass(deviceClass: Int)
    private external fun nativeSetExclusiveMode(exclusive: Boolean)
    private external fun nativeSetResampleEngine(engine: Int)
    override fun setResampleEngine(enabled: Boolean) {
        val prefs = contextRef?.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) ?: return
        val resamplePref = prefs.getString("pref_resampling_profile", "auto")
        val dacInfo = DacHelper.getCurrentDacInfo(contextRef!!)
        val isBluetooth = dacInfo.type == DacHelper.DacType.BLUETOOTH
        
        val useSoxr = if (isBluetooth) {
            false // Forced bypass for Bluetooth as per new routing architecture
        } else if (enabled) {
            when (resamplePref) {
                "extreme" -> true
                "standard" -> false
                else -> false
            }
        } else {
            false
        }
        nativeSetResampleEngine(if (useSoxr) 1 else 0)
    }

    fun updateBs2bState() {
        val context = contextRef ?: return
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val bs2bEnabledPref = prefs.getBoolean("pref_bs2b_enabled", false)
        val deviceClass = DeviceHelper.getCurrentDeviceClass(context)
        val isBluetooth = deviceClass == DeviceHelper.DeviceClass.BLUETOOTH

        // Only enable BS2B if user wants it AND we are on Bluetooth
        val shouldEnable = bs2bEnabledPref && isBluetooth
        setDspParameter(DSP_PARAM_BS2B_ENABLE, if (shouldEnable) 1.0f else 0.0f)
        
        if (shouldEnable) {
            val level = prefs.getInt("pref_bs2b_level", 700 or (45 shl 16)) // Default CLEVEL
            setDspParameter(DSP_PARAM_BS2B_LEVEL, level.toFloat())
        }
        
        android.util.Log.d(TAG, "updateBs2bState: enabledPref=$bs2bEnabledPref, isBluetooth=$isBluetooth -> active=$shouldEnable")
    }

    private external fun nativeGetResamplerName(): String
    private external fun nativeGetDecoderName(): String
    private external fun nativeSetEqBand(band: Int, gainDb: Float)

    // ── USB exclusive mode helpers ────────────────────────────────────────────
    /**
     * Returns current ring buffer occupancy in frames.
     * Used by UsbBulkEngine to check pre-buffer readiness.
     */
    fun getRingBufferFillFrames(): Int =
        NativeHiResEngineUsbBridge.getRingBufferFillFrames()

    /**
     * Enable USB-only mode: closes Oboe stream, PCM consumed by UsbNativeStreamer.
     */
    fun setUsbOutputModeEnabled(enabled: Boolean) =
        NativeHiResEngineUsbBridge.setUsbOutputMode(enabled)

    /**
     * Tell native decoder to produce output at DAC's exact format.
     */
    fun setOutputFormat(sampleRate: Int, bitDepth: Int, channels: Int, bitFormatOrdinal: Int) =
        NativeHiResEngineUsbBridge.setUsbOutputFormat(sampleRate, bitDepth, channels, bitFormatOrdinal)

    companion object {
        const val DSP_PARAM_PREAMP = 1
        const val DSP_PARAM_POSTAMP = 2
        const val DSP_PARAM_RESONANCE_ENABLE = 5
        const val DSP_PARAM_BS2B_ENABLE = 6
        const val DSP_PARAM_BS2B_LEVEL = 7

        init {
            System.loadLibrary("hyperplay")
        }
    }
}

