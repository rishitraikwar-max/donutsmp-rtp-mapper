package com.donutsmp.rtpmapper.mining;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningServerPolicyTest {
    @Test
    void remoteServersRequireAnExplicitExactOrWildcardMatch() {
        List<String> allowlist = List.of(
                "private.example",
                "*.friends.example",
                "donutsmp.net",
                "*.donutsmp.net"
        );

        assertTrue(MiningServerPolicy.isRemoteServerAllowed(
                "play.donutsmp.net",
                List.of()
        ));
        assertTrue(MiningServerPolicy.isRemoteServerAllowed(
                "donutsmp.net",
                List.of()
        ));
        assertTrue(MiningServerPolicy.isRemoteServerAllowed(
                "private.example:25565",
                allowlist
        ));
        assertTrue(MiningServerPolicy.isRemoteServerAllowed(
                "mine.friends.example",
                allowlist
        ));
        assertTrue(MiningServerPolicy.isRemoteServerAllowed(
                "play.donutsmp.net",
                allowlist
        ));
        assertFalse(MiningServerPolicy.isRemoteServerAllowed(
                "friends.example",
                allowlist
        ));
        assertFalse(MiningServerPolicy.isRemoteServerAllowed(
                "public.example",
                allowlist
        ));
        assertFalse(MiningServerPolicy.isRemoteServerAllowed("private.example", List.of()));
        assertFalse(MiningServerPolicy.isRemoteServerAllowed("private.example", null));
    }

    @Test
    void malformedAddressesAndPatternsFailClosed() {
        assertFalse(MiningServerPolicy.isRemoteServerAllowed(
                "private.example:not-a-port",
                List.of("private.example")
        ));
        assertFalse(MiningServerPolicy.isRemoteServerAllowed(
                "private.example",
                List.of("*", "foo.*.example")
        ));
    }
}
