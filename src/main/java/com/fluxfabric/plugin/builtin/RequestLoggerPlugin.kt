package com.fluxfabric.plugin.builtin

import android.util.Log
import com.fluxfabric.model.Packet
import com.fluxfabric.plugin.BasePlugin
import com.fluxfabric.plugin.PluginSetting

class RequestLoggerPlugin : BasePlugin(
    id = "request-logger",
    name = "Request Logger",
    description = "Logs all HTTP/HTTPS requests",
    version = "1.0.0"
) {
    private var requestCount = 0

    override val settings: List<PluginSetting<*>> = listOf(
        PluginSetting.ChoiceSetting(
            key = "log_level",
            label = "Log Level",
            description = "Verbosity of logging",
            defaultValue = "info",
            options = listOf("debug", "info", "warn")
        ),
        PluginSetting.MultiChoiceSetting(
            key = "monitored_ports",
            label = "Monitored Ports",
            description = "Ports to monitor for HTTP traffic",
            defaultValue = setOf("80", "443"),
            options = listOf("80", "443", "8080", "8443", "3000", "5000", "8000")
        ),
        PluginSetting.BooleanSetting(
            key = "log_payload_size",
            label = "Log Payload Size",
            description = "Include payload byte size in logs",
            defaultValue = true
        )
    )

    override fun onStart() {
        requestCount = 0
        Log.d(TAG, "RequestLogger started")
    }

    override fun onStop() {
        Log.d(TAG, "RequestLogger stopped — logged $requestCount packets")
    }

    override fun onPacketSent(packet: Packet): Packet? {
        val ports = (getSettingValue("monitored_ports") as? Set<*>)?.mapNotNull {
            (it as? String)?.toIntOrNull()
        } ?: listOf(80, 443)

        if (packet.dstPort in ports) {
            requestCount++
            val proto = if (packet.dstPort == 443) "HTTPS" else "HTTP"
            val level = (getSettingValue("log_level") as? String) ?: "info"
            val logPayload = (getSettingValue("log_payload_size") as? Boolean) ?: true
            val size = if (logPayload) " (${packet.length}B)" else ""
            val msg = "[$proto] #$requestCount: ${packet.srcIp}:${packet.srcPort} → ${packet.dstIp}:${packet.dstPort}$size"
            when (level) {
                "debug" -> Log.d(TAG, msg)
                "warn" -> Log.w(TAG, msg)
                else -> Log.i(TAG, msg)
            }
        }
        return packet
    }

    companion object {
        private const val TAG = "RequestLogger"
    }
}
