"""
Packet Inspector - Python plugin for FluxFabric

Inspects and logs network packets with payload analysis.
Can be configured to block specific patterns.

Configuration (set via environment or config file):
  BLOCK_STRINGS: comma-separated list of strings to block
  LOG_LEVEL: debug, info, warn (default: info)
"""

import json
import logging
from datetime import datetime

# User configuration
BLOCK_STRINGS = __builtins__.get("BLOCK_STRINGS", "").split(",") if __builtins__.get("BLOCK_STRINGS") else []
LOG_LEVEL = __builtins__.get("LOG_LEVEL", "info").lower()

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("PacketInspector")

_packet_count = {"received": 0, "sent": 0, "blocked": 0}


def on_start():
    _packet_count["received"] = 0
    _packet_count["sent"] = 0
    _packet_count["blocked"] = 0
    log.info(f"PacketInspector started (blocking {len(BLOCK_STRINGS)} patterns)")


def on_stop():
    log.info(
        f"PacketInspector stopped - "
        f"rcvd={_packet_count['received']} "
        f"sent={_packet_count['sent']} "
        f"blocked={_packet_count['blocked']}"
    )


def on_packet_received(packet_data: bytes) -> bytes:
    _packet_count["received"] += 1
    packet = _parse_ip(packet_data)
    if packet is None:
        return packet_data

    if _should_block(packet):
        _packet_count["blocked"] += 1
        log.warning(f"Blocked inbound: {packet['src_ip']}:{packet['src_port']}")
        return None

    if LOG_LEVEL == "debug":
        log.debug(f"Received: {packet}")

    return packet_data


def on_packet_sent(packet_data: bytes) -> bytes:
    _packet_count["sent"] += 1
    packet = _parse_ip(packet_data)
    if packet is None:
        return packet_data

    if _should_block(packet):
        _packet_count["blocked"] += 1
        log.warning(f"Blocked outbound: {packet['dst_ip']}:{packet['dst_port']}")
        return None

    if LOG_LEVEL == "debug":
        log.debug(f"Sent: {packet}")

    return packet_data


def _parse_ip(data: bytes):
    """Parse IP header and extract relevant fields."""
    try:
        if len(data) < 20:
            return None

        version_ihl = data[0]
        ihl = (version_ihl & 0x0F) * 4
        protocol = data[9]

        src_ip = ".".join(str(b) for b in data[ihl - 8:ihl - 4])
        dst_ip = ".".join(str(b) for b in data[ihl - 4:ihl])

        src_port = 0
        dst_port = 0
        if protocol in (6, 17):  # TCP or UDP
            if len(data) >= ihl + 4:
                src_port = (data[ihl] << 8) | data[ihl + 1]
                dst_port = (data[ihl + 2] << 8) | data[ihl + 3]

        proto_map = {1: "ICMP", 6: "TCP", 17: "UDP"}
        return {
            "src_ip": src_ip,
            "dst_ip": dst_ip,
            "src_port": src_port,
            "dst_port": dst_port,
            "protocol": proto_map.get(protocol, f"UNKNOWN({protocol})"),
            "length": len(data),
            "timestamp": datetime.now().isoformat(),
        }
    except Exception:
        return None


def _should_block(packet: dict) -> bool:
    """Check if packet matches any block rules."""
    if not BLOCK_STRINGS or not BLOCK_STRINGS[0]:
        return False

    payload_str = json.dumps(packet).lower()
    for pattern in BLOCK_STRINGS:
        if pattern.strip().lower() in payload_str:
            return True
    return False
