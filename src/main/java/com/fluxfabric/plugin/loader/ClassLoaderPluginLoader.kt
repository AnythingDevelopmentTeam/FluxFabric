package com.fluxfabric.plugin.loader

import android.content.Context
import android.util.Log
import com.fluxfabric.plugin.TrafficPlugin
import dalvik.system.DexClassLoader
import java.io.File

class ClassLoaderPluginLoader(private val context: Context) : PluginLoader {

    override fun discover(): List<TrafficPlugin> {
        val plugins = mutableListOf<TrafficPlugin>()

        // Built-in plugins (registered via meta-inf or code)
        discoverBuiltin(plugins)

        // External plugins from plugins directory
        discoverExternal(plugins)

        return plugins
    }

    private fun discoverBuiltin(plugins: MutableList<TrafficPlugin>) {
        try {
            val serviceLoader = java.util.ServiceLoader.load(TrafficPlugin::class.java)
            for (plugin in serviceLoader) {
                plugins.add(plugin)
                Log.d(TAG, "Discovered built-in plugin: ${plugin.id}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "ServiceLoader discovery failed", e)
        }
    }

    private fun discoverExternal(plugins: MutableList<TrafficPlugin>) {
        val pluginsDir = File(context.filesDir, "plugins")
        if (!pluginsDir.exists()) return

        pluginsDir.listFiles { f -> f.extension == "jar" || f.extension == "dex" }?.forEach { file ->
            try {
                val dexLoader = DexClassLoader(
                    file.absolutePath,
                    context.codeCacheDir.absolutePath,
                    null,
                    this::class.java.classLoader
                )
                val pluginClasses = dexLoader.loadClass("com.fluxfabric.plugin.PluginEntry")
                if (TrafficPlugin::class.java.isAssignableFrom(pluginClasses)) {
                    val plugin = pluginClasses.getDeclaredConstructor().newInstance() as TrafficPlugin
                    plugins.add(plugin)
                    Log.d(TAG, "Discovered external plugin: ${plugin.id} from ${file.name}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load plugin from ${file.name}", e)
            }
        }
    }

    companion object {
        private const val TAG = "ClassLoaderPluginLoader"
    }
}
