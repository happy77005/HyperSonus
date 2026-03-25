package com.example.first.engine

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.net.Uri
import android.util.Log
import com.example.first.usb.DeviceQuirkManager
import com.example.first.usb.NativeHiResEngineUsbBridge
import com.example.first.usb.UsbBulkStreamer
import com.example.first.usb.UsbDescriptorParser
import com.example.first.usb.UsbVolumeControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * IAudioEngine implementation for USB exclusive / bit-perfect mode.
 *
 * Architecture:
 *   FFmpegDecoder (C++) → AudioRingBuffer → UsbNativeStreamer (C++ RT thread) → bulkTransfer → DAC
 *
 * The existing NativeHiResEngine is used internally for decoding only (USB bypass mode).
 * Oboe is NOT opened in this path.
 *
 * This engine is created by AudioEngineFactory when a USB DAC is connected and the
 * user has USB-exclusive mode enabled.
 */
class UsbBulkEngine(
    private val context: Context,
    private val usbDevice: UsbDevice,
    private val connection: UsbDeviceConnection
) : IAudioEngine {

    init {
        Log.e("USB_DUMP", "===== UsbBulkEngine INIT for ${usbDevice.deviceName} =====")
        UsbDescriptorParser.dumpUsbDescriptors(usbDevice, connection)
    }

    private val TAG = "MusicService" // 🔧 Temporarily use MusicService to bypass logcat *:S filter

    // Internal decoder (USB bypass mode — no Oboe stream)
    private val decoder = NativeHiResEngine()

    private var config: UsbDescriptorParser.UsbAudioConfig? = null
    private var streamer: UsbBulkStreamer? = null
    private var volumeControl: UsbVolumeControl? = null

    fun getVolumeControl(): UsbVolumeControl? = volumeControl

    private var currentRate = 0; private var currentDepth = 0; private var currentCh = 0
    private var bitPerfect = false
    private var restartCount = 0
    private var decoderPrimed = false // 🔧 Decoder has analyzed format and filled Ring Buffer
    private var pendingPlay   = false // 🔧 Queues play() if called during priming

    private var onPreparedListener: (() -> Unit)? = null
    private var onCompletionListener: (() -> Unit)? = null
    private var onErrorListener: ((Int, Int) -> Unit)? = null

    private val scope = CoroutineScope(Dispatchers.IO)

    // ─── IAudioEngine: Media Control ─────────────────────────────────────────

    override fun setDataSource(context: Context, uri: Uri): Boolean {
        Log.d(TAG, "[USB] setDataSource: $uri")

        // Enable USB bypass mode in native engine (skips Oboe stream)
        NativeHiResEngineUsbBridge.setUsbOutputMode(true)

        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        this.bitPerfect = prefs.getBoolean("pref_bit_perfect", false)

        val success = decoder.setDataSource(context, uri)
        if (success) {
            // 🔧 Post-setDataSource priming coroutine
            scope.launch {
                Log.d(TAG, "[USB] Priming decoder for format analysis...")
                
                // 🔥 Step 1: Force decoder to start producing frames (ffmpeg-analysis + ringfill)
                decoder.play()
                
                var sr = 0
                var ch = 0
                
                repeat(50) { // ~1s max timeout (50 * 20ms)
                    delay(20)
                    sr = decoder.getActualSampleRate()
                    ch = decoder.getActualChannelCount()
                    if (sr > 0 && ch > 0) return@repeat
                }

                if (sr <= 0 || ch <= 0) {
                    Log.e(TAG, "[USB] Decoder format invalid after kick-start: ${sr}Hz ${ch}ch")
                    onErrorListener?.invoke(-5, 0)
                    return@launch
                }

                Log.d(TAG, "[USB] Decoder format ready: ${sr}Hz ${ch}ch")

                // 🔥 Step 2: Pause and setup USB
                decoder.pause()
                
                val successUsb = setupUsbForFormat(sr, 32, ch) // Always 32-bit for float decoder alignment
                if (successUsb) {
                    Log.d(TAG, "[USB] setupUsbForFormat SUCCESS -> decoderPrimed = true")
                    
                    val dacRate = currentRate
                    if (dacRate != sr) {
                        Log.w(TAG, "[USB] DAC rate ($dacRate) differs from decoder ($sr) — re-configuring decoder")
                    }

                    decoderPrimed = true
                    Log.d(TAG, "[USB] Triggering onPreparedListener?.invoke()")
                    onPreparedListener?.invoke()
                    
                    if (pendingPlay) {
                        Log.d(TAG, "[USB] Executing pending play request")
                        pendingPlay = false
                        startPlayback()
                    }
                } else {
                    Log.e(TAG, "[USB] setupUsbForFormat failed during priming")
                }
            }
        }

        decoder.setOnErrorListener { what, extra ->
            Log.e(TAG, "[USB] Internal decoder error: $what/$extra")
            onErrorListener?.invoke(what, extra)
        }

        decoder.setOnPreparedListener {
            Log.d(TAG, "[USB] Internal decoder (NativeHiResEngine) prepared")
        }

        return success
    }

    private fun startPlayback() {
        if (!decoderPrimed) return
        
        Log.d(TAG, "[USB] startPlayback: calling decoder.play() and starting streamer")
        decoder.play()
        val quirk = DeviceQuirkManager.getQuirk(usbDevice.vendorId, usbDevice.productId)
        val chunkMs = quirk.forceChunkMs.takeIf { it > 0 }
            ?: if (config?.isIsoEndpoint == true) 1 else 4

        scope.launch {
            Log.d(TAG, "[USB] Entering startPlayback coroutine (launch)")
            waitForPreBuffer()
            Log.d(TAG, "[USB] Calling streamer?.start(chunkMs)")
            streamer?.start(chunkMs) ?: Log.e(TAG, "[USB] streamer is null during startPlayback")
            Log.d(TAG, "[USB] startPlayback coroutine complete")
        }
    }

    private suspend fun waitForValidFormat(): Pair<Int, Int>? {
        repeat(20) { // ~200ms max
            val sr = decoder.getActualSampleRate()
            val ch = decoder.getActualChannelCount()
            if (sr > 0 && ch > 0) return sr to ch
            delay(10)
        }
        return null
    }

    override fun play() {
        if (!decoderPrimed) {
            Log.d(TAG, "[USB] play() called but decoder not primed — queuing")
            pendingPlay = true
            return
        }
        startPlayback()
    }

    override fun pause() {
        streamer?.pause()
        decoder.pause()
    }

    override fun resume() {
        decoder.resume()
        streamer?.resume()
    }

    override fun stop() {
        streamer?.stop()
        decoder.stop()
        decoderPrimed = false
        pendingPlay   = false
        releaseUsbResources()
    }

    override fun seekTo(positionMs: Int) {
        val wasPlaying = isPlaying()
        // Drain and restart streamer after seek to avoid stale audio
        streamer?.pause()
        decoder.seekTo(positionMs)
        if (wasPlaying) {
            streamer?.resume()
        }
    }

    override fun release() {
        stop()
        decoder.release()
        NativeHiResEngineUsbBridge.setUsbOutputMode(false)
    }

    // ─── IAudioEngine: Listeners ─────────────────────────────────────────────

    override fun setOnPreparedListener(listener: () -> Unit)   { onPreparedListener = listener }
    override fun setOnCompletionListener(listener: () -> Unit) { onCompletionListener = listener }
    override fun setOnErrorListener(listener: (Int, Int) -> Unit) { onErrorListener = listener }

    // ─── IAudioEngine: State ─────────────────────────────────────────────────

    override fun isPlaying()          = decoder.isPlaying()
    override fun getCurrentPosition() = decoder.getCurrentPosition()
    override fun getDuration()        = decoder.getDuration()
    override fun getAudioSessionId()  = 0
    override fun setNextEngine(nextEngine: IAudioEngine?) { /* gapless handled at MusicService level */ }
    override fun setPlaybackSpeed(speed: Float) { decoder.setPlaybackSpeed(speed) }
    override fun getActualSampleRate()   = currentRate
    override fun getActualChannelCount() = currentCh
    override fun probeSampleRates(deviceId: Int) = decoder.probeSampleRates(deviceId)
    override fun setDeviceId(deviceId: Int) { /* USB path: device is fixed at construction */ }
    override fun getResamplerName(): String = "None (Direct USB Bypass)"
    fun getDecoderName(): String = decoder.getDecoderName()
    fun getDacBitDepth(): Int = currentDepth
    override fun setResampleEngine(enabled: Boolean) {
        decoder.setResampleEngine(enabled)
    }

    // ─── Public: Track-change reconfiguration ────────────────────────────────

    /**
     * Called by MusicService or internally on native restart request.
     * Full USB re-negotiation: stop → release → re-parse → re-claim → restart.
     */
    suspend fun reconfigureForFormat(newRate: Int, newDepth: Int, newCh: Int, force: Boolean = false) {
        if (!force && newRate == currentRate && newDepth == currentDepth && newCh == currentCh) return
        Log.d(TAG, "[USB] Reconfiguring: force=$force, ${currentRate}→${newRate}Hz")

        streamer?.stop()
        config?.let { connection.releaseInterface(it.audioStreamingInterface) }

        setupUsbForFormat(newRate, newDepth, newCh)
    }

    // Returns true if setup successful
    private suspend fun setupUsbForFormat(rate: Int, depth: Int, ch: Int): Boolean {
        val quirk = DeviceQuirkManager.getQuirk(usbDevice.vendorId, usbDevice.productId)

        // If quirk forces a specific alt setting, adjust depth to match that setting's format
        val effectiveDepth = when (quirk.forceAltSetting) {
            1 -> 16   // Alt=1 on JA11 = PCM16
            2 -> 24   // Alt=2 = PCM24_PACKED
            3 -> 32   // Alt=3 = PCM32
            else -> depth
        }

        val parsed = UsbDescriptorParser.parse(connection, usbDevice, rate, effectiveDepth, ch)
        if (parsed == null) {
            Log.e(TAG, "[USB] Descriptor parse failed (Unsupported sample rate/depth combination)")
            onErrorListener?.invoke(-1, 0) // -1 = Parse failure / Unsupported
            return false
        }
        config = parsed

        // 🔧 FIX: Claim Audio Control interface (0) FIRST to detach kernel's
        // snd-usb-audio driver. Without this, SET_CUR fails because the kernel
        // still owns interface 0 and intercepts class-specific control requests.
        val acInterface = usbDevice.getInterface(0)  // Audio Control interface
        if (acInterface != null) {
            val acClaimed = connection.claimInterface(acInterface, true)
            Log.d(TAG, "[USB] Claim AudioControl iface 0: ${if (acClaimed) "OK" else "FAILED"}")
        }

        // Claim Audio Streaming interface
        val claimed = connection.claimInterface(parsed.audioStreamingInterface, true)
        if (!claimed) {
            Log.e(TAG, "[USB] claimInterface failed for streaming iface ${parsed.audioStreamingInterface.id}")
            onErrorListener?.invoke(-2, 0) // -2 = Resource Busy / Claim Failure
            return false
        }
        Log.d(TAG, "[USB] Claim AudioStreaming iface ${parsed.audioStreamingInterface.id}: OK")
        if (quirk.delayAfterClaim > 0) delay(quirk.delayAfterClaim)

        // SET_INTERFACE: Two-step approach for ISO compatibility
        // Step 1: connection.setInterface() → triggers kernel USBDEVFS_SETINTERFACE ioctl
        //         This is REQUIRED for ISO endpoints — the kernel allocates bandwidth here.
        val setIfaceResult = connection.setInterface(parsed.audioStreamingInterface)
        if (!setIfaceResult) {
            Log.w(TAG, "[USB] setInterface() returned false — kernel may not have set Alt=${parsed.altSettingIndex}")
        }

        // Step 2: Explicit USB control transfer as supplement (ensures device sees the request)
        val setIfCtrl = connection.controlTransfer(
            0x01,   // USB_DIR_OUT | USB_TYPE_STANDARD | USB_RECIP_INTERFACE
            0x0B,   // SET_INTERFACE request
            parsed.altSettingIndex,                 // wValue = alternate setting (e.g., 3)
            parsed.audioStreamingInterface.id,      // wIndex = interface number
            null, 0, 500
        )
        if (setIfCtrl >= 0) {
            Log.d(TAG, "[USB] SET_INTERFACE control transfer OK → Alt=${parsed.altSettingIndex}")
        } else {
            Log.w(TAG, "[USB] SET_INTERFACE control transfer failed ($setIfCtrl) — relying on setInterface()")
        }
        if (quirk.delayAfterSetInterface > 0) delay(quirk.delayAfterSetInterface)

        // SET_CUR sample rate
        if (!quirk.skipSetCur) {
            programSampleRate(parsed)
        } else {
            Log.d(TAG, "[USB] Skipping SET_CUR per device quirk")
        }

        // Hardware volume control via Feature Unit
        val fu = parsed.featureUnit
        if (fu != null && fu.hasVolumeControl) {
            val vc = UsbVolumeControl(connection, fu, parsed.uacVersion)
            val range = vc.queryVolumeRange()
            if (range != null) {
                volumeControl = vc
                NativeHiResEngineUsbBridge.setHwVolumeActive(true)
                Log.d(TAG, "[USB] HW volume enabled — software gain bypassed")
            } else {
                volumeControl = null
                NativeHiResEngineUsbBridge.setHwVolumeActive(false)
                Log.d(TAG, "[USB] FU range query failed — using software volume")
            }
        } else {
            volumeControl = null
            NativeHiResEngineUsbBridge.setHwVolumeActive(false)
            Log.d(TAG, "[USB] No Feature Unit with volume — using software volume")
        }

        // Tell native decoder exact output format
        val chunkMs = quirk.forceChunkMs.takeIf { it > 0 }
            ?: if (parsed.isIsoEndpoint) 1 else 4

        NativeHiResEngineUsbBridge.setUsbOutputFormat(
            parsed.nativeSampleRate,
            parsed.nativeBitDepth,
            parsed.nativeChannelCount,
            parsed.bitFormat.ordinal
        )

        currentRate  = parsed.nativeSampleRate
        currentDepth = parsed.nativeBitDepth
        currentCh    = parsed.nativeChannelCount

        // Build streamer
        streamer = UsbBulkStreamer(
            fd               = connection.fileDescriptor,
            endpointAddr     = parsed.outEndpoint.address,
            maxPacketSize    = parsed.maxPacketSize,
            isIsoEndpoint    = parsed.isIsoEndpoint,
            sampleRate       = parsed.nativeSampleRate,
            channelCount     = parsed.nativeChannelCount,
            bitDepth         = parsed.nativeBitDepth,
            bitFormatOrdinal = parsed.bitFormat.ordinal,
            bitPerfect       = this.bitPerfect,
            feedbackEndpointAddr = parsed.feedbackEndpointAddr,
            feedbackMaxPacketSize = parsed.feedbackMaxPacketSize,
            streamingInterface = parsed.audioStreamingInterface.id,
            altSetting       = parsed.altSettingIndex,
            onStreamRestart  = {
                scope.launch {
                    restartCount++
                    if (restartCount > 3) {
                        Log.e(TAG, "[USB] Persistent native failure after 3 retries — triggering fallback")
                        onErrorListener?.invoke(-3, 0) // -3 = persistent native error
                    } else {
                        Log.w(TAG, "[USB] Native restart requested (attempt $restartCount)")
                        delay(200 * restartCount.toLong()) // Exponential backoff
                        reconfigureForFormat(currentRate, currentDepth, currentCh, force = true)
                    }
                }
            },
            onPlaybackCompleted = {
                Log.d(TAG, "[USB] Playback completed — invoking listener")
                onCompletionListener?.invoke()
            }
        )
        
        if (streamer != null) {
            restartCount = 0 // Reset on successful setup
        }

        Log.d(TAG, "[USB] Setup complete — rate=$currentRate depth=$currentDepth ch=$currentCh " +
              "iso=${parsed.isIsoEndpoint} chunkMs=$chunkMs maxPkt=${parsed.maxPacketSize}")
        return true
    }

    private fun programSampleRate(cfg: UsbDescriptorParser.UsbAudioConfig) {
        val rate = cfg.nativeSampleRate
        val ifaceId = cfg.audioStreamingInterface.id

        Log.d(TAG, "[USB] programSampleRate: rate=$rate UAC=${cfg.uacVersion} " +
              "clockSourceId=${cfg.clockSourceId} terminalId=${cfg.terminalId} ifaceId=$ifaceId")

        val result = if (cfg.uacVersion == 2) {
            // UAC2: Clock Source Control SET_CUR
            val rateBytes = byteArrayOf(
                (rate and 0xFF).toByte(),
                ((rate shr 8) and 0xFF).toByte(),
                ((rate shr 16) and 0xFF).toByte(),
                ((rate shr 24) and 0xFF).toByte() // UAC2 requires 4-byte payload
            )
            // wIndex for UAC2 Clock Source: entity ID in high byte, AC interface in low byte
            // AC interface is typically 0 for most DACs
            val clockId = cfg.clockSourceId.toInt() and 0xFF
            val wIndex = (clockId shl 8) or 0  // AC interface 0
            Log.d(TAG, "[USB] UAC2 SET_CUR: bmReq=0x21 bReq=0x01 wVal=0x0100 " +
                  "wIdx=0x${wIndex.toString(16)} clockId=$clockId rate=$rate")
            connection.controlTransfer(
                0x21, 0x01, 0x0100,
                wIndex,
                rateBytes, 4, 500  // increased timeout
            )
        } else {
            // UAC1: Endpoint/Interface frequency control
            val rateBytes = byteArrayOf(
                (rate and 0xFF).toByte(),
                ((rate shr 8) and 0xFF).toByte(),
                ((rate shr 16) and 0xFF).toByte() // UAC1 requires 3-byte payload
            )
            connection.controlTransfer(
                0x22, 0x01, 0x0100,
                ((cfg.terminalId.toInt() and 0xFF) shl 8) or ifaceId,
                rateBytes, 3, 100
            )
        }

        if (result < 0) {
            Log.w(TAG, "[USB] SET_CUR failed (UAC${cfg.uacVersion}, result=$result) — DAC may auto-configure")
        } else {
            Log.d(TAG, "[USB] UAC${cfg.uacVersion} SET_CUR → ${rate}Hz OK (transferred $result bytes)")
        }
    }

    private suspend fun waitForPreBuffer() {
        val targetFrames = (currentRate / 1000) * PRE_BUFFER_MS
        val startTime = System.currentTimeMillis()
        
        Log.d(TAG, "[USB] Pre-buffering ${PRE_BUFFER_MS}ms ($targetFrames frames)...")
        
        // Task 3.3: Hard gate with 2s safety timeout
        while (NativeHiResEngineUsbBridge.getRingBufferFillFrames() < targetFrames) {
            if (System.currentTimeMillis() - startTime > PRE_BUFFER_TIMEOUT_MS) {
                Log.w(TAG, "[USB] Pre-buffer timeout — proceeding with partial buffer")
                break
            }
            delay(10)
        }
        Log.d(TAG, "[USB] Pre-buffer logic complete → starting stream")
    }

    private fun releaseUsbResources() {
        try {
            config?.let { connection.releaseInterface(it.audioStreamingInterface) }
        } catch (e: Exception) {
            Log.w(TAG, "releaseInterface error: ${e.message}")
        }
    }

    companion object {
        private const val PRE_BUFFER_MS = 500
        private const val PRE_BUFFER_TIMEOUT_MS = 2000L
    }
}
