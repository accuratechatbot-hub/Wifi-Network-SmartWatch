package com.example.networkscanner

import android.Manifest
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var textStatus: TextView
    private lateinit var textCount: TextView
    private lateinit var textInfo: TextView
    private lateinit var buttonToggle: Button
    private lateinit var listContainer: LinearLayout
    private lateinit var dotLive: View

    private var rendered: List<ScanStore.Device>? = null
    private val listener: () -> Unit = { refresh() }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startScanning() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textStatus = findViewById(R.id.textStatus)
        textCount = findViewById(R.id.textCount)
        textInfo = findViewById(R.id.textInfo)
        buttonToggle = findViewById(R.id.buttonToggle)
        listContainer = findViewById(R.id.listContainer)
        dotLive = findViewById(R.id.dotLive)

        buttonToggle.setOnClickListener {
            if (ScanStore.state == ScanStore.State.STOPPED) startScanning() else stopScanning()
        }

        ObjectAnimator.ofFloat(dotLive, View.ALPHA, 1f, 0.2f).apply {
            duration = 900
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }

        // Start automatically on launch (asking once for notification permission for alerts).
        if (savedInstanceState == null) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startScanning()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ScanStore.addListener(listener)
        refresh()
    }

    override fun onStop() {
        ScanStore.removeListener(listener)
        super.onStop()
    }

    private fun startScanning() {
        ContextCompat.startForegroundService(this, Intent(this, ScanService::class.java))
    }

    private fun stopScanning() {
        stopService(Intent(this, ScanService::class.java))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun refresh() {
        val state = ScanStore.state
        textStatus.text = when (state) {
            ScanStore.State.STOPPED -> "STOPPED"
            ScanStore.State.NO_WIFI -> "NO WI-FI"
            ScanStore.State.SCANNING -> "SCANNING ${ScanStore.progress}%"
            ScanStore.State.IDLE -> "LIVE"
        }
        buttonToggle.text = if (state == ScanStore.State.STOPPED) "START SCAN" else "STOP"

        val devices = ScanStore.devices
        textCount.text = if (devices.isEmpty()) "--" else devices.size.toString()
        textInfo.text = when {
            state == ScanStore.State.NO_WIFI -> "Connect the watch to Wi-Fi (Bluetooth only won't work)"
            ScanStore.myIp != null -> "YOU ${ScanStore.myIp}" + (ScanStore.gateway?.let { "  ROUTER $it" } ?: "")
            else -> ""
        }

        if (devices !== rendered) {
            rendered = devices
            listContainer.removeAllViews()
            devices.forEach { addRow(it) }
        }
    }

    private fun addRow(d: ScanStore.Device) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.card_bg)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        row.addView(TextView(this).apply {
            text = d.ip
            typeface = Typeface.create("monospace", Typeface.BOLD)
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.green_400))
        })
        val tags = buildList {
            if (d.isMe) add("THIS WATCH")
            if (d.isGateway) add("ROUTER")
            if (!d.isMe) d.host?.let { add(it) }
            if (!d.isMe && d.ms >= 0) add("${d.ms} ms")
        }
        if (tags.isNotEmpty()) {
            row.addView(TextView(this).apply {
                text = tags.joinToString(" · ")
                textSize = 9f
                typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.gray_400))
            })
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(4) }
        listContainer.addView(row, lp)
    }
}
