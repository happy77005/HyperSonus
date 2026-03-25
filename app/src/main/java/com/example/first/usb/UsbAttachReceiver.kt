package com.example.first.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.util.Log
import com.example.first.MusicService

/**
 * Receives USB_DEVICE_ATTACHED and USB_DEVICE_DETACHED system broadcasts.
 *
 * Why a BroadcastReceiver instead of a Service intent-filter?
 * Android does NOT reliably deliver USB attach intents to Services on all devices/versions.
 * The only reliable target for USB_DEVICE_ATTACHED is BroadcastReceiver or Activity.
 *
 * This receiver simply forwards the event to MusicService as a startService() call.
 * MusicService then calls UsbHelper.probeDevice() or triggers engine fallback.
 */
class UsbAttachReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "UsbAttachReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                Log.d(TAG, "[USB] Device attached — forwarding to MusicService")
                val svc = Intent(context, MusicService::class.java).apply {
                    action = UsbManager.ACTION_USB_DEVICE_ATTACHED
                    // Forward the UsbDevice parcelable so MusicService needn't re-query
                    putExtras(intent)
                }
                context.startService(svc)
            }
            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                Log.d(TAG, "[USB] Device detached — forwarding to MusicService")
                val svc = Intent(context, MusicService::class.java).apply {
                    action = UsbManager.ACTION_USB_DEVICE_DETACHED
                    putExtras(intent)
                }
                context.startService(svc)
            }
        }
    }
}
