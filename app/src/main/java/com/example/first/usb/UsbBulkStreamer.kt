package com.example.first.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.Log

/**
 * Kotlin lifecycle shim for the native USB streaming loop.
 *
 * The actual real-time audio write loop runs entirely in C++ (UsbNativeStreamer).
 * This class handles only lifecycle: start / stop / pause / resume.
 * It also bridges the stream-restart callback from native → UsbBulkEngine.
 *
 * JNI method IDs and JNIEnv* are cached inside the native thread for zero-overhead
 * per-transfer use.
 */
class UsbBulkStreamer(
    private val fd: Int,
    private val endpointAddr: Int,
    private val maxPacketSize: Int,
    private val isIsoEndpoint: Boolean,
    private val sampleRate: Int,
    private val channelCount: Int,
    private val bitDepth: Int,
    private val bitFormatOrdinal: Int,   // UsbDescriptorParser.BitFormat.ordinal
    private val bitPerfect: Boolean,
    private val feedbackEndpointAddr: Int,
    private val feedbackMaxPacketSize: Int,
    private val streamingInterface: Int,
    private val altSetting: Int,
    private val onStreamRestart: () -> Unit,
    private val onPlaybackCompleted: () -> Unit
) {
    private val TAG = "UsbBulkStreamer"
    private var isRunning = false

    /** Start the native RT streaming loop. Must be called after pre-buffer. */
    fun start(chunkMs: Int = 0) {
        if (isRunning) return
        val finalChunkMs = if (chunkMs > 0) chunkMs else (if (isIsoEndpoint) 1 else 4)
        Log.d(TAG, "[USB] Starting native streamer: " +
              "${sampleRate}Hz, ${bitDepth}bit, ${channelCount}ch, " +
              "iso=$isIsoEndpoint, chunkMs=$finalChunkMs, maxPkt=$maxPacketSize")
        isRunning = true
        NativeHiResEngineUsbBridge.startUsbStreamer(
            fd             = fd,
            endpointAddr   = endpointAddr,
            maxPacketSize  = maxPacketSize,
            isIso          = isIsoEndpoint,
            chunkMs        = finalChunkMs,
            sampleRate     = sampleRate,
            channels       = channelCount,
            bitDepth       = bitDepth,
            bitFormatOrd   = bitFormatOrdinal,
            bitPerfect     = bitPerfect,
            streamer       = this,
            feedbackEndpointAddr = feedbackEndpointAddr,
            feedbackMaxPacketSize = feedbackMaxPacketSize,
            streamingInterface = streamingInterface,
            altSetting = altSetting
        )
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        NativeHiResEngineUsbBridge.stopUsbStreamer()
        Log.d(TAG, "[USB] Native streamer stopped")
    }

    fun pause() {
        NativeHiResEngineUsbBridge.setUsbPaused(true)
        Log.d(TAG, "[USB] Paused — sending silence frames")
    }

    fun resume() {
        NativeHiResEngineUsbBridge.setUsbPaused(false)
        Log.d(TAG, "[USB] Resumed — per-sample ramp active")
    }

    val isActive get() = isRunning

    /**
     * Called from native via JNI when the streamer detects a persistent write failure
     * and needs a full stream restart (re-claim, re-negotiate, re-start).
     * Runs on native thread → posts to caller-supplied lambda.
     */
    @Suppress("unused") // called from C++ via JNI
    private fun onNativeRestartRequested() {
        Log.w(TAG, "[USB] Native restart requested — offloading to main thread")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            isRunning = false
            onStreamRestart()
        }
    }

    @Suppress("unused") // called from C++ via JNI
    private fun onNativePlaybackCompleted() {
        Log.d(TAG, "[USB] Native playback completed — notifying engine (main thread)")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            onPlaybackCompleted()
        }
    }
}


