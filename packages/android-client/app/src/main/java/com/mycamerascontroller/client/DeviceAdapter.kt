package com.mycamerascontroller.client

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.mycamerascontroller.client.holo.Holo
import com.mycamerascontroller.client.holo.HoloNodeView
import com.mycamerascontroller.client.holo.dp

/**
 * The node list.
 *
 * Rows are [HoloNodeView]s — single custom views that draw themselves — so
 * there is no item layout to inflate and every card animates continuously
 * without the adapter being involved. Diffing rather than
 * notifyDataSetChanged keeps a card's springs and phase intact when its
 * status changes underneath it; rebuilding the row would reset the entrance
 * animation on every heartbeat from the agent.
 */
class DeviceAdapter(
    private val onActivate: (DeviceWithId) -> Unit,
    private val onLongPress: (DeviceWithId, Float, Float) -> Unit,
    private val onSettings: (DeviceWithId) -> Unit,
    private val onTouchPoint: (Float, Float) -> Unit,
) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

    private var devices: List<DeviceWithId> = emptyList()
    /** Device ids with a viewer currently open, so the card can say so. */
    var openChannels: Set<String> = emptySet()
        set(value) { field = value; notifyItemRangeChanged(0, itemCount, PAYLOAD_STATE) }

    class ViewHolder(val node: HoloNodeView) : RecyclerView.ViewHolder(node)

    fun submit(list: List<DeviceWithId>) {
        val next = list.sortedBy { it.record.name }
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = devices.size
            override fun getNewListSize() = next.size
            override fun areItemsTheSame(o: Int, n: Int) = devices[o].id == next[n].id
            override fun areContentsTheSame(o: Int, n: Int) = devices[o] == next[n]
        })
        devices = next
        diff.dispatchUpdatesTo(this)
    }

    fun deviceAt(position: Int): DeviceWithId? = devices.getOrNull(position)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val node = HoloNodeView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val m = parent.context.dp(5f).toInt()
                setMargins(0, m, 0, m)
            }
        }
        return ViewHolder(node)
    }

    override fun getItemCount() = devices.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_STATE)) {
            // Status-only refresh: leave the card's own animation state alone.
            bindState(holder, devices[position])
            return
        }
        onBindViewHolder(holder, position)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = devices[position]
        val node = holder.node
        node.title = device.record.name
        node.tint = Holo.tintFor(device.id)
        bindState(holder, device)

        node.onTouchPoint = { x, y -> onTouchPoint(x, y) }
        node.onActivate = activate@{
            val current = holder.bindingAdapterPosition.takeIf { it >= 0 }?.let { devices.getOrNull(it) } ?: return@activate
            if (current.record.isOnline() || openChannels.contains(current.id)) onActivate(current)
            else node.reject()
        }
        node.onLongPressAt = longPress@{ x, y ->
            val current = holder.bindingAdapterPosition.takeIf { it >= 0 }?.let { devices.getOrNull(it) } ?: return@longPress
            onLongPress(current, x, y)
        }
        node.onSettings = settings@{
            val current = holder.bindingAdapterPosition.takeIf { it >= 0 }?.let { devices.getOrNull(it) } ?: return@settings
            onSettings(current)
        }
    }

    private fun bindState(holder: ViewHolder, device: DeviceWithId) {
        holder.node.online = device.record.isOnline()
        holder.node.channelOpen = openChannels.contains(device.id)
    }

    private companion object {
        const val PAYLOAD_STATE = "state"
    }
}
