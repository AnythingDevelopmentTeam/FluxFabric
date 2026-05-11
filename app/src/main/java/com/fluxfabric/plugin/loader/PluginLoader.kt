package com.fluxfabric.plugin.loader

import com.fluxfabric.plugin.TrafficPlugin

interface PluginLoader {
    fun discover(): List<TrafficPlugin>
}
