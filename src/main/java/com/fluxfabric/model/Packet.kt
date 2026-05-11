package com.fluxfabric.model

import java.net.InetAddress
import java.nio.ByteBuffer

data class Packet(
    val data: ByteArray,
    val protocol: Protocol,
    val srcIp: String,
    val dstIp: String,
    val srcPort: Int,
    val dstPort: Int,
    val timestamp: Long = System.currentTimeMillis()
) {
    enum class Protocol(val value: Int) {
        ICMP(1),
        TCP(6),
        UDP(17);

        companion object {
            fun from(value: Int) = entries.firstOrNull { it.value == value }
        }
    }

    val length: Int get() = data.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Packet) return false
        return data.contentEquals(other.data) &&
                protocol == other.protocol &&
                srcIp == other.srcIp &&
                dstIp == other.dstIp &&
                srcPort == other.srcPort &&
                dstPort == other.dstPort
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + protocol.hashCode()
        result = 31 * result + srcIp.hashCode()
        result = 31 * result + dstIp.hashCode()
        result = 31 * result + srcPort
        result = 31 * result + dstPort
        return result
    }

    companion object {
        fun parse(raw: ByteArray): Packet? {
            if (raw.size < 20) return null
            val bb = ByteBuffer.wrap(raw)
            val versionAndIhl = bb.get().toInt() and 0xFF
            val ihl = (versionAndIhl and 0x0F) * 4
            if (raw.size < ihl) return null

            val protocol = Protocol.from(bb.get(9).toInt() and 0xFF) ?: return null

            val srcBytes = ByteArray(4)
            val dstBytes = ByteArray(4)
            bb.position(ihl - 8)
            bb.get(srcBytes)
            bb.get(dstBytes)

            val srcIp = InetAddress.getByAddress(srcBytes).hostAddress ?: ""
            val dstIp = InetAddress.getByAddress(dstBytes).hostAddress ?: ""

            var srcPort = 0
            var dstPort = 0
            if (protocol == Protocol.TCP || protocol == Protocol.UDP) {
                if (raw.size >= ihl + 4) {
                    val portBb = ByteBuffer.wrap(raw, ihl, 4)
                    srcPort = (portBb.get().toInt() and 0xFF) shl 8 or (portBb.get().toInt() and 0xFF)
                    dstPort = (portBb.get().toInt() and 0xFF) shl 8 or (portBb.get().toInt() and 0xFF)
                }
            }

            return Packet(
                data = raw,
                protocol = protocol,
                srcIp = srcIp,
                dstIp = dstIp,
                srcPort = srcPort,
                dstPort = dstPort
            )
        }
    }
}
