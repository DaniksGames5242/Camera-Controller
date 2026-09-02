package com.mycamerascontroller.client

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.ValueEventListener

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: DeviceAdapter
    private var devicesListener: ValueEventListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val list = findViewById<RecyclerView>(R.id.deviceList)
        val emptyText = findViewById<TextView>(R.id.emptyText)
        list.layoutManager = LinearLayoutManager(this)
        adapter = DeviceAdapter(
            onClick = { device ->
                startActivity(
                    Intent(this, ViewerActivity::class.java)
                        .putExtra(ViewerActivity.EXTRA_DEVICE_ID, device.id)
                        .putExtra(ViewerActivity.EXTRA_DEVICE_NAME, device.record.name)
                )
            },
            onLongClick = { device ->
                val messageRes = if (device.record.status == "online") {
                    R.string.forget_device_confirm_online
                } else {
                    R.string.forget_device_confirm
                }
                AlertDialog.Builder(this)
                    .setTitle(device.record.name)
                    .setMessage(messageRes)
                    .setPositiveButton(R.string.forget_device) { _, _ -> Signaling.forgetDevice(device.id) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            },
            onSettingsClick = { device -> showSettingsDialog(device) },
        )
        list.adapter = adapter

        val hudStatus = findViewById<TextView>(R.id.hudStatus)
        hudStatus.text = getString(R.string.hud_initializing)

        Signaling.init {
            devicesListener = Signaling.listenDevices { devices ->
                adapter.submit(devices)
                emptyText.visibility = if (devices.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                val online = devices.count { it.record.status == "online" }
                hudStatus.text = getString(R.string.hud_status, devices.size, online)
            }
        }
    }

    override fun onDestroy() {
        devicesListener?.let { Signaling.stopListeningDevices(it) }
        super.onDestroy()
    }

    private fun showSettingsDialog(device: DeviceWithId) {
        val view = layoutInflater.inflate(R.layout.dialog_device_settings, null)
        val widthField = view.findViewById<EditText>(R.id.settingsWidth)
        val heightField = view.findViewById<EditText>(R.id.settingsHeight)
        val fpsField = view.findViewById<EditText>(R.id.settingsFps)

        Signaling.getDeviceSettings(device.id) { s ->
            s.width?.let { widthField.setText(it.toString()) }
            s.height?.let { heightField.setText(it.toString()) }
            s.frameRate?.let { fpsField.setText(it.toString()) }
        }

        AlertDialog.Builder(this)
            .setTitle(device.record.name)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                Signaling.setDeviceSettings(
                    device.id,
                    DeviceSettings(
                        width = widthField.text.toString().toIntOrNull(),
                        height = heightField.text.toString().toIntOrNull(),
                        frameRate = fpsField.text.toString().toIntOrNull(),
                    )
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
