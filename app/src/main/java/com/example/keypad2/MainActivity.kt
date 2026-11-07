package com.example.keypad2

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var info: TextView
    private lateinit var btnStart: Button
    private lateinit var btnDisc: Button
    private lateinit var btnPc: Button
    private lateinit var btnMin: Button
    private lateinit var btnStop: Button
    private lateinit var btnLayout: Button

    private var service: KeypadService? = null
    private var bound = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as KeypadService.LocalBinder).service()
            bound = true
            service?.hideBar()
            service?.onStatus = { text, ok -> showStatus(text, ok) }
            showStatus(service!!.statusText, service!!.connected)
            refreshButtons()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
            refreshButtons()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.BLACK)
            setPadding(40, 80, 40, 40)
        }

        status = TextView(this).apply {
            text = "Not started"
            textSize = 20f
            setTextColor(Color.parseColor("#ffcc00"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        root.addView(status)

        info = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#aaaaaa"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        root.addView(info)

        fun add(t: String, bg: String = "#333333", a: () -> Unit): Button {
            val b = Button(this).apply {
                text = t
                textSize = 16f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor(bg))
                setOnClickListener { a() }
            }
            val lp = LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(0, 10, 0, 10) }
            root.addView(b, lp)
            return b
        }

        add("1) Allow permissions") { askPermissions(); askOverlay() }
        btnStart = add("2) START", "#1a7f37") { startKeypad() }
        btnDisc = add("3) DISCOVERABLE (to pair)") { discoverable() }
        btnPc = add("4) CONNECT PC") { pickPc() }
        btnLayout = add("LAYOUT: " + layoutNames[currentLayout()], "#6a1b9a") { cycleLayout() }
        btnMin = add("5) MINIMIZE -> show keys", "#1565c0") { minimize() }
        btnStop = add("STOP & EXIT", "#b02a2a") { stopAll() }

        setContentView(root)
        refreshButtons()
    }

    override fun onStart() {
        super.onStart()
        if (running && !bound) bindService(
            Intent(this, KeypadService::class.java), conn, Context.BIND_AUTO_CREATE
        )
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        service?.hideBar()
        updateInfo()
        refreshButtons()
    }

    override fun onStop() {
        if (bound) {
            service?.onStatus = null
            unbindService(conn)
            bound = false
            service = null
        }
        super.onStop()
    }

    private fun hasBt(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        ).all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun updateInfo() {
        info.text = "Bluetooth: ${if (hasBt()) "OK" else "missing"}   |   " +
                "Overlay: ${if (Settings.canDrawOverlays(this)) "OK" else "missing"}"
    }

    private fun showStatus(text: String, ok: Boolean) {
        status.text = text
        status.setTextColor(Color.parseColor(if (ok) "#00ff99" else "#ffcc00"))
    }

    private fun refreshButtons() {
        val on = bound
        btnStart.isEnabled = !running
        btnDisc.isEnabled = on
        btnPc.isEnabled = on
        btnMin.isEnabled = on
    }

    private fun askPermissions() {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            list += Manifest.permission.BLUETOOTH_CONNECT
            list += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        if (list.isNotEmpty()) requestPermissions(list.toTypedArray(), 1)
    }

    private fun askOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun startKeypad() {
        if (!hasBt() || !Settings.canDrawOverlays(this)) {
            showStatus("Grant step 1 first, then START", false)
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            showStatus("Turn Bluetooth on first", false)
            return
        }
        val i = Intent(this, KeypadService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        running = true
        bindService(i, conn, Context.BIND_AUTO_CREATE)
        refreshButtons()
    }

    private fun discoverable() {
        startActivity(
            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        )
    }

    private fun pickPc() {
        val s = service ?: return
        val devices = s.pairedDevices()
        if (devices.isEmpty()) {
            Toast.makeText(this, "Pair with PC first (step 3)", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Choose PC")
            .setItems(devices.map { it.name ?: it.address }.toTypedArray()) { _, i ->
                s.connectTo(devices[i])
            }.show()
    }

    private fun minimize() {
        val s = service ?: return
        if (!Settings.canDrawOverlays(this)) {
            showStatus("Allow overlay first (step 1)", false)
            return
        }
        s.showBar()
        moveTaskToBack(true)
    }

    private fun stopAll() {
        service?.hideBar()
        if (bound) {
            service?.onStatus = null
            unbindService(conn)
            bound = false
        }
        service = null
        stopService(Intent(this, KeypadService::class.java))
        running = false
        finishAndRemoveTask()
    }

    private val layoutNames = listOf(
        "1 column (all)",
        "2 columns (F5-F8 | F1-F4)",
        "1 column: HOME PGUP F5-F8",
        "1 column: HOME PGUP F1-F4"
    )

    private fun currentLayout() =
        getSharedPreferences("kp2", MODE_PRIVATE).getInt("layout", 0)

    private fun cycleLayout() {
        val n = (currentLayout() + 1) % layoutNames.size
        getSharedPreferences("kp2", MODE_PRIVATE).edit().putInt("layout", n).apply()
        btnLayout.text = "LAYOUT: " + layoutNames[n]
    }

    companion object {
        @Volatile var running = false
    }
}
