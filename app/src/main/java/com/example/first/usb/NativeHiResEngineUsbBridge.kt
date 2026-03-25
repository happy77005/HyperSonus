package com.example.first.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint

/**
 * JNI bridge to the native USB streaming functions in native-lib.cpp.
 *
 * All functions are `@JvmStatic` on an `object` so they can be called
 * directly as `NativeHiResEngineUsbBridge.startUsbStreamer(…)` from Kotlin
 * or as static methods from Java.
 *
 * The corresponding C symbols live in native-lib.cpp:
 *   Java_com_example_first_usb_NativeHiResEngineUsbBridge_*
 */
object NativeHiResEngineUsbBridge {

    /**
     * Start the native real-time USB write loop using URBs (usbfs).
     *
     * @param fd             Raw file descriptor from UsbDeviceConnection.
     * @param endpointAddr   Target bulk/iso OUT endpoint address.
     * @param maxPacketSize  wMaxPacketSize of the endpoint.
     * @param isIso          true → isochronous emulation (1ms chunks); false → bulk (4ms chunks)
     * @param sampleRate     DAC sample rate (Hz)
     * @param channels       Number of audio channels
     * @param bitDepth       Bit depth (16/24/32)
     * @param bitFormatOrd   BitFormat ordinal: 0=PCM16, 1=PCM24_PACKED, 2=PCM24_IN32, 3=PCM32
     */
    @JvmStatic
    external fun startUsbStreamer(
        fd: Int,
        endpointAddr: Int,
        maxPacketSize: Int,
        isIso: Boolean,
        chunkMs: Int,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
        bitFormatOrd: Int,
        bitPerfect: Boolean,
        streamer: Any, // Passing UsbBulkStreamer instance for callbacks
        feedbackEndpointAddr: Int,
        feedbackMaxPacketSize: Int,
        streamingInterface: Int,
        altSetting: Int
    )

    /** Stop and destroy the native USB write loop, release global refs. */
    @JvmStatic
    external fun stopUsbStreamer()

    /**
     * Pause or resume the native stream without stopping it.
     * While paused, silence frames are sent to keep the DAC clocked.
     * On resume, a 20ms linear gain ramp is applied to avoid clicks.
     */
    @JvmStatic
    external fun setUsbPaused(paused: Boolean)

    @JvmStatic
    external fun setUsbVolume(volume: Float)

    @JvmStatic
    external fun setHwVolumeActive(active: Boolean)

    /**
     * Query how many PCM frames are currently available in the native ring buffer.
     * Used by [UsbBulkEngine] to wait for 50ms of pre-buffering before streaming.
     */
    @JvmStatic
    external fun getRingBufferFillFrames(): Int

    /**
     * Tell FFmpeg decoder to output at the DAC's exact format.
     * Must be called before [startUsbStreamer] so the decoder is configured
     * for the correct sample rate / channel count.
     *
     * @param bitFormatOrd  matches BitFormat ordinal in UsbDescriptorParser
     */
    @JvmStatic
    external fun setUsbOutputFormat(
        sampleRate: Int,
        bitDepth: Int,
        channels: Int,
        bitFormatOrd: Int
    )

    /**
     * Enable or disable USB-exclusive mode in the native AudioEngine.
     * When enabled, Oboe is not opened and the ring buffer is consumed
     * solely by [UsbNativeStreamer].
     */
    @JvmStatic
    external fun setUsbOutputMode(enabled: Boolean)
}
