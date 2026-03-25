package com.example.first

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

class LandingActivity : AppCompatActivity() {
    private val TAG = "LandingActivity"

    private var pendingScanType: String = "FULL"
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            if (pendingScanType == "FOLDER") {
                val intent = Intent(this, FolderPickerActivity::class.java)
                folderPickerLauncher.launch(intent)
            } else {
                startMainActivity("FULL", null)
            }
        } else {
            Toast.makeText(this, "Permission denied. Cannot scan device.", Toast.LENGTH_SHORT).show()
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val folderPath = result.data?.getStringExtra("FOLDER_PATH")
            if (folderPath != null) {
                Log.d(TAG, "folderPickerLauncher: Received path $folderPath")
                startMainActivity("FOLDER", folderPath)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val savedType = prefs.getString("SCAN_TYPE", null)
        val savedUri = prefs.getString("FOLDER_URI", null)
        
        if (savedType != null) {
            Log.d(TAG, "onCreate: Found saved preference $savedType, auto-launching")
            startMainActivity(savedType, savedUri, isManual = false)
            return
        }

        Log.d(TAG, "onCreate: Activity started")
        setContentView(R.layout.activity_landing)
        Log.d(TAG, "onCreate: Layout set")

        findViewById<MaterialButton>(R.id.btnFullScan).setOnClickListener {
            Log.d(TAG, "Full Scan button clicked")
            pendingScanType = "FULL"
            checkPermissionAndStart()
        }

        findViewById<MaterialButton>(R.id.btnFolderScan).setOnClickListener {
            Log.d(TAG, "Folder Scan button clicked")
            pendingScanType = "FOLDER"
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }

            if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "btnFolderScan: Permission already granted, launching FolderPickerActivity")
                val intent = Intent(this, FolderPickerActivity::class.java)
                folderPickerLauncher.launch(intent)
            } else {
                Log.d(TAG, "btnFolderScan: Permission not granted, requesting $permission")
                requestPermissionLauncher.launch(permission)
            }
        }
    }

    private fun checkPermissionAndStart() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            startMainActivity("FULL", null)
        } else {
            requestPermissionLauncher.launch(permission)
        }
    }

    private fun startMainActivity(type: String, uri: String?, isManual: Boolean = true) {
        if (isManual) {
            val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
            prefs.edit().apply {
                putString("SCAN_TYPE", type)
                putString("FOLDER_URI", uri)
                apply()
            }
        }
        
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("SCAN_TYPE", type)
            putExtra("FOLDER_URI", uri)
        }
        startActivity(intent)
        finish()
    }
}
