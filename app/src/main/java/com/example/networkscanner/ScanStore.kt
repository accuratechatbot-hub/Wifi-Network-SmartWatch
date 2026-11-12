package com.example.networkscanner

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** Latest scan results, shared between the background service and the UI. */
object ScanStore {
    data class Device(
        val ip: String,
        val host: String?,
        val ms: Long,
        val isMe: Boolean,
        val isGateway: Boolean
    )

    enum class State { STOPPED, NO_WIFI, SCANNING, IDLE }

    @Volatile var state = State.STOPPED
    @Volatile var progress = 0
    @Volatile var devices: List<Device> = emptyList()
    @Volatile var myIp: String? = null
    @Volatile var gateway: String? = null

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    fun changed() { main.post { listeners.forEach { it() } } }
}
