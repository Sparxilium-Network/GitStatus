package com.sparxilium.gitstatus.webhook;

/** Decides whether a webhook request may come from the given client IP. */
@FunctionalInterface
public interface SourceVerifier {

    boolean allowed(String provider, String clientIp);

    SourceVerifier ALLOW_ALL = (provider, ip) -> true;
}
