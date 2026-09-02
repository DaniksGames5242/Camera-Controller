package com.mycamerascontroller.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.mycamerascontroller.agent.holo.AgentButtonView
import com.mycamerascontroller.agent.holo.AgentStageView
import com.mycamerascontroller.agent.holo.DecodeTextView
import com.mycamerascontroller.agent.holo.HoloBracketView

/**
 * The one screen this app shows a person: two permissions, granted once,
 * after which the agent lives entirely in a foreground service and this
 * activity is never opened again. It is still built out of the same
 * holographic language as both client apps — the room, the bracket frame,
 * the decode-in status text — because "seen once" is not "seen by no one",
 * and the very first impression of the whole system is exactly this screen.
 */
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

    private lateinit var stage: AgentStageView
    private lateinit var statusFrame: HoloBracketView
    private lateinit var statusText: DecodeTextView
    private lateinit var grantButton: AgentButtonView
    private lateinit var batteryButton: AgentButtonView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        stage = findViewById(R.id.stage)
        statusFrame = findViewById(R.id.statusFrame)
        statusText = findViewById(R.id.statusText)

        findViewById<DecodeTextView>(R.id.brandMark).apply {
            setImmediate("")
            setDecoded(getString(R.string.brand_mark))
        }

        grantButton = findViewById<AgentButtonView>(R.id.grantPermissionsButton).apply {
            label = getString(R.string.grant_permissions)
            onActivate = { permissionLauncher.launch(requestedPermissions) }
        }
        batteryButton = findViewById<AgentButtonView>(R.id.batteryOptButton).apply {
            label = getString(R.string.ignore_battery_optimizations)
            onActivate = { requestIgnoreBatteryOptimizations() }
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
        } else {
            batteryButton.done = true
        }
    }

    private fun refreshStatus() {
        val ready = hasEssentialPermissions()
        stage.armed = ready
        statusFrame.armed = ready
        statusText.setDecoded(
            if (ready) getString(R.string.status_idle) else getString(R.string.status_waiting)
        )
        grantButton.done = ready
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        batteryButton.done = pm.isIgnoringBatteryOptimizations(packageName)
    }
}
