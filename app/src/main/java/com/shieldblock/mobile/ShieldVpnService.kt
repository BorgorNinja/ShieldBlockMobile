package com.shieldblock.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Local DNS-filtering VPN. Only the virtual DNS server address is routed into the tunnel, so
 * regular traffic never touches the app. Each DNS query is checked against the uBlock-derived
 * domain list: blocked names get a 0.0.0.0 / :: answer, everything else is forwarded to the
 * network's normal DNS servers.
 */
class ShieldVpnService : VpnService() {
    companion object {
        const val ACTION_START = "com.shieldblock.mobile.START"
        const val ACTION_STOP = "com.shieldblock.mobile.STOP"
        private const val TAG = "ShieldVpn"
        private const val CHANNEL = "shield_status"
        private const val NOTIF_ID = 1
        private const val DNS_ADDR = "10.111.222.2"
        private val DNS_BYTES = byteArrayOf(10, 111, 222.toByte(), 2)

        @Volatile var running = false
        @Volatile var instance: ShieldVpnService? = null
        val blocked = AtomicLong()
        val queries = AtomicLong()
    }

    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    @Volatile private var engine: FilterEngine? = null
    private val pool = Executors.newFixedThreadPool(16)
    private var upstreamCache: List<InetAddress> = emptyList()
    private var upstreamTime = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs(this).enabled = false
            stopVpn()
            return START_NOT_STICKY
        }
        if (intent == null && !Prefs(this).enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(NOTIF_ID, n)
        }
        if (worker?.isAlive != true) {
            worker = Thread({ runVpn() }, "shield-vpn").also { it.start() }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        Prefs(this).enabled = false
        stopVpn()
    }

    override fun onDestroy() {
        stopVpn()
        pool.shutdownNow()
        instance = null
        super.onDestroy()
    }

    fun reload() {
        Thread({
            try {
                engine = Lists.load(applicationContext)
            } catch (e: Throwable) {
                Log.e(TAG, "reload failed", e)
            }
        }, "shield-reload").start()
    }

    private fun stopVpn() {
        running = false
        try { tun?.close() } catch (_: Exception) {}
        if (worker?.isAlive != true) stopSelf()
    }

    private fun runVpn() {
        try {
            // Lists must be downloaded before the tunnel exists: once it is up, DNS depends on it.
            if (!Updater.hasLists(this)) Updater.update(this, false)
            engine = Lists.load(this)

            val pfd = Builder()
                .setSession("ShieldBlock Mobile")
                .addAddress("10.111.222.1", 24)
                .addDnsServer(DNS_ADDR)
                .addRoute(DNS_ADDR, 32)
                .setMtu(1500)
                .setBlocking(true)
                .establish()
            if (pfd == null) {
                Log.e(TAG, "VPN permission missing")
                Prefs(this).enabled = false
                return
            }
            tun = pfd
            running = true

            Thread({
                try {
                    Updater.update(this, false)
                    reload()
                } catch (e: Throwable) {
                    Log.e(TAG, "background update failed", e)
                }
            }, "shield-bgupdate").start()

            loop(pfd)
        } catch (e: Exception) {
            Log.e(TAG, "vpn stopped", e)
        } finally {
            running = false
            try { tun?.close() } catch (_: Exception) {}
            tun = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun loop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val buf = ByteArray(32767)
        while (running) {
            val n = try { input.read(buf) } catch (e: Exception) { break }
            if (n < 0) break
            if (n == 0) continue
            val pkt = Packets.parseUdp(buf, n) ?: continue
            if (pkt.dstPort != 53 || !pkt.dstIp.contentEquals(DNS_BYTES)) continue
            queries.incrementAndGet()
            val q = Packets.parseQuery(pkt.payload, pkt.payload.size)
            if (q != null && engine?.isBlocked(q.name) == true) {
                blocked.incrementAndGet()
                reply(output, pkt, Packets.blockedResponse(pkt.payload, q))
            } else {
                pool.execute { forward(output, pkt) }
            }
        }
    }

    private fun forward(output: FileOutputStream, pkt: Packets.Udp) {
        for (server in upstreams()) {
            try {
                DatagramSocket().use { s ->
                    protect(s)
                    s.soTimeout = 2500
                    s.send(DatagramPacket(pkt.payload, pkt.payload.size, server, 53))
                    val rb = ByteArray(4096)
                    val rp = DatagramPacket(rb, rb.size)
                    s.receive(rp)
                    reply(output, pkt, rb.copyOf(rp.length))
                }
                return
            } catch (_: Exception) {
                // try next server
            }
        }
    }

    private fun reply(output: FileOutputStream, pkt: Packets.Udp, payload: ByteArray) {
        if (payload.size + 28 > 1500) return
        val out = Packets.buildUdp(pkt.dstIp, pkt.srcIp, 53, pkt.srcPort, payload)
        try {
            synchronized(output) { output.write(out) }
        } catch (_: Exception) {}
    }

    /** DNS servers of the real (non-VPN) networks, validated first, then public fallbacks. */
    private fun upstreams(): List<InetAddress> {
        val now = System.currentTimeMillis()
        if (upstreamCache.isNotEmpty() && now - upstreamTime < 10_000) return upstreamCache
        val cm = getSystemService(ConnectivityManager::class.java)
        val validated = ArrayList<InetAddress>()
        val other = ArrayList<InetAddress>()
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            val dns = cm.getLinkProperties(n)?.dnsServers ?: continue
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) validated.addAll(dns)
            else other.addAll(dns)
        }
        val list = LinkedHashSet<InetAddress>()
        list.addAll(validated)
        list.addAll(other)
        list.add(InetAddress.getByName("1.1.1.1"))
        list.add(InetAddress.getByName("9.9.9.9"))
        upstreamCache = list.toList()
        upstreamTime = now
        return upstreamCache
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "ShieldBlock status", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ShieldVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("ShieldBlock Mobile is active")
            .setContentText("Blocking ad, tracker and malware domains")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
    }
}
