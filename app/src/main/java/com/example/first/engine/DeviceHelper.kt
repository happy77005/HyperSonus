package com.example.first.engine

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * DeviceHelper provides identification for audio output hardware.
 * 0: Internal Speaker / Wired
 * 1: USB DAC (Exclusive/Bulk)
 * 2: Bluetooth Device
 */
object DeviceHelper {

    enum class DeviceClass(val id: Int) {
        INTERNAL_SPEAKER(0),
        USB_DAC(1),
        BLUETOOTH(2),
        UNKNOWN(-1)
    }

    fun getCurrentDeviceClass(context: Context): DeviceClass {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        // Note: Priorities are USB > Bluetooth > Internal as per audiophile requirements.
        
        val usbDevice = devices.find { 
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || 
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET || 
            it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY 
        }
        if (usbDevice != null) return DeviceClass.USB_DAC

        val bluetoothDevice = devices.find { 
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || 
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO 
        }
        if (bluetoothDevice != null) return DeviceClass.BLUETOOTH

        val internalDevice = devices.find { 
            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || 
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || 
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET 
        }
        if (internalDevice != null) return DeviceClass.INTERNAL_SPEAKER

        return DeviceClass.UNKNOWN
    }
}
