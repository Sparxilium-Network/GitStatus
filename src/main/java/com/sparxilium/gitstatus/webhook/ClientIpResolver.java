package com.sparxilium.gitstatus.webhook;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Determines the real client IP. The client IP header (CF-Connecting-IP) is only trusted when
 * the TCP peer is a trusted proxy. Trusted proxies may be CIDRs, hostnames, or the keyword
 * {@value #GATEWAY}: the container's default gateway, which is the peer address of every
 * connection forwarded from the host's published port (where the user's cloudflared connects).
 * Hostnames and the gateway are re-resolved at most once per {@link #RESOLVE_TTL_MS}.
 */
@Component
public class ClientIpResolver {

    static final long RESOLVE_TTL_MS = 60_000;
    static final String GATEWAY = "gateway";
    private static final Path ROUTE_TABLE = Path.of("/proc/net/route");

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
        if (GATEWAY.equalsIgnoreCase(host)) {
            try {
                return defaultGateway(Files.readString(ROUTE_TABLE)).map(List::of).orElse(List.of());
            } catch (IOException e) {
                // not Linux / no /proc: trust nobody
                return List.of();
            }
        }
        try {
            return Arrays.stream(InetAddress.getAllByName(host))
                    .map(a -> a.getHostAddress().replaceAll("%.*$", "")) // drop IPv6 scope id
                    .toList();
        } catch (UnknownHostException e) {
            // not resolvable: trust nobody
            return List.of();
        }
    }

    /**
     * Default IPv4 gateway from a Linux {@code /proc/net/route} table: the row whose destination is
     * {@code 00000000}; the gateway column is a little-endian hex address.
     */
    static Optional<String> defaultGateway(String routeTable) {
        for (String line : routeTable.split("\n")) {
            String[] cols = line.trim().split("\\s+");
            if (cols.length > 2 && "00000000".equals(cols[1]) && cols[2].matches("[0-9A-Fa-f]{8}")) {
                long g = Long.parseLong(cols[2], 16);
                if (g == 0) {
                    continue;
                }
                return Optional.of((g & 0xFF) + "." + ((g >> 8) & 0xFF) + "." + ((g >> 16) & 0xFF) + "." + ((g >> 24) & 0xFF));
            }
        }
        return Optional.empty();
    }
}
