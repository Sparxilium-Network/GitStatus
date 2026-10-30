package com.sparxilium.gitstatus.webhook;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** IPv4 / IPv6 CIDR block. Parsing never performs DNS lookups. */
public record IpRange(byte[] network, int prefix) {

    public static IpRange parse(String cidr) {
        String s = cidr.trim();
        int slash = s.indexOf('/');
        String address = slash >= 0 ? s.substring(0, slash) : s;
        byte[] bytes = literal(address);
        if (bytes == null) {
            throw new IllegalArgumentException("not an IP range: " + cidr);
        }
        int prefix = slash >= 0 ? Integer.parseInt(s.substring(slash + 1)) : bytes.length * 8;
        if (prefix < 0 || prefix > bytes.length * 8) {
            throw new IllegalArgumentException("invalid prefix: " + cidr);
        }
        return new IpRange(bytes, prefix);
    }

    /** True if the string is an IP literal or CIDR (as opposed to a hostname). */
    public static boolean isRange(String s) {
        String address = s.contains("/") ? s.substring(0, s.indexOf('/')) : s;
        return literal(address.trim()) != null;
    }

    public boolean contains(String ip) {
        byte[] candidate = literal(ip);
        return candidate != null && contains(candidate);
    }

    public boolean contains(byte[] candidate) {
        if (candidate.length != network.length) {
            return false;
        }
        int full = prefix / 8;
        for (int i = 0; i < full; i++) {
            if (candidate[i] != network[i]) {
                return false;
            }
        }
        int rest = prefix % 8;
        if (rest == 0) {
            return true;
        }
        int mask = (0xFF << (8 - rest)) & 0xFF;
        return (candidate[full] & mask) == (network[full] & mask);
    }

    /** Parses an IPv4 / IPv6 literal without DNS; returns null for anything else. */
    static byte[] literal(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        boolean ipv4 = s.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        boolean ipv6 = s.contains(":") && s.matches("[0-9a-fA-F:.]+");
        if (!ipv4 && !ipv6) {
            return null;
        }
        if (ipv4) {
            // an out-of-range octet would make InetAddress treat the string as a hostname
            for (String octet : s.split("\\.")) {
                if (Integer.parseInt(octet) > 255) {
                    return null;
                }
            }
        }
        try {
            // IPv4-mapped IPv6 (::ffff:a.b.c.d) is returned as 4 bytes by the JDK
            return InetAddress.getByName(s).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
