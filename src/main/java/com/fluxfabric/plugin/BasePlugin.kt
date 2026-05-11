package com.fluxfabric.plugin

import android.content.Context
import android.content.SharedPreferences
import com.fluxfabric.model.Packet
import java.util.concurrent.ConcurrentHashMap

abstract class BasePlugin(
    override val id: String,
    override val name: String,
    override val description: String,
    override val version: String
) : TrafficPlugin {

    private var _isEnabled = true
    private var prefs: SharedPreferences? = null
    private val _settings = ConcurrentHashMap<String, Any?>()

    override val isEnabled: Boolean get() = _isEnabled

    override fun setEnabled(enabled: Boolean) {
        _isEnabled = enabled
        prefs?.edit()?.putBoolean("${id}_enabled", enabled)?.apply()
    }

    override val settings: List<PluginSetting<*>> get() = emptyList()

    override fun getSettingValue(key: String): Any? = _settings[key]

    override fun setSettingValue(key: String, value: Any) {
        _settings[key] = value
        saveSetting(key, value)
    }

    fun initSettings(context: Context) {
        prefs = context.getSharedPreferences("plugin_settings_$id", Context.MODE_PRIVATE)
        _isEnabled = prefs?.getBoolean("${id}_enabled", true) ?: true
        for (setting in settings) {
            val saved = loadSetting(setting)
            _settings[setting.key] = saved ?: setting.defaultValue
        }
    }

    private fun loadSetting(setting: PluginSetting<*>): Any? {
        val p = prefs ?: return null
        return when (setting) {
            is PluginSetting.BooleanSetting -> p.getBoolean(setting.key, setting.defaultValue)
            is PluginSetting.IntSetting -> p.getInt(setting.key, setting.defaultValue)
            is PluginSetting.StringSetting -> p.getString(setting.key, setting.defaultValue)
            is PluginSetting.ChoiceSetting -> p.getString(setting.key, setting.defaultValue)
            is PluginSetting.MultiChoiceSetting -> p.getStringSet(setting.key, setting.defaultValue)
        }
    }

    private fun saveSetting(key: String, value: Any) {
        val p = prefs ?: return
        val setting = settings.find { it.key == key } ?: return
        p.edit().apply {
            when (setting) {
                is PluginSetting.BooleanSetting -> putBoolean(key, value as Boolean)
                is PluginSetting.IntSetting -> putInt(key, value as Int)
                is PluginSetting.StringSetting -> putString(key, value as String)
                is PluginSetting.ChoiceSetting -> putString(key, value as String)
                is PluginSetting.MultiChoiceSetting -> putStringSet(key, value as Set<String>)
            }
            apply()
        }
    }

    override fun onStart() {}
    override fun onStop() {}

    override fun onPacketReceived(packet: Packet): Packet? = packet
    override fun onPacketSent(packet: Packet): Packet? = packet
}
