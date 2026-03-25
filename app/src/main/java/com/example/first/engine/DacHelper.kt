package com.example.first.engine

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Helper to identify and classify the current audio output device.
 */
object DacHelper {

    enum class DacType {
        INTERNAL_DAC,
        USB_DAC,
        BLUETOOTH, // Optional, for info
        UNKNOWN
    }

    data class DacInfo(
        val type: DacType,
        val name: String,
        val id: Int,
        val sampleRates: IntArray = intArrayOf(),
        val channelCounts: IntArray = intArrayOf(),
        val probedSampleRates: IntArray = intArrayOf()
    )

    private val probedRatesCache = mutableMapOf<Int, IntArray>()
    private var bluetoothA2dp: BluetoothA2dp? = null
    private val TAG = "DacHelper"

    fun initBluetoothListener(context: Context) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter ?: return
        
        adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                if (profile == BluetoothProfile.A2DP) {
                    bluetoothA2dp = proxy as BluetoothA2dp
                    Log.d(TAG, "BluetoothA2dp connected")
                }
            }
            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.A2DP) {
                    bluetoothA2dp = null
                    Log.d(TAG, "BluetoothA2dp disconnected")
                }
            }
        }, BluetoothProfile.A2DP)
    }

    fun getBluetoothCodecInfo(context: Context): String? {
        val a2dp = bluetoothA2dp ?: return null
        
        // Permission check for Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return "Bluetooth (Permission Required)"
            }
        }

        try {
            val devices = a2dp.connectedDevices
            if (devices.isEmpty()) return null
            
            val device = devices[0]
            
            // Use reflection for codecStatus
            val getCodecStatusMethod = a2dp.javaClass.getMethod("getCodecStatus", android.bluetooth.BluetoothDevice::class.java)
            val codecStatus = getCodecStatusMethod.invoke(a2dp, device) ?: return null
            
            val getCodecConfigMethod = codecStatus.javaClass.getMethod("getCodecConfig")
            val codecConfig = getCodecConfigMethod.invoke(codecStatus) ?: return null
            
            val codecType = codecConfig.javaClass.getMethod("getCodecType").invoke(codecConfig) as Int
            val sampleRate = codecConfig.javaClass.getMethod("getSampleRate").invoke(codecConfig) as Int
            val bitsPerSample = codecConfig.javaClass.getMethod("getBitsPerSample").invoke(codecConfig) as Int
            
            val codecName = when (codecType) {
                0 -> "SBC"
                1 -> "AAC"
                2 -> "aptX"
                3 -> "aptX HD"
                4 -> "LDAC"
                else -> "Unknown ($codecType)"
            }
            
            val rateStr = when (sampleRate) {
                1 shl 0 -> "44.1 kHz"
                1 shl 1 -> "48.0 kHz"
                1 shl 2 -> "88.2 kHz"
                1 shl 3 -> "96.0 kHz"
                1 shl 4 -> "176.4 kHz"
                1 shl 5 -> "192.0 kHz"
                else -> "Auto"
            }
            
            val bitsStr = when (bitsPerSample) {
                1 shl 0 -> "16-bit"
                1 shl 1 -> "24-bit"
                1 shl 2 -> "32-bit"
                else -> "Auto"
            }
            
            return "$codecName • $rateStr • $bitsStr"
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: Missing BLUETOOTH_CONNECT permission")
            return "Bluetooth (No Permission)"
        } catch (e: Exception) {
            Log.e(TAG, "Error getting bluetooth codec info: ${e.message}")
            return "Bluetooth Connected"
        }
    }

    fun setProbedRates(deviceId: Int, rates: IntArray) {
        probedRatesCache[deviceId] = rates
    }

    fun getProbedRates(deviceId: Int): IntArray {
        return probedRatesCache[deviceId] ?: intArrayOf()
    }

    fun getCurrentDacInfo(context: Context): DacInfo {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        // Find the active device (this is simplified, usually the last one in the list or filtered by platform heuristics)
        // For Android, the "active" device is often the one currently routed, 
        // but Oboe/AAudio allows choosing specifically.
        
        // Priority: USB > Bluetooth > Internal
        val usbDevice = devices.find { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY }
        if (usbDevice != null) {
            return DacInfo(
                type = DacType.USB_DAC,
                name = usbDevice.productName.toString().ifEmpty { "USB DAC" },
                id = usbDevice.id,
                sampleRates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) usbDevice.sampleRates else intArrayOf(),
                channelCounts = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) usbDevice.channelCounts else intArrayOf(),
                probedSampleRates = getProbedRates(usbDevice.id)
            )
        }

        val bluetoothDevice = devices.find { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        if (bluetoothDevice != null) {
            return DacInfo(
                type = DacType.BLUETOOTH,
                name = bluetoothDevice.productName.toString().ifEmpty { "Bluetooth Device" },
                id = bluetoothDevice.id,
                sampleRates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) bluetoothDevice.sampleRates else intArrayOf(),
                probedSampleRates = getProbedRates(bluetoothDevice.id)
            )
        }

        val internalDevice = devices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
        if (internalDevice != null) {
            return DacInfo(
                type = DacType.INTERNAL_DAC,
                name = if (internalDevice.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) "Internal Speaker" else "Wired Headset",
                id = internalDevice.id,
                sampleRates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) internalDevice.sampleRates else intArrayOf(),
                probedSampleRates = getProbedRates(internalDevice.id)
            )
        }

        return DacInfo(DacType.UNKNOWN, "Unknown Device", -1)
    }
}
