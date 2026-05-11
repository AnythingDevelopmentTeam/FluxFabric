package com.fluxfabric.plugin

import com.fluxfabric.model.Packet

interface TrafficPlugin {
    val id: String
    val name: String
    val description: String
    val version: String

    fun onStart()
    fun onStop()

    fun onPacketReceived(packet: Packet): Packet?
    fun onPacketSent(packet: Packet): Packet?

    val isEnabled: Boolean
    fun setEnabled(enabled: Boolean)

    val settings: List<PluginSetting<*>>

    fun getSettingValue(key: String): Any?
    fun setSettingValue(key: String, value: Any)
}
