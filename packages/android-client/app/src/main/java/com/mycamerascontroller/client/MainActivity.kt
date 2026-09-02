package com.mycamerascontroller.client

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
        adapter = DeviceAdapter { device ->
            startActivity(
                Intent(this, ViewerActivity::class.java)
                    .putExtra(ViewerActivity.EXTRA_DEVICE_ID, device.id)
                    .putExtra(ViewerActivity.EXTRA_DEVICE_NAME, device.record.name)
            )
        }
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
