package com.example.networkscanner

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Finds devices on the same Wi-Fi network by probing every address in the subnet. */
object NetworkScanner {

    class WifiInfo(
        val network: Network,
        val ip: Inet4Address,
        val prefix: Int,
        val gateway: String?
    )

    private val dnsPool = Executors.newCachedThreadPool()

    fun currentWifi(context: Context): WifiInfo? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val lp = cm.getLinkProperties(n) ?: continue
            val addr = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: continue
            val gw = lp.routes
                .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                ?.gateway?.hostAddress
            return WifiInfo(n, addr.address as Inet4Address, addr.prefixLength, gw)
        }
        return null
    }

    private fun toInt(a: Inet4Address): Int {
        val b = a.address
        return ((b[0].toInt() and 255) shl 24) or ((b[1].toInt() and 255) shl 16) or
            ((b[2].toInt() and 255) shl 8) or (b[3].toInt() and 255)
    }

    private fun toStr(v: Int) =
        "${(v ushr 24) and 255}.${(v ushr 16) and 255}.${(v ushr 8) and 255}.${v and 255}"

    fun scan(
        wifi: WifiInfo,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean
    ): List<ScanStore.Device> {
        // Scan at most one /24 (254 addresses) around the watch.
        val prefix = wifi.prefix.coerceIn(24, 30)
        val mask = -1 shl (32 - prefix)
        val me = toInt(wifi.ip)
        val network = me and mask
        val broadcast = network or mask.inv()
        val targets = (network + 1 until broadcast).filter { it != me }

        val pool = Executors.newFixedThreadPool(48)
        val done = AtomicInteger(0)
        val found = ArrayList<ScanStore.Device>()
        try {
            val futures = targets.map { ip ->
                pool.submit(Callable {
                    val d = if (isCancelled()) null else probe(wifi, ip)
                    val n = done.incrementAndGet()
                    if (n % 8 == 0 || n == targets.size) onProgress(n * 100 / targets.size)
                    d
                })
            }
            for (f in futures) {
                val d = try { f.get() } catch (e: Exception) { null }
                if (d != null) found.add(d)
            }
        } finally {
            pool.shutdownNow()
        }

        val myIpStr = wifi.ip.hostAddress ?: toStr(me)
        found.add(ScanStore.Device(myIpStr, "This watch", 0, true, false))
        // The router is on the network by definition, even if it ignores probes.
        val gw = wifi.gateway
        if (gw != null && found.none { it.ip == gw }) {
            found.add(ScanStore.Device(gw, null, -1, false, true))
        }
        return found.sortedBy { d -> InetAddress.getByName(d.ip).address.fold(0L) { acc, b -> acc * 256 + (b.toInt() and 255) } }
    }

    private fun probe(wifi: WifiInfo, ip: Int): ScanStore.Device? {
        val ipStr = toStr(ip)
        val addr = InetAddress.getByName(ipStr)
        var alive = false
        var rtt = -1L

        // A connect that succeeds OR is actively refused means a host is there.
        for (port in intArrayOf(443, 80)) {
            val start = System.nanoTime()
            val socket = wifi.network.socketFactory.createSocket()
            try {
                socket.connect(InetSocketAddress(addr, port), 250)
                alive = true
            } catch (e: ConnectException) {
                if (e.message?.contains("refused", ignoreCase = true) == true) alive = true
            } catch (e: Exception) {
                // timeout or unreachable: nothing answered on this port
            } finally {
                try { socket.close() } catch (e: Exception) {}
            }
            if (alive) {
                rtt = (System.nanoTime() - start) / 1_000_000
                break
            }
        }
        if (!alive) {
            val start = System.nanoTime()
            alive = try { addr.isReachable(300) } catch (e: Exception) { false }
            if (alive) rtt = (System.nanoTime() - start) / 1_000_000
        }
        if (!alive) return null

        return ScanStore.Device(ipStr, resolveHost(addr), rtt, false, ipStr == wifi.gateway)
    }

    private fun resolveHost(addr: InetAddress): String? = try {
        val f = dnsPool.submit<String> { addr.canonicalHostName }
        val name = try { f.get(700, TimeUnit.MILLISECONDS) } finally { f.cancel(true) }
        if (name == addr.hostAddress) null else name
    } catch (e: Exception) {
        null
    }
}
