package com.fluxfabric.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.fluxfabric.MainActivity
import com.fluxfabric.model.Packet
import com.fluxfabric.plugin.PluginManager
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.*
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

class FluxVpnService : VpnService() {

    private lateinit var pluginManager: PluginManager
    private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var isRunning = false
    private var tunOut: FileOutputStream? = null

    private val udpSessions = ConcurrentHashMap<String, UdpSession>()
    private val tcpSessions = ConcurrentHashMap<String, TcpSession>()

    override fun onCreate() {
        super.onCreate()
        pluginManager = PluginManager(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopVpn(); return START_NOT_STICKY }
        startForeground(NOTIFICATION_ID, buildNotification())
        pluginManager.loadPlugins()
        pluginManager.startAll()
        vpnInterface = buildVpnConnection()
        if (vpnInterface == null) { Log.e(TAG, "vpn failed"); stopVpn(); return START_NOT_STICKY }
        isRunning = true
        Thread { readLoop(vpnInterface!!) }.start()
        return START_STICKY
    }

    override fun onDestroy() { stopVpn(); super.onDestroy() }

    private fun stopVpn() {
        isRunning = false
        udpSessions.values.forEach { it.close() }; udpSessions.clear()
        tcpSessions.values.forEach { it.close() }; tcpSessions.clear()
        pluginManager.stopAll()
        try { vpnInterface?.close() } catch (_: Exception) {}; vpnInterface = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        try { stopSelf() } catch (_: Exception) {}
    }

    private fun buildVpnConnection(): ParcelFileDescriptor? = try {
        Builder()
            .setSession("FluxFabric").setMtu(1500)
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1").addDnsServer("8.8.8.8")
            .addDisallowedApplication(packageName)
            .establish()
    } catch (e: Exception) { Log.e(TAG, "establish fail", e); null }

    private fun readLoop(fd: ParcelFileDescriptor) {
        val `is` = FileInputStream(fd.fileDescriptor)
        tunOut = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        while (isRunning) {
            try {
                val n = `is`.read(buf); if (n <= 0) continue
                val raw = buf.copyOf(n)
                val pkt = Packet.parse(raw) ?: continue

                when (pkt.protocol) {
                    Packet.Protocol.TCP -> handleTcp(pkt)
                    Packet.Protocol.UDP -> handleUdp(pkt)
                    else -> {}
                }
            } catch (_: Exception) {}
        }
    }

    // ── TCP stateful proxy ───────────────────────────────

    private fun parseTcpFlags(data: ByteArray): Int {
        val ihl = (data[0].toInt() and 0x0F) * 4
        return data[ihl + 13].toInt() and 0xFF
    }

    private fun parseTcpSeq(data: ByteArray): Long {
        val ihl = (data[0].toInt() and 0x0F) * 4
        val bb = ByteBuffer.wrap(data, ihl + 4, 4)
        return bb.getInt().toLong() and 0xFFFFFFFFL
    }

    private fun parseTcpAck(data: ByteArray): Long {
        val ihl = (data[0].toInt() and 0x0F) * 4
        val bb = ByteBuffer.wrap(data, ihl + 8, 4)
        return bb.getInt().toLong() and 0xFFFFFFFFL
    }

    private inner class TcpSession(
        val clientIp: String, val clientPort: Int,
        val serverIp: String, val serverPort: Int
    ) {
        val key: String get() = "${clientIp}:${clientPort}-${serverIp}:${serverPort}"
        val reverseKey: String get() = "${serverIp}:${serverPort}-${clientIp}:${clientPort}"

        @Volatile var clientSeq: Long = 0
        @Volatile var serverSeq: Long = (System.currentTimeMillis() and 0x7FFFFFFF).toLong()
        @Volatile var established = false
        @Volatile var closed = false

        private val sock = Socket()
        private val lock = Any()

        fun connectServer(): Boolean = try {
            sock.connect(InetSocketAddress(serverIp, serverPort), 5000)
            sock.soTimeout = 10000
            Thread { serverReadLoop() }.start()
            true
        } catch (e: Exception) { Log.d(TAG, "tcp connect: ${e.message}"); close(); false }

        fun sendSynAck(clientSynSeq: Long) {
            clientSeq = (clientSynSeq + 1) and 0xFFFFFFFFL
            val ack = (clientSynSeq + 1) and 0xFFFFFFFFL
            val mySeq = serverSeq and 0xFFFFFFFFL
            writeIpPacket(ByteArray(0), serverIp, clientIp, serverPort, clientPort,
                6, mySeq, ack, 0x12)
            serverSeq = (mySeq + 1) and 0xFFFFFFFFL
        }

        fun handleData(data: ByteArray, seq: Long) {
            if (closed) return
            val payload = extractPayload(data) ?: return
            if (payload.isEmpty()) return
            clientSeq = (seq + payload.size) and 0xFFFFFFFFL
            synchronized(lock) {
                try {
                    sock.getOutputStream().write(payload)
                    sock.getOutputStream().flush()
                } catch (e: Exception) { close() }
            }
        }

        private fun serverReadLoop() {
            try {
                val rbuf = ByteArray(65535)
                while (!closed) {
                    val n = sock.getInputStream().read(rbuf)
                    if (n < 0) { sendFin(); break }
                    if (n > 0) {
                        val rbufCopy = rbuf.copyOf(n)
                        Log.d(TAG, "TCP server response ${n}B")
                        val mySeq = serverSeq and 0xFFFFFFFFL
                        val cliAck = clientSeq and 0xFFFFFFFFL
                        writeIpPacket(rbufCopy, serverIp, clientIp,
                            serverPort, clientPort, 6, mySeq, cliAck, 0x18)
                        serverSeq = (mySeq + n) and 0xFFFFFFFFL
                    }
                }
            } catch (e: SocketException) { Log.d(TAG, "TCP server: ${e.message}") } catch (_: Exception) {}
            close()
        }

        private fun sendFin() {
            val mySeq = serverSeq and 0xFFFFFFFFL
            val cliAck = clientSeq and 0xFFFFFFFFL
            writeIpPacket(ByteArray(0), serverIp, clientIp, serverPort, clientPort,
                6, mySeq, cliAck, 0x11)
            serverSeq = (mySeq + 1) and 0xFFFFFFFFL
        }

        fun sendAck() {
            val mySeq = serverSeq and 0xFFFFFFFFL
            val cliAck = (clientSeq) and 0xFFFFFFFFL
            writeIpPacket(ByteArray(0), serverIp, clientIp, serverPort, clientPort,
                6, mySeq, cliAck, 0x10)
        }

        fun close() {
            if (closed) return; closed = true
            try { sock.close() } catch (_: Exception) {}
            tcpSessions.remove(key)
            tcpSessions.remove(reverseKey)
        }

        fun halfClose() {
            try { sock.shutdownOutput() } catch (_: Exception) {}
        }
    }

    private fun handleTcp(pkt: Packet) {
        val processed = pluginManager.processSent(pkt) ?: return
        val flags = parseTcpFlags(processed.data)
        val isSyn = (flags and 0x02) != 0
        val isRst = (flags and 0x04) != 0
        val isFin = (flags and 0x01) != 0
        val isAck = (flags and 0x10) != 0
        val seq = parseTcpSeq(processed.data)
        val ack = parseTcpAck(processed.data)

        val key = "${processed.srcIp}:${processed.srcPort}-${processed.dstIp}:${processed.dstPort}"
        val revKey = "${processed.dstIp}:${processed.dstPort}-${processed.srcIp}:${processed.srcPort}"

        var session = tcpSessions[key] ?: tcpSessions[revKey]

        if (isRst) { session?.close(); return }

        if (isSyn && !isAck) {
            if (session != null) { session.close(); tcpSessions.remove(key); tcpSessions.remove(revKey) }
            val s = TcpSession(processed.srcIp, processed.srcPort, processed.dstIp, processed.dstPort)
            Log.d(TAG, "New TCP session to ${processed.dstIp}:${processed.dstPort}")
            if (!s.connectServer()) { Log.d(TAG, "TCP connectServer failed"); return }
            s.sendSynAck(seq)
            tcpSessions[key] = s
            tcpSessions[revKey] = s
            Log.d(TAG, "TCP session established, sent SYN-ACK")
            return
        }

        if (session == null) return

        if (isFin) {
            session.halfClose()
            if (!session.established) session.close()
            return
        }

        if (isAck && !session.established && (flags and 0x02) == 0) {
            Log.d(TAG, "TCP handshake complete")
            session.established = true
            session.sendAck()
            val payload = extractPayload(processed.data)
            if (payload != null && payload.isNotEmpty()) {
                Log.d(TAG, "TCP forwarding ${payload.size}B data")
                session.handleData(processed.data, seq)
            }
            return
        }

        if (session.established && (flags and 0x08) != 0) {
            Log.d(TAG, "TCP PSH data")
            session.handleData(processed.data, seq)
        } else if (session.established && isAck && !isSyn) {
            Log.d(TAG, "TCP ACK data")
            session.handleData(processed.data, seq)
        }
    }

    // ── UDP forward (session-based) ──────────────────────

    private inner class UdpSession(
        val clientIp: String, val clientPort: Int,
        val serverIp: String, val serverPort: Int
    ) {
        private val sock = DatagramSocket()
        @Volatile private var done = false

        fun start(): Boolean = try {
            sock.soTimeout = 30000
            Thread { readLoop() }.start(); true
        } catch (e: Exception) { Log.d(TAG, "udp: ${e.message}"); false }

        private fun readLoop() {
            try {
                val rbuf = ByteArray(65535)
                val rp = DatagramPacket(rbuf, rbuf.size)
                while (!done) {
                    sock.receive(rp)
                    if (rp.length > 0) {
                        writeIpPacket(rp.data, rp.length, serverIp, clientIp,
                            serverPort, clientPort, 17, 0, 0, 0)
                    }
                }
            } catch (_: SocketTimeoutException) {} catch (_: Exception) {}
            close()
        }

        fun write(data: ByteArray) = try {
            sock.send(DatagramPacket(data, data.size, InetAddress.getByName(serverIp), serverPort))
        } catch (_: Exception) { close() }

        fun close() { done = true; try { sock.close() } catch (_: Exception) {} }
    }

    private fun handleUdp(pkt: Packet) {
        val key = "${pkt.srcIp}:${pkt.srcPort}-${pkt.dstIp}:${pkt.dstPort}"
        val payload = extractPayload(pkt.data) ?: return
        val processed = pluginManager.processSent(pkt) ?: return
        val processedPayload = extractPayload(processed.data) ?: return

        var session = udpSessions[key]
        if (session == null) {
            session = UdpSession(pkt.srcIp, pkt.srcPort, pkt.dstIp, pkt.dstPort)
            if (!session.start()) return
            udpSessions[key] = session
        }
        session.write(processedPayload)
    }

    // ── Response packet crafting ─────────────────────────

    @Volatile private var ipId = 1

    private fun writeIpPacket(data: ByteArray, len: Int,
                               srcIp: String, dstIp: String,
                               srcPort: Int, dstPort: Int,
                               proto: Int, tcpSeq: Long, tcpAck: Long, tcpFlags: Int) {
        try {
            val payload = data.copyOf(len)
            val ipHdrLen = 20
            val l4HdrLen = if (proto == 17) 8 else 20
            val totalLen = ipHdrLen + l4HdrLen + payload.size
            val bb = ByteBuffer.allocate(totalLen)

            bb.put(0x45.toByte())
            bb.put(0x00.toByte())
            bb.putShort(totalLen.toShort())
            bb.putShort(((ipId++ and 0xFFFF)).toShort())
            bb.putShort(0x4000.toShort())
            bb.put(64.toByte())
            bb.put(proto.toByte())
            bb.putShort(0)
            bb.put(InetAddress.getByName(srcIp).address)
            bb.put(InetAddress.getByName(dstIp).address)

            val ipChecksum = checksum(bb.array(), 0, ipHdrLen)
            bb.putShort(10, ipChecksum)

            if (proto == 17) {
                bb.putShort(srcPort.toShort())
                bb.putShort(dstPort.toShort())
                bb.putShort((8 + payload.size).toShort())
                bb.putShort(0)
            } else {
                bb.putShort(srcPort.toShort())
                bb.putShort(dstPort.toShort())
                bb.putInt(tcpSeq.toInt())
                bb.putInt(tcpAck.toInt())
                val offFlags = (5 shl 12) or (tcpFlags and 0x3F)
                bb.putShort(offFlags.toShort())
                bb.putShort(0xFFFF.toShort())
                bb.putShort(0)
                bb.putShort(0)
            }

            bb.put(payload)

            if (proto == 6) {
                val cksum = tcpChecksum(bb.array(), ipHdrLen, srcIp, dstIp)
                Log.d(TAG, "TCP cksum=0x${(cksum.toInt() and 0xFFFF).toString(16)} len=${totalLen}")
                bb.putShort(ipHdrLen + 16, cksum)
            }

            val raw = bb.array()
            val parsed = Packet.parse(raw)
            if (parsed != null) {
                val response = pluginManager.processReceived(parsed)
                if (response != null) {
                    tunOut?.write(response.data)
                    tunOut?.flush()
                }
            }
        } catch (e: Exception) { Log.d(TAG, "write err: ${e.message}") }
    }

    private fun writeIpPacket(data: ByteArray,
                               srcIp: String, dstIp: String,
                               srcPort: Int, dstPort: Int,
                               proto: Int, tcpSeq: Long, tcpAck: Long, tcpFlags: Int) {
        writeIpPacket(data, data.size, srcIp, dstIp, srcPort, dstPort, proto, tcpSeq, tcpAck, tcpFlags)
    }

    private fun checksum(data: ByteArray, offset: Int, len: Int): Short {
        var sum = 0L; var i = offset
        while (i < offset + len - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < offset + len) sum += (data[i].toInt() and 0xFF) shl 8
        while ((sum shr 16) != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toShort()
    }

    private fun tcpChecksum(packet: ByteArray, ipHdrLen: Int, srcIp: String, dstIp: String): Short {
        val tcpLen = packet.size - ipHdrLen
        val pseudoLen = 12 + tcpLen + (if (tcpLen % 2 != 0) 1 else 0)
        val buf = ByteBuffer.allocate(pseudoLen)
        buf.put(InetAddress.getByName(srcIp).address)
        buf.put(InetAddress.getByName(dstIp).address)
        buf.put(0); buf.put(6.toByte())
        buf.putShort(tcpLen.toShort())
        buf.put(packet, ipHdrLen, tcpLen)
        if (tcpLen % 2 != 0) buf.put(0)
        return checksum(buf.array(), 0, pseudoLen)
    }

    private fun extractPayload(data: ByteArray): ByteArray? = try {
        val ihl = ((data[0].toInt() and 0x0F) * 4)
        val totalLen = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val proto = data[9].toInt() and 0xFF
        val l4size = if (proto == 17) 8 else 20
        val start = ihl + l4size; val end = minOf(data.size, totalLen)
        if (start >= end) null else data.copyOfRange(start, end)
    } catch (_: Exception) { null }

    // ── Notification ─────────────────────────────────────

    private var notifBuilder: Notification.Builder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(NOTIF_CH, "FluxFabric", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Routing traffic"
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(this, 0,
            Intent(this, FluxVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, NOTIF_CH) else Notification.Builder(this)
        notifBuilder = b
        return b.setContentTitle("FluxFabric").setContentText("Routing traffic")
            .setSmallIcon(android.R.drawable.ic_lock_lock).setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .setOngoing(true).build()
    }

    companion object {
        private const val TAG = "FluxVpnService"
        const val ACTION_STOP = "com.fluxfabric.STOP_VPN"
        private const val NOTIF_CH = "fluxfabric_vpn"
        private const val NOTIFICATION_ID = 1
    }
}
