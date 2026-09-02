package com.mycamerascontroller.client

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DeviceAdapter(
    private val onClick: (DeviceWithId) -> Unit,
    private val onLongClick: (DeviceWithId) -> Unit,
) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

    private var devices: List<DeviceWithId> = emptyList()

    fun submit(list: List<DeviceWithId>) {
        devices = list.sortedBy { it.record.name }
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dot: View = view.findViewById(R.id.statusDot)
        val name: TextView = view.findViewById(R.id.deviceName)
        val status: TextView = view.findViewById(R.id.deviceStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount() = devices.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = devices[position]
        val online = device.record.status == "online"
        holder.name.text = device.record.name
        holder.status.text = holder.itemView.context.getString(
            if (online) R.string.status_online else R.string.status_offline
        )
        holder.dot.setBackgroundResource(if (online) R.drawable.dot_online else R.drawable.dot_offline)
        holder.itemView.alpha = if (online) 1f else 0.5f
        holder.itemView.setOnClickListener { if (online) onClick(device) }
        // Long-press to forget a device — mainly useful for offline
        // leftovers (e.g. a reinstalled agent, which registers under a new
        // record if this one is never cleaned up), but allowed for any
        // device since a stale "online" entry can also happen.
        holder.itemView.setOnLongClickListener { onLongClick(device); true }
    }
}
