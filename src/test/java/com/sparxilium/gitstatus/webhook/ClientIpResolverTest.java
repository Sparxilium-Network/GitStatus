package com.sparxilium.gitstatus.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ClientIpResolverTest {

    @Test
    void ipRangeMatchesV4AndV6() {
        IpRange v4 = IpRange.parse("192.30.252.0/22");
        assertTrue(v4.contains("192.30.255.254"));
        assertFalse(v4.contains("192.30.251.255"));
        assertFalse(v4.contains("2a0a:a440::1"));

        IpRange v6 = IpRange.parse("2a0a:a440::/29");
        assertTrue(v6.contains("2a0a:a447:ffff::1"));
        assertFalse(v6.contains("2a0a:a448::1"));

        assertTrue(IpRange.parse("10.0.0.5").contains("10.0.0.5"));
        assertFalse(IpRange.parse("10.0.0.5").contains("10.0.0.6"));
    }

    @Test
    void ipRangeNeverTreatsInputAsHostname() {
        assertFalse(IpRange.isRange("cloudflared"));
        assertFalse(IpRange.isRange("999.1.1.1"));
        assertFalse(IpRange.parse("0.0.0.0/0").contains("github.com"));
        assertThrows(IllegalArgumentException.class, () -> IpRange.parse("10.0.0.0/33"));
    }

    private static ClientIpResolver resolver(String trusted, Map<String, List<String>> dns) {
        return new ClientIpResolver("CF-Connecting-IP", trusted, host -> dns.getOrDefault(host, List.of()));
    }

    @Test
    void usesHeaderOnlyFromTrustedProxyHost() {
        ClientIpResolver r = resolver("cloudflared", Map.of("cloudflared", List.of("172.18.0.3")));
        assertEquals("cf-connecting-ip", r.headerName());
        assertEquals("140.82.115.1", r.resolve("172.18.0.3", "140.82.115.1"));
        // any other peer cannot spoof its address with the header
        assertEquals("172.18.0.9", r.resolve("172.18.0.9", "140.82.115.1"));
    }

    @Test
    void untrustedWhenProxyHostDoesNotResolve() {
        ClientIpResolver r = resolver("cloudflared", Map.of());
        assertEquals("172.18.0.3", r.resolve("172.18.0.3", "140.82.115.1"));
    }

    @Test
    void acceptsCidrEntriesAndIgnoresGarbageHeader() {
        ClientIpResolver r = resolver("127.0.0.1/32, ::1", Map.of());
        assertEquals("140.82.115.1", r.resolve("127.0.0.1", "140.82.115.1, 10.0.0.1"));
        assertEquals("127.0.0.1", r.resolve("127.0.0.1", "not-an-ip"));
        assertEquals("127.0.0.1", r.resolve("127.0.0.1", null));
    }

    @Test
    void noHeaderConfiguredMeansRemoteAddress() {
        ClientIpResolver r = new ClientIpResolver("", "127.0.0.1", host -> List.of());
        assertEquals("127.0.0.1", r.resolve("127.0.0.1", "140.82.115.1"));
    }
}
