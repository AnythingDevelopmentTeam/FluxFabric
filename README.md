# FluxFabric

A VPN-based network traffic interceptor for Android with a plugin system supporting Kotlin, Java, and Python plugins.

## Features

- **VPN-based traffic routing** — intercepts all device traffic through a local VPN
- **Plugin system** — process packets in-flight using Kotlin, Java, or Python
- **Plugin chain** — packets flow through each enabled plugin in order; any plugin can inspect, modify, or drop packets
- **Plugin settings** — each plugin can expose configurable settings persisted via SharedPreferences
- **Sideload plugins** — import `.jar`/`.dex`/`.py` plugins from local storage or remote URLs
- **Built-in plugins** — Ad Blocker (DNS-based) and Request Logger

## Plugin Types

| Type | Language | Extension | Loader |
|------|----------|-----------|--------|
| Built-in | Kotlin | — | Compiled into app |
| JAR/DEX | Java/Kotlin | `.jar` / `.dex` | `DexClassLoader` |
| Python | Python | `.py` | Chaquopy |

## Build Flavors

| Flavor | Store | Description |
|--------|-------|-------------|
| `oss` | Disabled | Open-source build without store features |
| `play` | Enabled | Includes plugin store placeholder |

### Building

```bash
# OSS build (default)
./gradlew assembleOssDebug

# Play build (with store)
./gradlew assemblePlayDebug

# Release build
./gradlew assembleOssRelease
./gradlew assemblePlayRelease
```

## Project Structure

```
FluxFabric/
  app/
    src/
      main/
        java/com/fluxfabric/
          model/          # Packet data model
          plugin/         # Plugin interface, base, manager, settings
            builtin/      # Built-in plugin implementations
            loader/       # Plugin loaders (classloader, python)
          store/          # Plugin store placeholder
          ui/theme/       # Material3 theme
          vpn/            # VPN service implementation
        python/
          plugins/        # Python plugin assets
  plugins/
    sample-java/          # Sample Java plugin project
    sample-kotlin/        # Sample Kotlin plugin project
    sample-python/        # Sample Python plugin project
```

## Writing a Plugin

### Kotlin/Java (JAR/DEX)

Create a class extending `BasePlugin`:

```kotlin
class MyPlugin : BasePlugin(
    id = "my-plugin",
    name = "My Plugin",
    description = "Does something with packets",
    version = "1.0.0"
) {
    override fun onPacketSent(packet: Packet): Packet? {
        // Inspect or modify the packet
        return packet // return null to drop
    }
}
```

### Python

Create a `.py` file with hook functions:

```python
def on_start():
    pass

def on_stop():
    pass

def on_packet_received(data: bytes) -> bytes:
    return data  # return None to drop

def on_packet_sent(data: bytes) -> bytes:
    return data
```

## License

GNU General Public License v3.0
