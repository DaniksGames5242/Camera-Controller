package com.mycamerascontroller.agent

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.UUID

/**
 * Persistent per-device identity, mirrors deviceId.ts on desktop agents.
 *
 * Uses ANDROID_ID rather than a random UUID: a UUID generated on first run
 * and cached in SharedPreferences is wiped by uninstalling the app, so every
 * reinstall during testing/updates registered as a brand new Firebase device
 * — the old entry never went away, just sat there forever as "offline",
 * piling up duplicates. ANDROID_ID survives app uninstall/reinstall (it's
 * tied to the device + this app's package name + signing key, not to app
 * data), so the same physical phone reuses the same Firebase device record.
 * This only holds if the app is always signed with the same key — see the
 * pinned debug keystore in the release workflow.
 */
object DeviceIdentity {
    private const val PREFS = "device_identity"
    private const val KEY_ID = "device_id"
    private const val KEY_NAME = "device_name"

    @SuppressLint("HardwareIds")
    fun deviceId(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        // Known-bad values on some emulators/broken devices — fall back to a
        // cached random id rather than have every such device collide as "9774d56d682e549c".
        if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") return androidId

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_ID, id).apply()
        return id
    }

    fun deviceName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_NAME, null) ?: "${Build.MANUFACTURER} ${Build.MODEL}"
    }
}
