package com.example.plugin;

import android.util.Log;
import com.fluxfabric.model.Packet;
import com.fluxfabric.plugin.BasePlugin;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class AdBlockerPlugin extends BasePlugin {

    private static final String TAG = "AdBlocker";
    private int blockedCount = 0;

    private static final Set<String> BLOCKED_DOMAINS = new HashSet<>(Arrays.asList(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "adsrvr.org",
        "adnxs.com",
        "rubiconproject.com"
    ));

    public AdBlockerPlugin() {
        super(
            "ad-blocker",
            "Ad Blocker",
            "Blocks known ad and tracking domains",
            "1.0.0"
        );
    }

    @Override
    public void onStart() {
        blockedCount = 0;
        Log.d(TAG, "AdBlocker started with " + BLOCKED_DOMAINS.size() + " blocked domains");
    }

    @Override
    public void onStop() {
        Log.d(TAG, "AdBlocker stopped — blocked " + blockedCount + " requests");
    }

    @Override
    public Packet onPacketSent(Packet packet) {
        // Check DNS requests (UDP port 53)
        if (packet.getProtocol() == Packet.Protocol.UDP && packet.getDstPort() == 53) {
            String dnsQuery = extractDnsQuery(packet.getData());
            if (dnsQuery != null && isBlocked(dnsQuery)) {
                blockedCount++;
                Log.d(TAG, "Blocked DNS query to: " + dnsQuery);
                return null; // Drop the packet
            }
        }
        return packet;
    }

    private String extractDnsQuery(byte[] data) {
        // Simple DNS query extraction (skips IP + UDP headers)
        try {
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(data);
            int versionAndIhl = bb.get() & 0xFF;
            int ihl = (versionAndIhl & 0x0F) * 4;
            int udpOffset = ihl + 8; // IP header + UDP header

            if (data.length <= udpOffset + 12) return null;

            // Skip DNS header (12 bytes)
            int queryStart = udpOffset + 12;
            StringBuilder domain = new StringBuilder();

            int pos = queryStart;
            while (pos < data.length) {
                int len = data[pos] & 0xFF;
                if (len == 0) break;
                pos++;
                if (pos + len > data.length) break;
                if (domain.length() > 0) domain.append(".");
                for (int i = 0; i < len; i++) {
                    domain.append((char) data[pos + i]);
                }
                pos += len;
            }

            return domain.length() > 0 ? domain.toString().toLowerCase() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isBlocked(String domain) {
        for (String blocked : BLOCKED_DOMAINS) {
            if (domain.endsWith(blocked)) {
                return true;
            }
        }
        return false;
    }
}
