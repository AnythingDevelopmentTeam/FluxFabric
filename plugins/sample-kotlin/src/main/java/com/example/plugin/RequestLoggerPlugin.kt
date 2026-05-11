package com.example.plugin

import android.util.Log
import com.fluxfabric.model.Packet
import com.fluxfabric.plugin.BasePlugin

class RequestLoggerPlugin : BasePlugin(
    id = "request-logger",
    name = "Request Logger",
    description = "Logs all HTTP/HTTPS requests with URL info",
    version = "1.0.0"
) {
    private var requestCount = 0

    override fun onStart() {
        requestCount = 0
        Log.d(TAG, "RequestLogger started")
    }

    override fun onStop() {
        Log.d(TAG, "RequestLogger stopped — logged $requestCount packets")
    }

    override fun onPacketSent(packet: Packet): Packet? {
        if (packet.dstPort == 80 || packet.dstPort == 443) {
            requestCount++
            val direction = if (packet.dstPort == 443) "HTTPS" else "HTTP"
            Log.d(TAG, "[$direction] $requestCount: ${packet.srcIp}:${packet.srcPort} → ${packet.dstIp}:${packet.dstPort} (${packet.length} bytes)")
        }
        return packet
    }

    companion object {
        private const val TAG = "RequestLogger"
    }
}
