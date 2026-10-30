package com.sparxilium.gitstatus.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Provider-neutral event. Never carries the raw provider payload: anything a formatter
 * may show must be copied explicitly into a field or the {@code safeAttributes} allowlist.
 */
public record NormalizedEvent(
        String provider,
        String deliveryId,
        String installationId,
        String repositoryId,
        String repositoryFullName,
        EventType eventType,
        String actor,
        String ref,
        String title,
        String conclusion,
        String url,
        Instant occurredAt,
        Map<String, String> safeAttributes) {

    public NormalizedEvent {
        Map<String, String> attrs = new HashMap<>();
        if (safeAttributes != null) {
            safeAttributes.forEach((k, v) -> {
                if (v != null) {
                    attrs.put(k, v);
                }
            });
        }
        safeAttributes = Map.copyOf(attrs);
    }

    public String attr(String key) {
        return safeAttributes.get(key);
    }
}
