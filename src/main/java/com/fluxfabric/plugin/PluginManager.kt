package com.fluxfabric.plugin

import android.content.Context
import android.net.Uri
import android.util.Log
import com.fluxfabric.model.Packet
import com.fluxfabric.plugin.builtin.AdBlockerPlugin
import com.fluxfabric.plugin.builtin.RequestLoggerPlugin
import com.fluxfabric.plugin.loader.ClassLoaderPluginLoader
import com.fluxfabric.plugin.loader.PythonPluginLoader
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class PluginManager(val context: Context) {
    private val builtins = listOf(
        AdBlockerPlugin(),
        RequestLoggerPlugin()
    )

    private val plugins = CopyOnWriteArrayList<TrafficPlugin>()
    private val loaders = listOf(
        ClassLoaderPluginLoader(context),
        PythonPluginLoader(context)
    )

    fun loadPlugins() {
        plugins.clear()
        for (plugin in builtins + loaders.flatMap { loader ->
            try { loader.discover() } catch (e: Exception) {
                Log.e(TAG, "Failed to load plugins from ${loader::class.simpleName}", e)
                emptyList()
            }
        }) {
            if (plugin is BasePlugin) {
                plugin.initSettings(context)
            }
            plugins.add(plugin)
        }
        Log.i(TAG, "Loaded ${plugins.size} plugins total")
    }

    fun importPlugin(uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return "Cannot open file"

            val fileName = getFileName(uri) ?: "plugin"
            val bytes = inputStream.readBytes()
            inputStream.close()

            when {
                fileName.endsWith(".jar") || fileName.endsWith(".dex") -> {
                    val pluginsDir = File(context.filesDir, "plugins")
                    pluginsDir.mkdirs()
                    val dest = File(pluginsDir, fileName)
                    dest.writeBytes(bytes)
                    Log.i(TAG, "Imported JAR/DEX plugin: $fileName")
                    null
                }
                fileName.endsWith(".py") -> {
                    val pythonDir = File(context.filesDir, "python_plugins")
                    pythonDir.mkdirs()
                    val dest = File(pythonDir, fileName)
                    dest.writeBytes(bytes)
                    Log.i(TAG, "Imported Python plugin: $fileName")
                    null
                }
                else -> "Unsupported file type (use .jar, .dex, or .py)"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import plugin", e)
            "Import failed: ${e.message}"
        }
    }

    fun importFromUrl(url: String): String? {
        return try {
            val fileName = url.substringAfterLast("/").takeIf { it.isNotBlank() } ?: "plugin.jar"
            val bytes = java.net.URL(url).readBytes()
            when {
                fileName.endsWith(".jar") || fileName.endsWith(".dex") -> {
                    val pluginsDir = File(context.filesDir, "plugins")
                    pluginsDir.mkdirs()
                    val dest = File(pluginsDir, fileName)
                    dest.writeBytes(bytes)
                    Log.i(TAG, "Downloaded JAR/DEX plugin: $fileName")
                    null
                }
                fileName.endsWith(".py") -> {
                    val pythonDir = File(context.filesDir, "python_plugins")
                    pythonDir.mkdirs()
                    val dest = File(pythonDir, fileName)
                    dest.writeBytes(bytes)
                    Log.i(TAG, "Downloaded Python plugin: $fileName")
                    null
                }
                else -> "Unsupported file type (use .jar, .dex, or .py)"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import plugin from URL", e)
            "Download failed: ${e.message}"
        }
    }

    private fun getFileName(uri: Uri): String? {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && it.moveToFirst()) it.getString(nameIndex) else null
        }
    }

    fun startAll() {
        for (plugin in plugins) {
            try {
                if (plugin.isEnabled) plugin.onStart()
            } catch (e: Exception) {
                Log.e(TAG, "Error starting plugin ${plugin.id}", e)
            }
        }
    }

    fun stopAll() {
        for (plugin in plugins) {
            try {
                if (plugin.isEnabled) plugin.onStop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping plugin ${plugin.id}", e)
            }
        }
    }

    fun processReceived(packet: Packet): Packet? {
        var current = packet
        for (plugin in plugins) {
            if (!plugin.isEnabled) continue
            current = plugin.onPacketReceived(current) ?: return null
        }
        return current
    }

    fun processSent(packet: Packet): Packet? {
        var current = packet
        for (plugin in plugins) {
            if (!plugin.isEnabled) continue
            current = plugin.onPacketSent(current) ?: return null
        }
        return current
    }

    fun getPlugins(): List<TrafficPlugin> = plugins.toList()

    fun getPlugin(id: String): TrafficPlugin? = plugins.find { it.id == id }

    companion object {
        private const val TAG = "PluginManager"
    }
}
