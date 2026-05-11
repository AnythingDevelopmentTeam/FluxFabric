package com.fluxfabric.plugin

sealed class PluginSetting<T>(
    val key: String,
    val label: String,
    val description: String,
    val defaultValue: T
) {
    class BooleanSetting(
        key: String, label: String, description: String, defaultValue: Boolean = false
    ) : PluginSetting<Boolean>(key, label, description, defaultValue)

    class IntSetting(
        key: String, label: String, description: String, defaultValue: Int = 0,
        val min: Int? = null, val max: Int? = null
    ) : PluginSetting<Int>(key, label, description, defaultValue)

    class StringSetting(
        key: String, label: String, description: String, defaultValue: String = "",
        val multiline: Boolean = false
    ) : PluginSetting<String>(key, label, description, defaultValue)

    class ChoiceSetting(
        key: String, label: String, description: String, defaultValue: String = "",
        val options: List<String> = emptyList()
    ) : PluginSetting<String>(key, label, description, defaultValue)

    class MultiChoiceSetting(
        key: String, label: String, description: String, defaultValue: Set<String> = emptySet(),
        val options: List<String> = emptyList()
    ) : PluginSetting<Set<String>>(key, label, description, defaultValue)
}
