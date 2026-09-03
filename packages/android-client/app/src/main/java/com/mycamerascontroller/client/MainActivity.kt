package com.mycamerascontroller.client

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.ValueEventListener
import com.mycamerascontroller.client.holo.DecodeTextView
import com.mycamerascontroller.client.holo.Haptics
import com.mycamerascontroller.client.holo.Holo
import com.mycamerascontroller.client.holo.HoloBootView
import com.mycamerascontroller.client.holo.HoloButtonView
import com.mycamerascontroller.client.holo.HoloPulseView
import com.mycamerascontroller.client.holo.HoloRadialMenu
import com.mycamerascontroller.client.holo.HoloStageView
import com.mycamerascontroller.client.holo.HoloStatsView
import com.mycamerascontroller.client.holo.HoloToastHost

/**
 * The node list, standing inside the hologram.
 *
 * Everything specific to this client living rather than the desktop one is
 * here: the room parallaxes to the device's own orientation, presses stir the
 * dust at the exact point of contact, a long press fans the actions out
 * around the thumb, and an over-scroll at the top of the list re-sweeps the
 * room instead of spinning a refresh indicator.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var stage: HoloStageView
    private lateinit var adapter: DeviceAdapter
    private lateinit var stats: HoloStatsView
    private lateinit var radial: HoloRadialMenu
    private lateinit var toasts: HoloToastHost
    private lateinit var boot: HoloBootView
    private lateinit var emptyState: View

    private var devicesListener: ValueEventListener? = null
    private var devices: List<DeviceWithId> = emptyList()
    private var knownOnline: Set<String> = emptySet()
    private var overscroll = 0f
    private var scanArmed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge to edge: the room should reach the corners of the display,
        // with the interface inset out of the system bars' way.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        stage = findViewById(R.id.stage)
        stats = findViewById(R.id.stats)
        radial = findViewById(R.id.radial)
        toasts = findViewById(R.id.toasts)
        boot = findViewById(R.id.boot)
        emptyState = findViewById(R.id.emptyState)

        findViewById<DecodeTextView>(R.id.brandMark).apply {
            setImmediate("")
            setDecoded(getString(R.string.brand_mark))
        }

        applyInsets()
        setUpDock()
        setUpList()
        startBoot()

        Signaling.init {
            devicesListener = Signaling.listenDevices { list ->
                onDevices(list)
            }
        }
    }

    private fun applyInsets() {
        val content = findViewById<View>(R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top, view.paddingRight, bars.bottom)
            insets
        }
    }

    private fun setUpDock() {
        findViewById<HoloButtonView>(R.id.scanButton).apply {
            label = getString(R.string.action_scan)
            glyph = "◈"
            accent = Holo.CYAN
            onActivate = {
                stage.renderer.triggerScan()
                stage.renderer.kickGlitch(0.35f)
                toasts.push(getString(R.string.toast_scanning), HoloToastHost.Tone.INFO, 2000)
            }
            onTouchPoint = { x, y -> stirAt(x, y) }
        }
        findViewById<HoloButtonView>(R.id.recentreButton).apply {
            label = getString(R.string.action_recentre)
            glyph = "⌾"
            accent = Holo.VIOLET
            onActivate = {
                // Re-zero the tilt reference to however the phone is held now.
                stage.recalibrateTilt()
                toasts.push(getString(R.string.toast_recentred), HoloToastHost.Tone.OK, 1800)
            }
            onTouchPoint = { x, y -> stirAt(x, y) }
        }
    }

    private fun setUpList() {
        val list = findViewById<RecyclerView>(R.id.deviceList)
        list.layoutManager = LinearLayoutManager(this)
        list.itemAnimator = null // the cards run their own physics
        adapter = DeviceAdapter(
            onActivate = { device -> openViewer(device) },
            onLongPress = { device, x, y -> showNodeMenu(device, x, y) },
            onSettings = { device -> showSettings(device) },
            onTouchPoint = { x, y -> stirAt(x, y) },
        )
        list.adapter = adapter

        // Pull past the top of the list to re-sweep the room. The gesture is
        // already there for refreshing; here it drives something the user can
        // actually see happening.
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy < 0 && !rv.canScrollVertically(-1)) {
                    overscroll += -dy.toFloat()
                    if (overscroll > 220f && !scanArmed) {
                        scanArmed = true
                        Haptics.materialise(rv)
                        stage.renderer.triggerScan()
                        stage.renderer.kickGlitch(0.5f)
                        toasts.push(getString(R.string.toast_scanning), HoloToastHost.Tone.INFO, 1800)
                    }
                } else if (rv.canScrollVertically(-1)) {
                    overscroll = 0f
                    scanArmed = false
                }
            }
        })
    }

    private fun startBoot() {
        boot.onProgress = { p -> stage.renderer.boot = p }
        boot.onFinished = {
            stage.renderer.boot = 1f
            stage.renderer.triggerScan()
        }
        boot.begin()
    }

    /**
     * Every touch anywhere is observed — never consumed — so the room reacts
     * to contact even when the gesture belongs to a control on top of it.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (radial.isOpen) {
            radial.onGesture(ev)
            return true
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            boot.skip()
            stage.observeTouch(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun stirAt(rawX: Float, rawY: Float) {
        stage.stirAt(rawX, rawY)
    }

    private fun onDevices(list: List<DeviceWithId>) {
        devices = list
        adapter.submit(list)
        val online = list.filter { it.record.isOnline() }.map { it.id }.toSet()
        // Announce genuinely new arrivals only — the agent's heartbeat
        // rewrites these records constantly.
        for (device in list) {
            if (device.id in online && device.id !in knownOnline && knownOnline.isNotEmpty()) {
                toasts.push(getString(R.string.toast_node_online, device.record.name), HoloToastHost.Tone.OK)
                stage.renderer.ripple((Math.random().toFloat() - 0.5f) * 12f, 0f, 0.7f)
            }
        }
        knownOnline = online
        stats.set(list.size, online.size, adapter.openChannels.size)
        emptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openViewer(device: DeviceWithId) {
        Haptics.materialise(window.decorView)
        stage.renderer.kickGlitch(0.6f)
        startActivity(
            Intent(this, ViewerActivity::class.java)
                .putExtra(ViewerActivity.EXTRA_DEVICE_ID, device.id)
                .putExtra(ViewerActivity.EXTRA_DEVICE_NAME, device.record.name)
                .putExtra(ViewerActivity.EXTRA_TINT, Holo.tintFor(device.id))
        )
        // The activities dissolve into each other rather than sliding: the
        // channel is a projection coming up, not a page being pushed.
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun showNodeMenu(device: DeviceWithId, x: Float, y: Float) {
        val online = device.record.isOnline()
        val items = buildList {
            if (online) add(
                HoloRadialMenu.Item(getString(R.string.action_open), "▶") { openViewer(device) }
            )
            add(HoloRadialMenu.Item(getString(R.string.action_settings), "⚙") { showSettings(device) })
            add(
                HoloRadialMenu.Item(getString(R.string.forget_device), "⌫", danger = true) {
                    confirmForget(device)
                }
            )
        }
        radial.show(x, y, Holo.tintFor(device.id), items)
    }

    private fun confirmForget(device: DeviceWithId) {
        // Two-stage rather than a dialog: the destructive action is itself
        // behind a second deliberate press on a plate that says what it does.
        val sheet = HoloConfirmSheet(
            this,
            title = device.record.name,
            body = getString(
                if (device.record.isOnline()) R.string.forget_device_confirm_online
                else R.string.forget_device_confirm
            ),
            confirmLabel = getString(R.string.forget_device),
        ) {
            Signaling.forgetDevice(device.id)
            stage.renderer.kickGlitch(0.9f)
            toasts.push(getString(R.string.toast_node_forgotten, device.record.name), HoloToastHost.Tone.WARN)
        }
        sheet.show()
    }

    private fun showSettings(device: DeviceWithId) {
        Signaling.getDeviceSettings(device.id) { current ->
            HoloSettingsSheet(
                this,
                deviceName = device.record.name,
                accent = Holo.tintFor(device.id),
                initial = current,
            ) { patch ->
                Signaling.setDeviceSettings(device.id, patch)
                toasts.push(getString(R.string.toast_settings_saved, device.record.name), HoloToastHost.Tone.OK)
            }.show()
        }
    }

    override fun onResume() {
        super.onResume()
        stage.onResume()
        // Coming back from a viewer: the channel is no longer open here.
        adapter.openChannels = emptySet()
        stats.set(devices.size, knownOnline.size, 0)
    }

    override fun onPause() {
        stage.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        devicesListener?.let { Signaling.stopListeningDevices(it) }
        super.onDestroy()
    }
}
