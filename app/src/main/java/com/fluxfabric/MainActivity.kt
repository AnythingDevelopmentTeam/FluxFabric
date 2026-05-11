package com.fluxfabric

import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fluxfabric.BuildConfig
import com.fluxfabric.plugin.PluginManager
import com.fluxfabric.plugin.PluginSetting
import com.fluxfabric.plugin.TrafficPlugin
import com.fluxfabric.store.PluginStoreScreen
import com.fluxfabric.ui.theme.FluxFabricTheme
import com.fluxfabric.vpn.FluxVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val vpnRequestCode = 1000
    private lateinit var pluginManager: PluginManager
    private var isVpnActive by mutableStateOf(false)
    private var isPreparing by mutableStateOf(false)

    private var onPluginsChanged: (() -> Unit)? = null

    private val importPluginLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val result = pluginManager.importPlugin(uri)
            if (result != null) {
                Toast.makeText(this, result, Toast.LENGTH_LONG).show()
            } else {
                pluginManager.loadPlugins()
                onPluginsChanged?.invoke()
                Toast.makeText(this, "Plugin imported successfully", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pluginManager = PluginManager(applicationContext)

        setContent {
            FluxFabricTheme {
                FluxFabricApp(
                    isVpnActive = isVpnActive,
                    isPreparing = isPreparing,
                    onToggleVpn = { toggleVpn() },
                    pluginManager = pluginManager,
                    onImportPlugin = { importPluginLauncher.launch(arrayOf("application/*", "text/*")) },
                    onPluginsChanged = { onPluginsChanged = it }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        pluginManager.loadPlugins()
    }

    private fun toggleVpn() {
        if (isVpnActive) {
            stopService(Intent(this, FluxVpnService::class.java))
            isVpnActive = false
        } else {
            isPreparing = true
            val intent = VpnService.prepare(this)
            if (intent != null) {
                startActivityForResult(intent, vpnRequestCode)
            } else {
                onActivityResult(vpnRequestCode, RESULT_OK, null)
            }
        }
    }

    @Deprecated("Use registerForActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        isPreparing = false
        if (requestCode == vpnRequestCode && resultCode == RESULT_OK) {
            startForegroundService(Intent(this, FluxVpnService::class.java))
            isVpnActive = true
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FluxFabricApp(
    isVpnActive: Boolean,
    isPreparing: Boolean,
    onToggleVpn: () -> Unit,
    pluginManager: PluginManager,
    onImportPlugin: () -> Unit = {},
    onPluginsChanged: ((() -> Unit) -> Unit)? = null
) {
    val plugins = remember { mutableStateListOf<TrafficPlugin>() }
    var selectedTab by remember { mutableIntStateOf(0) }
    var showUrlDialog by remember { mutableStateOf(false) }

    fun refreshPlugins() {
        pluginManager.loadPlugins()
        plugins.clear()
        plugins.addAll(pluginManager.getPlugins())
    }

    LaunchedEffect(Unit) {
        refreshPlugins()
        onPluginsChanged?.invoke(::refreshPlugins)
    }

    if (showUrlDialog) {
        val scope = rememberCoroutineScope()
        SideloadUrlDialog(
            onDismiss = { showUrlDialog = false },
            onImport = { url ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        pluginManager.importFromUrl(url)
                    }.let { result ->
                        if (result != null) {
                            Toast.makeText(
                                pluginManager.context,
                                result,
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            pluginManager.loadPlugins()
                            refreshPlugins()
                            Toast.makeText(
                                pluginManager.context,
                                "Plugin downloaded and imported",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    showUrlDialog = false
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "FluxFabric",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = { showUrlDialog = true }) {
                        Icon(Icons.Default.CloudDownload, contentDescription = "Import from URL")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // VPN Toggle Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isVpnActive)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "VPN Status",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            if (isVpnActive) "● Active" else "○ Inactive",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isVpnActive) Color(0xFF4CAF50) else Color.Gray
                        )
                    }
                    FilledTonalButton(
                        onClick = onToggleVpn,
                        enabled = !isPreparing
                    ) {
                        Icon(
                            Icons.Default.PowerSettingsNew,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (isVpnActive) "Disconnect" else "Connect")
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Dashboard") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Plugins (${plugins.size})") }
                )
                if (BuildConfig.ENABLE_STORE) {
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("Store") }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            when (selectedTab) {
                0 -> DashboardTab(isVpnActive)
                1 -> PluginsTab(
                    plugins = plugins,
                    onImportPlugin = onImportPlugin
                )
                2 -> if (BuildConfig.ENABLE_STORE) PluginStoreScreen()
            }
        }
    }
}

@Composable
fun DashboardTab(isVpnActive: Boolean) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "Traffic Overview",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard("Packets In", "--", Modifier.weight(1f))
            StatCard("Packets Out", "--", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard("Active Plugins", "--", Modifier.weight(1f))
            StatCard("Uptime", "--", Modifier.weight(1f))
        }

        Spacer(Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Plugin Chain",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Packets flow through each enabled plugin in order.\nAny plugin can modify or drop a packet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun PluginsTab(
    plugins: List<TrafficPlugin>,
    onImportPlugin: () -> Unit = {}
) {
    var showImportMenu by remember { mutableStateOf(false) }
    var selectedPlugin by remember { mutableStateOf<TrafficPlugin?>(null) }

    selectedPlugin?.let { plugin ->
        PluginSettingsDialog(
            plugin = plugin,
            onDismiss = { selectedPlugin = null }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "Installed Plugins",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Write plugins in Kotlin, Java, or Python",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                FilledTonalButton(onClick = { showImportMenu = true }) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Import")
                }
                DropdownMenu(
                    expanded = showImportMenu,
                    onDismissRequest = { showImportMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("From file (.jar/.dex/.py)") },
                        onClick = {
                            showImportMenu = false
                            onImportPlugin()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.FileOpen, contentDescription = null)
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (plugins.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Extension,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = Color.Gray
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("No plugins installed", color = Color.Gray)
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(plugins) { plugin ->
                    PluginCard(
                        plugin = plugin,
                        onSettingsClick = { selectedPlugin = plugin }
                    )
                }
            }
        }
    }
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun PluginCard(
    plugin: TrafficPlugin,
    onSettingsClick: () -> Unit = {}
) {
    var enabled by remember { mutableStateOf(plugin.isEnabled) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSettingsClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        plugin.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (plugin.settings.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = "Settings available",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    plugin.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "v${plugin.version} — ${plugin.id}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onSettingsClick) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Settings",
                        modifier = Modifier.size(20.dp)
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        plugin.setEnabled(it)
                    }
                )
            }
        }
    }
}

// ── Plugin Settings Dialog ───────────────────────────────

@Composable
fun PluginSettingsDialog(
    plugin: TrafficPlugin,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(plugin.name, fontWeight = FontWeight.Bold)
                Text(
                    plugin.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            if (plugin.settings.isEmpty()) {
                Text(
                    "This plugin has no configurable settings.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    plugin.settings.forEach { setting ->
                        SettingRow(plugin = plugin, setting = setting)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingRow(
    plugin: TrafficPlugin,
    setting: PluginSetting<*>
) {
    val currentValue = remember(setting.key) { plugin.getSettingValue(setting.key) ?: setting.defaultValue }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            setting.label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium
        )
        Text(
            setting.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))

        when (setting) {
            is PluginSetting.BooleanSetting -> {
                Switch(
                    checked = currentValue as? Boolean ?: setting.defaultValue,
                    onCheckedChange = { plugin.setSettingValue(setting.key, it) }
                )
            }
            is PluginSetting.IntSetting -> {
                OutlinedTextField(
                    value = (currentValue as? Int)?.toString() ?: setting.defaultValue.toString(),
                    onValueChange = { text ->
                        text.toIntOrNull()?.let { v ->
                            val clamped = when {
                                setting.min != null && v < setting.min -> setting.min
                                setting.max != null && v > setting.max -> setting.max
                                else -> v
                            }
                            plugin.setSettingValue(setting.key, clamped)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }
            is PluginSetting.StringSetting -> {
                OutlinedTextField(
                    value = currentValue as? String ?: setting.defaultValue,
                    onValueChange = { plugin.setSettingValue(setting.key, it) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = if (setting.multiline) 3 else 1,
                    maxLines = if (setting.multiline) 5 else 1
                )
            }
            is PluginSetting.ChoiceSetting -> {
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = currentValue as? String ?: setting.defaultValue,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        setting.options.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    plugin.setSettingValue(setting.key, option)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
            is PluginSetting.MultiChoiceSetting -> {
                val selected = (currentValue as? Set<*>)?.map { it as? String ?: "" }?.toSet()
                    ?: setting.defaultValue
                setting.options.forEach { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = option in selected,
                            onCheckedChange = { checked ->
                                val newSet = if (checked) selected + option else selected - option
                                plugin.setSettingValue(setting.key, newSet)
                            }
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(option, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

// ── Sideload URL Dialog ──────────────────────────────────

@Composable
fun SideloadUrlDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!importing) onDismiss() },
        title = { Text("Import Plugin from URL") },
        text = {
            Column {
                Text(
                    "Download a .jar, .dex, or .py plugin from a URL.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://example.com/plugin.jar") },
                    singleLine = true,
                    enabled = !importing,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                if (importing) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Downloading...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (error != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        error!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { importing = true; error = null; onImport(url) },
                enabled = url.isNotBlank() && !importing
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !importing
            ) {
                Text("Cancel")
            }
        }
    )
}
