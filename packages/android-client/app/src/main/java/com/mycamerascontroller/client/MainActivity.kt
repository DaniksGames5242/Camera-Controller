package com.mycamerascontroller.client

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
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
                AlertDialog.Builder(this)
                    .setTitle(device.record.name)
                    .setMessage(R.string.forget_device_confirm)
                    .setPositiveButton(R.string.forget_device) { _, _ -> Signaling.forgetDevice(device.id) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            },
        )
        list.adapter = adapter

        Signaling.init {
            devicesListener = Signaling.listenDevices { devices ->
                adapter.submit(devices)
                emptyText.visibility = if (devices.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
    }

    override fun onDestroy() {
        devicesListener?.let { Signaling.stopListeningDevices(it) }
        super.onDestroy()
    }
}
