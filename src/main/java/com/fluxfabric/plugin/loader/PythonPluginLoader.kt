package com.fluxfabric.plugin.loader

import android.content.Context
import android.util.Log
import com.fluxfabric.model.Packet
import com.fluxfabric.plugin.BasePlugin
import com.fluxfabric.plugin.PluginSetting
import com.fluxfabric.plugin.TrafficPlugin
import java.io.File

class PythonPluginLoader(private val context: Context) : PluginLoader {

    private val knownPlugins = listOf(
        "plugins.hex_dumper"
    )

    override fun discover(): List<TrafficPlugin> {
        val plugins = mutableListOf<TrafficPlugin>()

        try {
            val pythonClass = Class.forName("com.chaquo.python.Python")
            val getInstance = pythonClass.getMethod("getInstance")
            val python = getInstance.invoke(null)
            val getModule = python::class.java.getMethod("getModule", String::class.java)

            // Add external Python plugins directory to sys.path
            addExternalPath(python)

            for (moduleName in knownPlugins) {
                try {
                    val mod = getModule.invoke(python, moduleName)
                    val plugin = PythonPluginAdapter(moduleName, mod)
                    plugins.add(plugin)
                    Log.i(TAG, "Loaded Python plugin: ${plugin.id}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load Python module: $moduleName", e)
                }
            }

            // Discover external Python plugins from filesDir/python_plugins/
            discoverExternal(python, getModule, plugins)
        } catch (e: Exception) {
            Log.w(TAG, "Chaquopy not available; Python plugins disabled", e)
        }

        return plugins
    }

    private fun addExternalPath(python: Any) {
        try {
            val pythonDir = File(context.filesDir, "python_plugins")
            if (!pythonDir.exists()) return
            val sys = python::class.java.getMethod("getModule", String::class.java)
                .invoke(python, "sys")
            val path = sys::class.java.getMethod("get", String::class.java)
                .invoke(sys, "path")
            path::class.java.getMethod("callAttr", String::class.java, Any::class.java)
                .invoke(path, "append", pythonDir.absolutePath)
            Log.i(TAG, "Added ${pythonDir.absolutePath} to Python sys.path")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add external Python path", e)
        }
    }

    private fun discoverExternal(
        python: Any,
        getModule: java.lang.reflect.Method,
        plugins: MutableList<TrafficPlugin>
    ) {
        val pythonDir = File(context.filesDir, "python_plugins")
        if (!pythonDir.exists()) return

        pythonDir.listFiles { f -> f.extension == "py" }?.forEach { file ->
            val moduleName = file.nameWithoutExtension
            if (knownPlugins.any { it.endsWith(moduleName) }) return@forEach
            try {
                val mod = getModule.invoke(python, moduleName)
                val plugin = PythonPluginAdapter(moduleName, mod)
                plugins.add(plugin)
                Log.i(TAG, "Loaded external Python plugin: ${plugin.id}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load external Python module: $moduleName", e)
            }
        }
    }

    companion object {
        private const val TAG = "PythonPluginLoader"
    }
}

class PythonPluginAdapter(
    private val moduleName: String,
    private val pythonModule: Any
) : BasePlugin(
    id = moduleName.split(".").last(),
    name = moduleName.split(".").last().replace("_", " "),
    description = "Python plugin: $moduleName",
    version = "1.0"
) {
    private var onReceivedMethod: java.lang.reflect.Method? = null
    private var onSentMethod: java.lang.reflect.Method? = null
    private var onStartMethod: java.lang.reflect.Method? = null
    private var onStopMethod: java.lang.reflect.Method? = null

    override val settings: List<PluginSetting<*>> = listOf(
        PluginSetting.BooleanSetting(
            key = "enabled",
            label = "Enabled",
            description = "Enable this Python plugin",
            defaultValue = true
        )
    )

    init {
        try {
            val modClass = pythonModule::class.java
            onReceivedMethod = try {
                modClass.getMethod("on_packet_received", ByteArray::class.java)
            } catch (_: Exception) { null }
            onSentMethod = try {
                modClass.getMethod("on_packet_sent", ByteArray::class.java)
            } catch (_: Exception) { null }
            onStartMethod = try {
                modClass.getMethod("on_start")
            } catch (_: Exception) { null }
            onStopMethod = try {
                modClass.getMethod("on_stop")
            } catch (_: Exception) { null }
            Log.d(TAG, "Loaded: $moduleName recv=${onReceivedMethod != null} sent=${onSentMethod != null}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inspect Python module: $moduleName", e)
        }
    }

    override fun onStart() {
        try { onStartMethod?.invoke(pythonModule) } catch (_: Exception) {}
    }

    override fun onStop() {
        try { onStopMethod?.invoke(pythonModule) } catch (_: Exception) {}
    }

    override fun onPacketReceived(packet: Packet): Packet? {
        if (onReceivedMethod == null) return packet
        return try {
            val result = onReceivedMethod!!.invoke(pythonModule, packet.data)
            if (result == null) null
            else Packet.parse(result as ByteArray) ?: packet
        } catch (e: Exception) {
            Log.e(TAG, "Python on_packet_received error", e)
            packet
        }
    }

    override fun onPacketSent(packet: Packet): Packet? {
        if (onSentMethod == null) return packet
        return try {
            val result = onSentMethod!!.invoke(pythonModule, packet.data)
            if (result == null) null
            else Packet.parse(result as ByteArray) ?: packet
        } catch (e: Exception) {
            Log.e(TAG, "Python on_packet_sent error", e)
            packet
        }
    }

    companion object {
        private const val TAG = "PythonPluginAdapter"
    }
}
