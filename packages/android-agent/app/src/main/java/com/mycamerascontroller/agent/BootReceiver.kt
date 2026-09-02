package com.mycamerascontroller.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Restarts the agent service after reboot — the app-store analogue of the
 * desktop agents' autostart registration, no elevated permission required.
 * Only fires if the user already granted camera/mic (checked before start;
 * without them the service would immediately fail to capture anyway).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val hasPermissions = listOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
        ).all {
            ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (!hasPermissions) return

        ContextCompat.startForegroundService(context, Intent(context, CameraAgentService::class.java))
    }
}
