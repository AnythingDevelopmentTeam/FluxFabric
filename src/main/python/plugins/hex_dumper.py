import struct

def on_packet_received(data):
    if len(data) < 20:
        return data
    version_ihl = data[0]
    ihl = (version_ihl & 0x0F) * 4
    if len(data) < ihl:
        return data
    protocol = data[9]
    total_len = struct.unpack("!H", data[2:4])[0]
    total_len = min(total_len, len(data))
    src_ip = ".".join(str(b) for b in data[ihl-8:ihl-4])
    dst_ip = ".".join(str(b) for b in data[ihl-4:ihl])
    proto_names = {1: "ICMP", 6: "TCP", 17: "UDP"}
    proto_name = proto_names.get(protocol, f"UNKNOWN({protocol})")
    src_port = dst_port = 0
    if protocol in (6, 17):
        src_port = struct.unpack("!H", data[ihl:ihl+2])[0]
        dst_port = struct.unpack("!H", data[ihl+2:ihl+4])[0]
    payload_len = total_len - ihl - (20 if protocol == 6 else 8)
    print(f"[hex_dumper] IN  {proto_name} {src_ip}:{src_port} -> {dst_ip}:{dst_port} data_len={payload_len}")
    return data

def on_packet_sent(data):
    if len(data) < 20:
        return data
    version_ihl = data[0]
    ihl = (version_ihl & 0x0F) * 4
    if len(data) < ihl:
        return data
    protocol = data[9]
    total_len = struct.unpack("!H", data[2:4])[0]
    total_len = min(total_len, len(data))
    src_ip = ".".join(str(b) for b in data[ihl-8:ihl-4])
    dst_ip = ".".join(str(b) for b in data[ihl-4:ihl])
    proto_names = {1: "ICMP", 6: "TCP", 17: "UDP"}
    proto_name = proto_names.get(protocol, f"UNKNOWN({protocol})")
    src_port = dst_port = 0
    if protocol in (6, 17):
        src_port = struct.unpack("!H", data[ihl:ihl+2])[0]
        dst_port = struct.unpack("!H", data[ihl+2:ihl+4])[0]
    payload_len = total_len - ihl - (20 if protocol == 6 else 8)
    print(f"[hex_dumper] OUT {proto_name} {src_ip}:{src_port} -> {dst_ip}:{dst_port} data_len={payload_len}")
    return data
