package com.example.first

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

class UsbHelper(private val context: Context, private val listener: UsbListener) {

    private val TAG = "UsbAccess"
    private val ACTION_USB_PERMISSION = "com.example.first.USB_PERMISSION"
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    interface UsbListener {
        /** Called when USB permission is granted and the device connection is open. */
        fun onUsbAccessGranted(device: UsbDevice, connection: UsbDeviceConnection)
        /** Called when the user denies the USB permission dialog. */
        fun onUsbPermissionDenied(device: UsbDevice)
        /** Called when the active USB device is physically unplugged. */
        fun onUsbDeviceDetached(device: UsbDevice)
    }

    // FIX 4: Set-based guard supports multiple concurrent devices and fast reconnect.
    private val pendingRequests = mutableSetOf<Int>()

    // FIX 5: Track which device ID is currently active (open connection).
    // Only fire onUsbDeviceDetached for the active device — ignore others.
    private var activeDeviceId: Int = -1

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }

                        // FIX 3: explicit null guard before any use
                        if (device == null) {
                            Log.e(TAG, "[UsbAccess] Permission callback with null device — ignoring")
                            return
                        }

                        pendingRequests.remove(device.deviceId)   // FIX 4: clear from set

                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            Log.d(TAG, "[UsbAccess] Permission granted for ${device.productName}")
                            openDeviceConnection(device)
                        } else {
                            Log.w(TAG, "[UsbAccess] Permission denied for ${device.productName}")
                            listener.onUsbPermissionDenied(device)
                        }
                    }
                }
                // NOTE: ACTION_USB_DEVICE_ATTACHED is intentionally NOT handled here.
                // It is received exclusively by UsbAttachReceiver (Manifest BroadcastReceiver)
                // which forwards it to MusicService.onStartCommand → usbHelper.probeDevice().
                // Handling it here too caused a double-probe + double requestPermission() race
                // that made Android silently drop the permission dialog.
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    device?.let {
                        // FIX 5: only fire callback for the device we actually opened
                        if (it.deviceId == activeDeviceId) {
                            Log.d(TAG, "[UsbAccess] Active DAC detached: ${it.productName}")
                            activeDeviceId = -1
                            pendingRequests.remove(it.deviceId)
                            listener.onUsbDeviceDetached(it)
                        } else {
                            Log.d(TAG, "[UsbAccess] Ignoring detach for non-active device: ${it.productName}")
                        }
                    }
                }
            }
        }
    }

    fun register() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)               // permission dialog result
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)  // cable unplugged
            // ACTION_USB_DEVICE_ATTACHED is handled by UsbAttachReceiver (Manifest)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
    }

    // FIX 6: safe unregister — won't crash if already unregistered (e.g. double destroy)
    fun unregister() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "[UsbAccess] Receiver already unregistered: ${e.message}")
        }
    }

    fun discoverDevices() {
        for (device in usbManager.deviceList.values) {
            probeDevice(device)
        }
    }

    fun probeDevice(device: UsbDevice) {
        // Check for Audio Class (1) at device level first
        var isAudioDevice = device.deviceClass == UsbConstants.USB_CLASS_AUDIO

        // Some DACs report class 0 at device level but expose audio class on an interface
        if (!isAudioDevice) {
            for (i in 0 until device.interfaceCount) {
                if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_AUDIO) {
                    isAudioDevice = true
                    break
                }
            }
        }

        if (!isAudioDevice) return

        Log.d(TAG, "DAC Found: ${device.productName} (VID:${device.vendorId} PID:${device.productId})")

        if (usbManager.hasPermission(device)) {
            Log.d(TAG, "[UsbAccess] Already have permission — opening ${device.productName}")
            openDeviceConnection(device)
            return
        }

        // FIX 4: set-based guard prevents double requestPermission() for same device
        if (pendingRequests.contains(device.deviceId)) {
            Log.d(TAG, "[UsbAccess] Permission already pending for ${device.productName} — skipping")
            return
        }

        pendingRequests.add(device.deviceId)
        Log.d(TAG, "[UsbAccess] Requesting permission for ${device.productName}")

        // CRITICAL: The permission result broadcast has extras added by the Android OS
        // (EXTRA_DEVICE + EXTRA_PERMISSION_GRANTED). FLAG_IMMUTABLE blocks those extras
        // on Android 12+ → callback fires with device=null → permission silently lost.
        // Must use FLAG_MUTABLE on Android 12+ so the OS can attach its extras.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    PendingIntent.FLAG_MUTABLE   // ← required for USB permission on API 31+
                else
                    0

        val permissionIntent = PendingIntent.getBroadcast(
            context,
            device.deviceId,    // unique per device — prevents PendingIntent collision
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), // scoped to our app
            flags
        )
        usbManager.requestPermission(device, permissionIntent)

    }

    private fun openDeviceConnection(device: UsbDevice) {
        val connection = usbManager.openDevice(device)
        if (connection != null) {
            // FIX 7: richer log — includes device name and fd
            Log.d(TAG, "[USB] Connection opened (fd=${connection.fileDescriptor}) for ${device.productName}")
            activeDeviceId = device.deviceId   // FIX 5: mark this device as the active one
            listener.onUsbAccessGranted(device, connection)
        } else {
            Log.e(TAG, "[USB] Failed to open device: ${device.productName}")
            pendingRequests.remove(device.deviceId)
        }
    }
}
