package com.example.first

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MusicNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == "com.example.first.ACTION_COPY_ERROR") {
            val errorText = intent.getStringExtra("error_text") ?: "No error details available."
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("HyperHiRes Error", errorText)
            clipboard.setPrimaryClip(clip)
            android.widget.Toast.makeText(context, "Error details copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val serviceIntent = Intent(context, MusicService::class.java).apply {
            this.action = action
        }
        
        // Forward the action to the service
        context.startService(serviceIntent)
    }
}
