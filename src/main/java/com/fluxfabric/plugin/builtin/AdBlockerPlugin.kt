package com.fluxfabric.plugin.builtin

import android.util.Log
import com.fluxfabric.model.Packet
import com.fluxfabric.plugin.BasePlugin
import com.fluxfabric.plugin.PluginSetting

class AdBlockerPlugin : BasePlugin(
    id = "ad-blocker",
    name = "Ad Blocker",
    description = "Blocks known ad/tracking domains via DNS filtering",
    version = "1.0.0"
) {
    private var blockedCount = 0

    override val settings: List<PluginSetting<*>> = listOf(
        PluginSetting.BooleanSetting(
            key = "block_ads",
            label = "Block Ad Domains",
            description = "Block known advertising and tracking domains",
            defaultValue = true
        ),
        PluginSetting.StringSetting(
            key = "custom_domains",
            label = "Custom Domains",
            description = "Additional domains to block (comma-separated)",
            defaultValue = ""
        ),
        PluginSetting.IntSetting(
            key = "max_blocked",
            label = "Max Blocked",
            description = "Maximum number of blocked requests before pausing (0 = unlimited)",
            defaultValue = 0,
            min = 0,
            max = 10000
        )
    )

    private val builtinDomains = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google.com", "adsrvr.org", "adnxs.com", "rubiconproject.com",
        "adform.net", "criteo.com", "casalemedia.com", "moatads.com",
        "outbrain.com", "taboola.com", "pubmatic.com", "openx.net",
        "appnexus.com", "adzerk.net", "scorecardresearch.com", "quantserve.com",
        "exelator.com", "bluekai.com", "demdex.net"
    )

    private val activeDomains: Set<String>
        get() {
            val domains = mutableSetOf<String>()
            if (getSettingValue("block_ads") == true) {
                domains.addAll(builtinDomains)
            }
            val custom = (getSettingValue("custom_domains") as? String)?.takeIf { it.isNotBlank() }
            if (custom != null) {
                domains.addAll(custom.split(",").map { it.trim() }.filter { it.isNotEmpty() })
            }
            return domains
        }

    override fun onStart() {
        blockedCount = 0
        Log.d(TAG, "AdBlocker started (${activeDomains.size} domains)")
    }

    override fun onStop() {
        Log.d(TAG, "AdBlocker stopped — blocked $blockedCount requests")
    }

    override fun onPacketSent(packet: Packet): Packet? {
        val max = (getSettingValue("max_blocked") as? Int) ?: 0
        if (max > 0 && blockedCount >= max) return packet
        if (packet.protocol == Packet.Protocol.UDP && packet.dstPort == 53) {
            val domain = extractDnsQuery(packet.data)
            if (domain != null && isBlocked(domain)) {
                blockedCount++
                Log.i(TAG, "Blocked DNS: $domain")
                return null
            }
        }
        return packet
    }

    private fun extractDnsQuery(data: ByteArray): String? {
        return try {
            val bb = java.nio.ByteBuffer.wrap(data)
            val versionAndIhl = bb.get().toInt() and 0xFF
            val ihl = (versionAndIhl and 0x0F) * 4
            val udpOffset = ihl + 8
            if (data.size <= udpOffset + 12) return null
            val queryStart = udpOffset + 12
            val domain = StringBuilder()
            var pos = queryStart
            while (pos < data.size) {
                val len = data[pos].toInt() and 0xFF
                if (len == 0) break
                pos++
                if (pos + len > data.size) break
                if (domain.isNotEmpty()) domain.append(".")
                for (i in 0 until len) {
                    domain.append((data[pos + i].toInt() and 0xFF).toChar())
                }
                pos += len
            }
            domain.toString().lowercase().ifEmpty { null }
        } catch (_: Exception) { null }
    }

    private fun isBlocked(domain: String): Boolean {
        return activeDomains.any { domain.endsWith(it) }
    }

    companion object {
        private const val TAG = "AdBlocker"
    }
}
