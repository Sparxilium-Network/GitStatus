package com.sparxilium.gitstatus.webhook;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Determines the real client IP. The client IP header (CF-Connecting-IP) is only trusted when
 * the TCP peer is a trusted proxy, i.e. the cloudflared container. Trusted proxies may be CIDRs
 * or hostnames; hostnames (e.g. the compose service {@code cloudflared}) are re-resolved at most
 * once per {@link #RESOLVE_TTL_MS}.
 */
@Component
public class ClientIpResolver {

    static final long RESOLVE_TTL_MS = 60_000;

    private final String header;
    private final List<String> fixedEntries;
    private final List<String> hostnames;
    private final Function<String, List<String>> dns;
    private volatile List<IpRange> resolved = List.of();
    private volatile long resolvedAt;

    @Autowired
    public ClientIpResolver(@Value("${webhook.clientIpHeader}") String header,
                            @Value("${webhook.trustedProxies}") String trustedProxies) {
        this(header, trustedProxies, ClientIpResolver::lookup);
    }

    ClientIpResolver(String header, String trustedProxies, Function<String, List<String>> dns) {
        this.header = header == null ? "" : header.trim();
        this.dns = dns;
        List<String> fixed = new ArrayList<>();
        List<String> hosts = new ArrayList<>();
        for (String entry : trustedProxies == null ? new String[0] : trustedProxies.split(",")) {
            String e = entry.trim();
            if (e.isEmpty()) {
                continue;
            }
            if (IpRange.isRange(e)) {
                IpRange.parse(e); // fail fast on a malformed CIDR
                fixed.add(e);
            } else {
                hosts.add(e);
            }
        }
        this.fixedEntries = List.copyOf(fixed);
        this.hostnames = List.copyOf(hosts);
    }

    /** Lower-case name of the client IP header, or empty if none is configured. */
    public String headerName() {
        return header.toLowerCase();
    }

    /**
     * @param remoteAddr TCP peer address
     * @param headerValue value of the client IP header, may be null
     */
    public String resolve(String remoteAddr, String headerValue) {
        if (header.isEmpty() || headerValue == null || headerValue.isBlank() || !isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }
        String candidate = headerValue.split(",")[0].trim();
        return IpRange.literal(candidate) != null ? candidate : remoteAddr;
    }

    boolean isTrustedProxy(String remoteAddr) {
        for (IpRange r : ranges()) {
            if (r.contains(remoteAddr)) {
                return true;
            }
        }
        return false;
    }

    private List<IpRange> ranges() {
        long now = System.currentTimeMillis();
        if (now - resolvedAt > RESOLVE_TTL_MS) {
            List<IpRange> out = new ArrayList<>();
            fixedEntries.forEach(e -> out.add(IpRange.parse(e)));
            for (String host : hostnames) {
                dns.apply(host).forEach(ip -> out.add(IpRange.parse(ip)));
            }
            resolved = List.copyOf(out);
            resolvedAt = now;
        }
        return resolved;
    }

    private static List<String> lookup(String host) {
        try {
            return Arrays.stream(InetAddress.getAllByName(host))
                    .map(a -> a.getHostAddress().replaceAll("%.*$", "")) // drop IPv6 scope id
                    .toList();
        } catch (UnknownHostException e) {
            // not resolvable (e.g. cloudflared not running): trust nobody
            return List.of();
        }
    }
}
