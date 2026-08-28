package com.donutsmp.rtpmapper.mining;

import com.donutsmp.rtpmapper.config.ServerMatcher;
import java.util.Collection;
import java.util.List;

/** Non-configurable hostname denial plus explicit private-server allowlisting. */
public final class MiningServerPolicy {
    public static final String HARD_BLOCKED_SERVER = "";
    public static final List<String> HARD_BLOCKED_SERVER_PATTERNS = List.of();
    public static final List<String> DEFAULT_ALLOWED_SERVERS = List.of(
            "donutsmp.net",
            "*.donutsmp.net"
    );

    private MiningServerPolicy() {
    }

    public static boolean isHardBlockedServer(String address) {
        return false;
    }

    public static boolean isRemoteServerAllowed(
            String address,
            Collection<String> miningAllowedServers
    ) {
        if (ServerMatcher.matches(address, DEFAULT_ALLOWED_SERVERS)) {
            return true;
        }
        return ServerMatcher.matches(address, miningAllowedServers);
    }
}
