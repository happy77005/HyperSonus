package com.example.first.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses raw USB descriptors to extract USB Audio Class streaming configuration.
 *
 * Supports UAC1 (Audio Class 1.0) and UAC2 (Audio Class 2.0).
 * Selects best alternate setting based on format priority and bandwidth.
 */
object UsbDescriptorParser {

    private const val TAG = "MusicService" // 🔧 Temporarily use MusicService to bypass logcat *:S filter

    // USB Audio Class constants
    private const val USB_CLASS_AUDIO = 1
    private const val USB_SUBCLASS_AUDIOCONTROL = 1
    private const val USB_SUBCLASS_AUDIOSTREAMING = 2
    
    private const val CS_INTERFACE = 0x24
    private const val HEADER = 0x01
    private const val AS_GENERAL = 0x01
    private const val FORMAT_TYPE = 0x02
    private const val OUTPUT_TERMINAL = 0x03
    private const val FEATURE_UNIT = 0x06
    private const val CLOCK_SOURCE = 0x0A
    private const val UAC_FORMAT_TYPE_I = 0x01

    enum class BitFormat(val bytesPerSample: Int, val priority: Int) {
        FLOAT(4, 100),       // Best: highest precision
        PCM24_IN32(4, 80),   // High: 24-bit in 32-bit slot
        PCM32(4, 70),        // High: 32-bit PCM
        PCM24_PACKED(3, 60), // Mid: 24-bit packed (bandwidth efficient)
        PCM16(2, 40)         // Low: standard 16-bit
    }

    enum class SyncType { NONE, ASYNC, ADAPTIVE, SYNC }

    data class FeatureUnitInfo(
        val unitId: Int,              // bUnitID — target for SET_CUR volume
        val sourceId: Int,            // bSourceID
        val hasVolumeControl: Boolean // true if bmaControls has volume bit
    )

    data class UsbAudioConfig(
        val audioStreamingInterface: UsbInterface,
        val altSettingIndex: Int,
        val outEndpoint: UsbEndpoint,
        val isIsoEndpoint: Boolean,
        val maxPacketSize: Int,
        val uacVersion: Int,
        val nativeSampleRate: Int,
        val nativeBitDepth: Int,
        val nativeChannelCount: Int,
        val bitFormat: BitFormat,
        val terminalId: Byte,
        val clockSourceId: Byte,
        val featureUnit: FeatureUnitInfo?,   // null = no FU, fallback to SW volume
        val syncType: SyncType,              // NEW: from bmAttributes bits 2-3
        val feedbackEndpointAddr: Int,       // NEW: -1 if none
        val feedbackMaxPacketSize: Int = 0   // NEW: [v12.2] for dynamic URB length
    )

    fun parse(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        preferredRate: Int = 48000,
        preferredDepth: Int = 0,
        preferredCh: Int = 0
    ): UsbAudioConfig? {
        val raw = connection.rawDescriptors ?: return null

        Log.d(TAG, "[USB] Parsing descriptors (${raw.size} bytes)")

        val candidates = mutableListOf<CandidateConfig>()
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)

        var currentIfaceClass = -1
        var currentIfaceSubClass = -1
        var currentIfaceAltSetting = -1
        var currentIfaceNumber = -1
        var uacVersion = 1
        var terminalId: Byte = 0
        var clockSourceId: Byte = 0
        var featureUnitInfo: FeatureUnitInfo? = null
        
        var fmtRate = 0; var fmtDepth = 0; var fmtCh = 0; var fmtSubFrameSize = 0; var fmtTag = -1
        var pendingEndpoint: EndpointInfo? = null
        var feedbackEndpointAddr = -1
        var feedbackMaxPacketSize = 0

        while (buffer.remaining() >= 2) {
            val start = buffer.position()
            val len = buffer.get().toInt() and 0xFF
            if (len < 2 || start + len > raw.size) break
            val type = buffer.get().toInt() and 0xFF

            when (type) {
                0x04 -> { // INTERFACE
                    pendingEndpoint = flushCandidate(
                        pendingEndpoint, currentIfaceSubClass, currentIfaceAltSetting,
                        fmtRate, uacVersion, preferredRate, currentIfaceNumber,
                        fmtDepth, fmtCh, fmtSubFrameSize, fmtTag, terminalId, clockSourceId, 
                        candidates, feedbackEndpointAddr, feedbackMaxPacketSize
                    )
                    fmtRate = 0; fmtDepth = 0; fmtCh = 0; fmtSubFrameSize = 0; fmtTag = -1
                    feedbackEndpointAddr = -1
                    feedbackMaxPacketSize = 0

                    if (len >= 9) {
                        currentIfaceNumber    = buffer.get().toInt() and 0xFF
                        currentIfaceAltSetting = buffer.get().toInt() and 0xFF
                        buffer.get() // bNumEndpoints
                        currentIfaceClass     = buffer.get().toInt() and 0xFF
                        currentIfaceSubClass  = buffer.get().toInt() and 0xFF
                        val protoVer          = buffer.get().toInt() and 0xFF
                        if (currentIfaceClass == USB_CLASS_AUDIO) {
                            uacVersion = if (protoVer == 0x20) 2 else 1
                        }
                    }
                }

                0x05 -> { // ENDPOINT
                    if (len >= 7 && currentIfaceSubClass == USB_SUBCLASS_AUDIOSTREAMING) {
                        val addr = buffer.get().toInt() and 0xFF
                        val attr = buffer.get().toInt() and 0xFF
                        
                        // Fix for High-Speed: bits 12-11 are additional transactions
                        val rawMaxPkt = buffer.short.toInt() and 0xFFFF
                        val maxPkt = (rawMaxPkt and 0x7FF) * (1 + ((rawMaxPkt shr 11) and 3))
                        
                        val direction = addr and 0x80
                        val transferType = attr and 0x03
                        val syncBits = (attr shr 2) and 0x03
                        val usageBits = (attr shr 4) and 0x03 // bits 4-5
                        val syncType = when(syncBits) {
                            0x01 -> SyncType.ASYNC
                            0x02 -> SyncType.ADAPTIVE
                            0x03 -> SyncType.SYNC
                            else -> SyncType.NONE
                        }
                        
                        if (direction == 0x00 && (transferType == 1 || transferType == 2)) {
                            pendingEndpoint = EndpointInfo(addr, transferType == 1, maxPkt, syncType)
                        } else if (direction == 0x80 && transferType == 1 && usageBits == 0x01) {
                            // 0x01 = Explicit Feedback [v12.2 Fix]
                            feedbackEndpointAddr = addr
                            feedbackMaxPacketSize = maxPkt
                            Log.d(TAG, "[USB] Explicit Feedback EP found: 0x${addr.toString(16)} size=$maxPkt")
                        } else {
                            Log.d(TAG, "[USB] Skipping EP 0x${addr.toString(16)}: dir=$direction xfer=$transferType sync=$syncBits usage=$usageBits")
                        }
                    }
                }

                CS_INTERFACE -> {
                    val subType = if (len >= 3) buffer.get().toInt() and 0xFF else -1
                    when {
                        subType == HEADER && currentIfaceSubClass == USB_SUBCLASS_AUDIOCONTROL -> {
                            if (len >= 6) {
                                val bcd = buffer.short.toInt() and 0xFFFF
                                if (bcd >= 0x0200) uacVersion = 2
                            }
                        }
                        subType == OUTPUT_TERMINAL && currentIfaceSubClass == USB_SUBCLASS_AUDIOCONTROL -> {
                            if (len >= 4) terminalId = buffer.get()
                        }
                        subType == CLOCK_SOURCE && currentIfaceSubClass == USB_SUBCLASS_AUDIOCONTROL && uacVersion == 2 -> {
                            if (len >= 4) clockSourceId = buffer.get()
                        }
                        subType == FEATURE_UNIT && currentIfaceSubClass == USB_SUBCLASS_AUDIOCONTROL -> {
                            // UAC1 FU layout: bLength, bDescType, bDescSubtype, bUnitID, bSourceID, bControlSize, bmaControls[0..N]
                            // UAC2 FU layout: bLength, bDescType, bDescSubtype, bUnitID, bSourceID, bmaControls[0..N] (4 bytes each)
                            if (len >= 6) {
                                val fuId = buffer.get().toInt() and 0xFF
                                val fuSource = buffer.get().toInt() and 0xFF
                                var hasVolume = false
                                
                                if (uacVersion == 1) {
                                    val controlSize = buffer.get().toInt() and 0xFF
                                    if (controlSize > 0) {
                                        // Metadata: bLength, bType, bSubtype, bId, bSrc, bCtrlSize = 6 bytes
                                        val numFields = (len - 6) / controlSize
                                        for (i in 0 until numFields) {
                                            if (buffer.remaining() < 1) break
                                            val controls = buffer.get().toInt() and 0xFF
                                            if ((controls and 0x02) != 0) hasVolume = true
                                            if (controlSize > 1 && buffer.remaining() >= controlSize - 1) {
                                                repeat(controlSize - 1) { buffer.get() }
                                            }
                                        }
                                    }
                                } else {
                                    // UAC2 metadata: bLength, bType, bSubtype, bId, bSrc = 5 bytes
                                    val numFields = (len - 5) / 4
                                    for (i in 0 until numFields) {
                                        if (buffer.remaining() < 4) break
                                        val bma = buffer.int
                                        Log.d(TAG, "[USB] FU UAC2 id=$fuId field=$i bma=0x%08X".format(bma))
                                        if ((bma and 0x0C) != 0) hasVolume = true
                                    }
                                }
                                
                                if (hasVolume) {
                                    featureUnitInfo = FeatureUnitInfo(fuId, fuSource, hasVolume)
                                    Log.d(TAG, "[USB] Feature Unit Found: id=$fuId hasVolume=true")
                                }
                            }
                        }
                        subType == AS_GENERAL && currentIfaceSubClass == USB_SUBCLASS_AUDIOSTREAMING -> {
                            if (uacVersion == 2 && len >= 11) {
                                buffer.position(start + 7) 
                                buffer.get().toInt() and 0xFF // skip bmFormats low byte
                                val formats = buffer.get().toInt() and 0xFF // formats high byte/tag?
                                fmtCh = buffer.get().toInt() and 0xFF
                            }
                        }
                        subType == FORMAT_TYPE && currentIfaceSubClass == USB_SUBCLASS_AUDIOSTREAMING -> {
                            fmtTag = buffer.get().toInt() and 0xFF
                            if (fmtTag == UAC_FORMAT_TYPE_I || (uacVersion == 2 && fmtTag == 0x01)) { // 0x01 is Type I in UAC2 too
                                if (uacVersion == 1) {
                                    fmtCh = buffer.get().toInt() and 0xFF
                                    fmtSubFrameSize = buffer.get().toInt() and 0xFF
                                    fmtDepth = buffer.get().toInt() and 0xFF
                                    val nRates = buffer.get().toInt() and 0xFF
                                    if (nRates > 0 && len >= 9 + nRates * 3) {
                                        var best = 0
                                        for (r in 0 until nRates) {
                                            val rate = read24(buffer)
                                            if (rate == preferredRate) { best = rate; break }
                                            if (rate > best) best = rate
                                        }
                                        fmtRate = best
                                    }
                                } else {
                                    fmtSubFrameSize = buffer.get().toInt() and 0xFF
                                    fmtDepth = buffer.get().toInt() and 0xFF
                                    Log.d(TAG, "[USB] UAC2 FORMAT_TYPE: subFrameSize=$fmtSubFrameSize bitDepth=$fmtDepth")
                                }
                            }
                        }
                    }
                }
            }
            buffer.position(start + len)
        }

        flushCandidate(
            pendingEndpoint, currentIfaceSubClass, currentIfaceAltSetting,
            fmtRate, uacVersion, preferredRate, currentIfaceNumber,
            fmtDepth, fmtCh, fmtSubFrameSize, fmtTag, terminalId, clockSourceId, 
            candidates, feedbackEndpointAddr, feedbackMaxPacketSize
        )

        if (candidates.isEmpty()) return null

        val best = candidates.maxWithOrNull { a, b ->
            // If a specific depth is preferred (e.g. from forceAltSetting quirk), prioritize exact match
            if (preferredDepth > 0) {
                val depthMatchA = if (a.bitDepth == preferredDepth) 1000 else 0
                val depthMatchB = if (b.bitDepth == preferredDepth) 1000 else 0
                if (depthMatchA != depthMatchB) return@maxWithOrNull depthMatchA - depthMatchB
            }

            val fmtRel = a.format.priority - b.format.priority
            if (fmtRel != 0) return@maxWithOrNull fmtRel
            
            val rateMatchA = if (a.sampleRate == preferredRate) 100 else 0
            val rateMatchB = if (b.sampleRate == preferredRate) 100 else 0
            if (rateMatchA != rateMatchB) return@maxWithOrNull rateMatchA - rateMatchB
            
            // v12: For UAC2 Async, prefer ISO + Feedback
            val isoPrioA = when {
                a.uacVersion == 2 && a.syncType == SyncType.ASYNC && a.isIso -> 200
                !a.isIso -> 100  // Bulk priority
                else -> 0
            }
            val isoPrioB = when {
                b.uacVersion == 2 && b.syncType == SyncType.ASYNC && b.isIso -> 200
                !b.isIso -> 100
                else -> 0
            }
            (isoPrioA + a.maxPacketSize) - (isoPrioB + b.maxPacketSize)
        } ?: candidates.first()

        // 🔧 Fix 4 — DO NOT assume UAC2 always valid
        if (best.sampleRate <= 0) {
            Log.w(TAG, "[USB] Sample rate missing — using preferredRate ($preferredRate)")
        }

        val usbIface = findInterface(device, best.ifaceNumber, best.altSetting)
        val usbEp = findEndpoint(usbIface, best.endpointAddr)

        if (usbIface == null || usbEp == null) return null

        Log.d(TAG, "[USB] Selected: Alt=${best.altSetting}, UAC=${best.uacVersion}, " +
                   "fmt=${best.sampleRate}Hz/${best.format}/${best.channels}ch")

        return UsbAudioConfig(
            audioStreamingInterface = usbIface,
            altSettingIndex = best.altSetting,
            outEndpoint = usbEp,
            isIsoEndpoint = best.isIso,
            maxPacketSize = best.maxPacketSize,
            uacVersion = best.uacVersion,
            nativeSampleRate = best.sampleRate,
            nativeBitDepth = best.bitDepth,
            nativeChannelCount = best.channels,
            bitFormat = best.format,
            terminalId = best.terminalId,
            clockSourceId = best.clockSourceId,
            featureUnit = featureUnitInfo,
            syncType = best.syncType,
            feedbackEndpointAddr = best.feedbackAddr,
            feedbackMaxPacketSize = best.feedbackMaxPkt
        )
    }

    private data class CandidateConfig(
        val ifaceNumber: Int, val altSetting: Int, val endpointAddr: Int,
        val isIso: Boolean, val maxPacketSize: Int, val uacVersion: Int,
        val sampleRate: Int, val bitDepth: Int, val channels: Int,
        val subFrameSize: Int, val terminalId: Byte, val clockSourceId: Byte,
        val format: BitFormat, val syncType: SyncType,
        val feedbackAddr: Int = -1,
        val feedbackMaxPkt: Int = 0
    )

    private data class EndpointInfo(val address: Int, val isIso: Boolean, val maxPacket: Int, val syncType: SyncType)

    private fun findInterface(device: UsbDevice, ifaceNumber: Int, altSetting: Int): UsbInterface? {
        for (idx in 0 until device.interfaceCount) {
            val iface = device.getInterface(idx)
            if (iface.id == ifaceNumber && iface.alternateSetting == altSetting) return iface
        }
        for (idx in 0 until device.interfaceCount) {
            val iface = device.getInterface(idx)
            if (iface.id == ifaceNumber) return iface
        }
        return null
    }

    private fun findEndpoint(iface: UsbInterface?, targetAddr: Int): UsbEndpoint? {
        if (iface == null) return null
        for (idx in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(idx)
            if (ep.address == targetAddr) return ep
        }
        return null
    }

    private fun flushCandidate(
        ep: EndpointInfo?, subClass: Int, altSetting: Int, fmtRate: Int,
        uacVersion: Int, preferredRate: Int, ifaceNumber: Int,
        fmtDepth: Int, fmtCh: Int, fmtSubFrameSize: Int, fmtTag: Int,
        terminalId: Byte, clockSourceId: Byte,
        candidates: MutableList<CandidateConfig>,
        feedbackAddr: Int, feedbackMaxPkt: Int
    ): EndpointInfo? {
        if (ep != null && subClass == USB_SUBCLASS_AUDIOSTREAMING && altSetting > 0) {
            Log.d(TAG, "[USB] Endpoint candidate Alt=$altSetting type: ${if (ep.isIso) "ISO" else "BULK"}")
            
            // Priority 1: Check Type I (PCM) or UAC2 Dynamic
            if (fmtTag == 0x01 || (uacVersion == 2 && fmtTag != -1)) {
                 // 🔧 Fix 3 — Parser safety (sampleRate fallback)
                 val safeRate = when {
                    fmtRate > 0 -> fmtRate
                    uacVersion == 2 -> preferredRate // ⭐ KEY FIX for UAC2
                    preferredRate > 0 -> preferredRate
                    else -> 48000 // safe fallback
                 }

                 val safeDepth = when {
                    fmtDepth > 0 -> fmtDepth
                    uacVersion == 2 -> 32 // ⭐ Assume 32-bit (common UAC2 high-res DAC default)
                    else -> 16
                 }

                 val safeSubframe = when {
                    fmtSubFrameSize > 0 -> fmtSubFrameSize
                    uacVersion == 2 -> 4 // ⭐ 32-bit needs 4 byte subslots
                    else -> 2
                 }
                 
                 val format = resolveBitFormat(safeSubframe, safeDepth, uacVersion, fmtTag)

                 Log.d(TAG, "[USB] Candidate Alt=$altSetting: " +
                       "fmtDepth=$fmtDepth safeDepth=$safeDepth fmtSubFrame=$fmtSubFrameSize safeSubFrame=$safeSubframe " +
                       "format=$format rate=$safeRate ch=${if (fmtCh > 0) fmtCh else 2}")

                 candidates.add(CandidateConfig(
                    ifaceNumber = ifaceNumber, altSetting = altSetting,
                    endpointAddr = ep.address, isIso = ep.isIso, maxPacketSize = ep.maxPacket,
                    uacVersion = uacVersion, 
                    sampleRate = safeRate,
                    bitDepth = safeDepth, channels = if (fmtCh > 0) fmtCh else 2,
                    subFrameSize = safeSubframe, terminalId = terminalId,
                    clockSourceId = clockSourceId, format = format,
                    syncType = ep.syncType,
                    feedbackAddr = feedbackAddr,
                    feedbackMaxPkt = feedbackMaxPkt
                 ))
            }
        }
        return null
    }

    fun dumpUsbDescriptors(device: UsbDevice, connection: UsbDeviceConnection) {
        Log.e("USB_DUMP", "===== DUMP CALLED =====")
        val raw = connection.rawDescriptors
        if (raw == null) {
            Log.e("USB_RAW", "Raw descriptors are null!")
            return
        }
        
        Log.e("USB_RAW", "Total Length: ${raw.size}")
        Log.e("USB_RAW", "Hex: " + raw.joinToString("") { "%02X".format(it) })

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            Log.e("USB_DUMP", "Interface Index: $i, ID: ${iface.id}, Alt: ${iface.alternateSetting}, Class: ${iface.interfaceClass}, SubClass: ${iface.interfaceSubclass}")
            
            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                val addr = ep.address
                val attr = ep.attributes
                val dir = if ((addr and 0x80) != 0) "IN" else "OUT"
                val type = when (attr and 0x03) {
                    0 -> "Control"
                    1 -> "Isochronous"
                    2 -> "Bulk"
                    else -> "Interrupt"
                }
                val sync = when ((attr shr 2) and 0x03) {
                    1 -> "Async"
                    2 -> "Adaptive"
                    3 -> "Sync"
                    else -> "None"
                }
                val usage = when ((attr shr 4) and 0x03) {
                    1 -> "Feedback"
                    2 -> "Implicit Feedback"
                    else -> "Data"
                }
                
                Log.e("USB_DUMP", "  EP 0x%02X -> %s %s, sync=%s, usage=%s, maxPkt=%d, interval=%d".format(
                    addr, dir, type, sync, usage, ep.maxPacketSize, ep.interval
                ))
            }
        }
    }

    private fun read24(buffer: ByteBuffer): Int {
        val b0 = buffer.get().toInt() and 0xFF
        val b1 = buffer.get().toInt() and 0xFF
        val b2 = buffer.get().toInt() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16)
    }

    private fun resolveBitFormat(subFrameSize: Int, bitDepth: Int, uacVersion: Int, fmtTag: Int): BitFormat {
        // UAC2 tags: 0x01=PCM, 0x02=PCM8, 0x03=IEEE_FLOAT, 0x04=ALAW, 0x05=MULAW
        if (uacVersion == 2 && fmtTag == 0x03) return BitFormat.FLOAT
        
        return when {
            subFrameSize == 4 && bitDepth == 32 -> BitFormat.PCM32
            subFrameSize == 4 && bitDepth == 24 -> BitFormat.PCM24_IN32
            subFrameSize == 3 && bitDepth == 24 -> BitFormat.PCM24_PACKED
            subFrameSize == 2 -> BitFormat.PCM16
            else -> BitFormat.PCM16
        }
    }
}
