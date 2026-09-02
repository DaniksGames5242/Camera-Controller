package com.mycamerascontroller.agent

import android.content.Context
import android.os.Build
import java.util.UUID

/** Persistent per-install identity, mirrors deviceId.ts on desktop agents. */
object DeviceIdentity {
    private const val PREFS = "device_identity"
    private const val KEY_ID = "device_id"
    private const val KEY_NAME = "device_name"

    fun deviceId(context: Context): String {
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
