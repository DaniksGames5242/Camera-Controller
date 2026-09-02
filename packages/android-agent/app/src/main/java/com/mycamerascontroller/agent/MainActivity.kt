package com.mycamerascontroller.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    // Camera/mic are the only permissions the agent actually can't work
    // without. POST_NOTIFICATIONS is requested too (so the status
    // notification shows when allowed), but a foreground service still runs
    // fine — just silently, with no visible notification — if it's denied;
    // gating startup on it would needlessly refuse to work without it.
    private val essentialPermissions = arrayOf(
        android.Manifest.permission.CAMERA,
        android.Manifest.permission.RECORD_AUDIO,
    )

    private val requestedPermissions: Array<String>
        get() = buildList {
            addAll(essentialPermissions)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (essentialPermissions.all { result[it] == true }) startAgentService()
        refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.grantPermissionsButton).setOnClickListener {
            permissionLauncher.launch(requestedPermissions)
        }
        findViewById<Button>(R.id.batteryOptButton).setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }

        if (hasEssentialPermissions()) startAgentService()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun hasEssentialPermissions() = essentialPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun startAgentService() {
        val intent = Intent(this, CameraAgentService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        }
    }

    private fun refreshStatus() {
        val statusText = findViewById<TextView>(R.id.statusText)
        statusText.text = if (hasEssentialPermissions()) getString(R.string.status_idle)
        else getString(R.string.grant_permissions)
    }
}
