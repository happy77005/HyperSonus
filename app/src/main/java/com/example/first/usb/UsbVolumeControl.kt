package com.example.first.usb

import android.hardware.usb.UsbDeviceConnection
import android.util.Log

/**
 * USB Audio Class hardware volume control via Feature Unit SET_CUR/GET_CUR.
 *
 * Sends control transfers directly to the DAC's Feature Unit to adjust
 * the hardware volume attenuator, preserving full PCM bit depth.
 */
class UsbVolumeControl(
    private val connection: UsbDeviceConnection,
    private val featureUnit: UsbDescriptorParser.FeatureUnitInfo,
    private val uacVersion: Int,
    private val acInterfaceNumber: Int = 0  // Audio Control interface number
) {
    companion object {
        private const val TAG = "MusicService"

        // USB Audio Class control selectors
        private const val VOLUME_CONTROL = 0x02  // FU_VOLUME_CONTROL (same for UAC1 & UAC2)

        // Request types
        private const val SET_CUR = 0x01
        private const val GET_CUR = 0x81
        private const val GET_MIN = 0x82
        private const val GET_MAX = 0x83
        private const val GET_RES = 0x84

        // UAC2 uses RANGE request instead of individual GET_MIN/MAX/RES
        private const val UAC2_RANGE = 0x02
        private const val UAC2_CUR   = 0x01
    }

    data class VolumeRange(
        val minDb: Float,   // e.g., -127.9961 dB
        val maxDb: Float,   // e.g., 0.0 dB
        val resDb: Float    // e.g., 0.00390625 dB (1/256)
    )

    private var volumeRange: VolumeRange? = null

    /**
     * Query the DAC's volume range. Call once after connecting.
     * Returns null if the DAC doesn't respond to volume queries.
     */
    fun queryVolumeRange(): VolumeRange? {
        volumeRange = if (uacVersion == 2) queryRangeUac2() else queryRangeUac1()
        if (volumeRange != null) {
            Log.d(TAG, "[USB] HW VolumeRange: min=${volumeRange!!.minDb}dB " +
                  "max=${volumeRange!!.maxDb}dB res=${volumeRange!!.resDb}dB")
        } else {
            Log.w(TAG, "[USB] Failed to query HW volume range")
        }
        return volumeRange
    }

    /**
     * Set hardware volume.
     * @param linearVolume 0.0 (silent) to 1.0 (max)
     * @return true if control transfer succeeded
     */
    fun setVolume(linearVolume: Float): Boolean {
        val range = volumeRange ?: return false
        val clamped = linearVolume.coerceIn(0f, 1f)

        // Squared curve for perceptual volume (matches existing software curve)
        val curved = clamped * clamped

        // Map to dB range
        val targetDb = if (curved <= 0.001f) {
            range.minDb  // treat near-zero as minimum (often -inf / mute)
        } else {
            range.minDb + curved * (range.maxDb - range.minDb)
        }

        // Convert dB to USB format: int16 in 1/256 dB units
        val rawValue = (targetDb * 256f).toInt().toShort()

        return setVolumeRaw(rawValue)
    }

    /**
     * Query current hardware volume as linear 0.0–1.0.
     */
    fun getCurrentVolume(): Float {
        val range = volumeRange ?: return 1.0f
        val raw = getCurrentRaw() ?: return 1.0f
        val db = raw.toFloat() / 256f
        val span = range.maxDb - range.minDb
        if (span <= 0f) return 1.0f
        val curved = ((db - range.minDb) / span).coerceIn(0f, 1f)
        // Inverse of squared curve
        return Math.sqrt(curved.toDouble()).toFloat()
    }

    // ─── Private control transfer helpers ─────────────────────────────────

    private fun setVolumeRaw(value: Short): Boolean {
        val data = byteArrayOf(
            (value.toInt() and 0xFF).toByte(),
            ((value.toInt() shr 8) and 0xFF).toByte()
        )
        val wIndex = (featureUnit.unitId shl 8) or acInterfaceNumber
        val bReq = if (uacVersion == 2) UAC2_CUR else SET_CUR
        var anyOk = false

        // Send to channels 1 & 2 (per-channel volume — most DACs need this)
        for (ch in 1..2) {
            val wValue = (VOLUME_CONTROL shl 8) or ch
            val r = connection.controlTransfer(0x21, bReq, wValue, wIndex, data, 2, 500)
            if (r >= 0) anyOk = true
        }

        // Also try master channel 0 (some DACs support it)
        val wValueMaster = (VOLUME_CONTROL shl 8) or 0x00
        val rMaster = connection.controlTransfer(0x21, bReq, wValueMaster, wIndex, data, 2, 500)
        if (rMaster >= 0) anyOk = true

        if (!anyOk) {
            Log.w(TAG, "[USB] SET_CUR volume failed on all channels (unit=${featureUnit.unitId})")
        }
        return anyOk
    }

    private fun getCurrentRaw(): Short? {
        val data = ByteArray(2)
        val wValue = (VOLUME_CONTROL shl 8) or 0x00
        val wIndex = (featureUnit.unitId shl 8) or acInterfaceNumber

        val result = if (uacVersion == 2) {
            connection.controlTransfer(0xA1, UAC2_CUR, wValue, wIndex, data, 2, 500)
        } else {
            connection.controlTransfer(0xA1, GET_CUR, wValue, wIndex, data, 2, 500)
        }

        if (result < 0) return null
        return ((data[1].toInt() shl 8) or (data[0].toInt() and 0xFF)).toShort()
    }

    private fun queryRangeUac1(): VolumeRange? {
        val wIndex = (featureUnit.unitId shl 8) or acInterfaceNumber
        val buf = ByteArray(2)

        // Query channel 1 first (many DACs only have volume on per-channel, not master)
        for (ch in intArrayOf(1, 0)) {
            val wValue = (VOLUME_CONTROL shl 8) or ch

            var r = connection.controlTransfer(0xA1, GET_MIN, wValue, wIndex, buf, 2, 500)
            if (r < 0) continue
            val minRaw = ((buf[1].toInt() shl 8) or (buf[0].toInt() and 0xFF)).toShort()

            r = connection.controlTransfer(0xA1, GET_MAX, wValue, wIndex, buf, 2, 500)
            if (r < 0) continue
            val maxRaw = ((buf[1].toInt() shl 8) or (buf[0].toInt() and 0xFF)).toShort()

            r = connection.controlTransfer(0xA1, GET_RES, wValue, wIndex, buf, 2, 500)
            val resRaw = if (r >= 0) {
                ((buf[1].toInt() shl 8) or (buf[0].toInt() and 0xFF)).toShort()
            } else 1.toShort()

            Log.d(TAG, "[USB] UAC1 RANGE ch=$ch: min=$minRaw max=$maxRaw res=$resRaw")
            return VolumeRange(
                minDb = minRaw.toFloat() / 256f,
                maxDb = maxRaw.toFloat() / 256f,
                resDb = resRaw.toFloat() / 256f
            )
        }
        return null
    }

    private fun queryRangeUac2(): VolumeRange? {
        val wIndex = (featureUnit.unitId shl 8) or acInterfaceNumber
        val buf = ByteArray(8)

        // Query channel 1 first (many DACs only have volume on per-channel, not master)
        for (ch in intArrayOf(1, 0)) {
            val wValue = (VOLUME_CONTROL shl 8) or ch
            val r = connection.controlTransfer(0xA1, UAC2_RANGE, wValue, wIndex, buf, 8, 500)
            if (r < 8) continue

            val numSubRanges = (buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8)
            if (numSubRanges < 1) continue

            val minRaw = ((buf[3].toInt() shl 8) or (buf[2].toInt() and 0xFF)).toShort()
            val maxRaw = ((buf[5].toInt() shl 8) or (buf[4].toInt() and 0xFF)).toShort()
            val resRaw = ((buf[7].toInt() shl 8) or (buf[6].toInt() and 0xFF)).toShort()

            Log.d(TAG, "[USB] UAC2 RANGE ch=$ch: min=$minRaw max=$maxRaw res=$resRaw")
            return VolumeRange(
                minDb = minRaw.toFloat() / 256f,
                maxDb = maxRaw.toFloat() / 256f,
                resDb = if (resRaw.toInt() != 0) resRaw.toFloat() / 256f else (1f / 256f)
            )
        }
        Log.w(TAG, "[USB] UAC2 RANGE query failed on all channels")
        return null
    }
}
