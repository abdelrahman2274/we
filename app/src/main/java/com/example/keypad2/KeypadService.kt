package com.example.keypad2

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast

@SuppressLint("MissingPermission", "ClickableViewAccessibility")
class KeypadService : Service() {

    inner class LocalBinder : Binder() {
        fun service() = this@KeypadService
    }

    private val binder = LocalBinder()

    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var wm: WindowManager
    private var bar: LinearLayout? = null
    private var barParams: WindowManager.LayoutParams? = null

    var onStatus: ((String, Boolean) -> Unit)? = null

    private val topKeys = listOf("HOME" to 0x4A, "PGUP" to 0x4B)
    private val keysF5to8 = listOf("F5" to 0x3E, "F6" to 0x3F, "F7" to 0x40, "F8" to 0x41)
    private val keysF1to4 = listOf("F1" to 0x3A, "F2" to 0x3B, "F3" to 0x3C, "F4" to 0x3D)

    private val descriptor = byteArrayOf(
        0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x01,
        0x05, 0x07, 0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(),
        0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x08, 0x81.toByte(), 0x02,
        0x95.toByte(), 0x01, 0x75, 0x08, 0x81.toByte(), 0x01,
        0x95.toByte(), 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
        0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81.toByte(), 0x00,
        0xC0.toByte()
    )

    var statusText = "Starting..."
        private set
    var connected = false
        private set

    private fun publish(text: String, ok: Boolean) {
        statusText = text
        connected = ok
        ui.post { onStatus?.invoke(text, ok) }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                host = device
                publish("Connected: ${device.name ?: device.address}", true)
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (host == device) host = null
                publish("Disconnected", false)
            }
        }
    }

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            val h = proxy as BluetoothHidDevice
            hid = h
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "Key Pad 2", "Keypad2", "Me",
                BluetoothHidDevice.SUBCLASS1_KEYBOARD, descriptor
            )
            h.registerApp(sdp, null, null, { it.run() }, callback)

            val already = BluetoothAdapter.getDefaultAdapter().bondedDevices
                .firstOrNull { h.getConnectionState(it) == BluetoothProfile.STATE_CONNECTED }
            if (already != null) {
                host = already
                publish("Connected: ${already.name ?: already.address}", true)
            } else {
                publish("Ready. Pair from PC or tap CONNECT PC", false)
            }
        }

        override fun onServiceDisconnected(profile: Int) { hid = null }
    }

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        BluetoothAdapter.getDefaultAdapter()
            ?.getProfileProxy(this, listener, BluetoothProfile.HID_DEVICE)
    }

    fun pairedDevices(): List<BluetoothDevice> =
        BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList() ?: emptyList()

    fun connectTo(device: BluetoothDevice) {
        val h = hid
        if (h == null) {
            publish("Not ready yet", false)
            return
        }
        publish("Connecting to ${device.name ?: device.address}...", false)
        h.connect(device)
    }

    fun showBar() {
        if (bar != null) return
        buildBar()
    }

    fun hideBar() {
        bar?.let { runCatching { wm.removeView(it) } }
        bar = null
    }

    private fun startAsForeground() {
        val chId = "keypad2"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(chId, "Key Pad 2", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, KeypadService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE
        )
        @Suppress("DEPRECATION")
        val n = Notification.Builder(this, chId)
            .setContentTitle("Key Pad 2 is running")
            .setContentText("Tap to open full screen")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(android.R.drawable.ic_delete, "Stop", stop).build()
            )
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, n)
        }
    }

    private fun buildBar() {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val mode = getSharedPreferences("kp2", MODE_PRIVATE).getInt("layout", 0)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E6111111"))
            setPadding(px(4), px(4), px(4), px(4))
        }

        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val lp = barParams ?: WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0
            y = px(160)
        }
        barParams = lp

        fun makeBtn(name: String, code: Int): Button {
            val b = Button(this).apply {
                text = name
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#333333"))
                layoutParams = LinearLayout.LayoutParams(px(58), px(44)).apply {
                    setMargins(px(2), px(3), px(2), px(3))
                }
                setPadding(0, 0, 0, 0)
            }

            var startX = 0; var startY = 0
            var touchX = 0f; var touchY = 0f
            var dragging = false
            var longPress: Runnable? = null

            b.setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = lp.x; startY = lp.y
                        touchX = e.rawX; touchY = e.rawY
                        dragging = false
                        longPress = Runnable {
                            dragging = true
                            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        }
                        ui.postDelayed(longPress!!, 450)
                        v.isPressed = true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (dragging) {
                            lp.x = startX - (e.rawX - touchX).toInt()
                            lp.y = startY + (e.rawY - touchY).toInt()
                            wm.updateViewLayout(root, lp)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        longPress?.let { ui.removeCallbacks(it) }
                        v.isPressed = false
                        if (!dragging && e.action == MotionEvent.ACTION_UP) press(code)
                        dragging = false
                    }
                }
                true
            }
            return b
        }

        fun column(list: List<Pair<String, Int>>): LinearLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                for ((n, c) in list) addView(makeBtn(n, c))
            }

        when (mode) {
            1 -> {
                // HOME/PGUP on topKeys, then two columns: F5-F8 | F1-F4
                val topRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    for ((n, c) in topKeys) addView(makeBtn(n, c))
                }
                root.addView(topRow)
                val cols = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(column(keysF5to8))
                    addView(column(keysF1to4))
                }
                root.addView(cols)
            }
            2 -> root.addView(column(topKeys + keysF5to8))
            3 -> root.addView(column(topKeys + keysF1to4))
            else -> root.addView(column(topKeys + keysF5to8 + keysF1to4))
        }

        bar = root
        wm.addView(root, lp)
    }

    private fun press(code: Int) {
        val h = hid
        val d = host
        if (h == null || d == null) {
            Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show()
            return
        }
        h.sendReport(d, 1, byteArrayOf(0, 0, code.toByte(), 0, 0, 0, 0, 0))
        ui.postDelayed({ h.sendReport(d, 1, ByteArray(8)) }, 70)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            MainActivity.running = false
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        hideBar()
        hid?.unregisterApp()
        super.onDestroy()
    }
}
