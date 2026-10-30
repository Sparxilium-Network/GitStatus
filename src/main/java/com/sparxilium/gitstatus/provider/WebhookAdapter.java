package com.sparxilium.gitstatus.provider;

import java.util.List;
import java.util.Map;

import com.sparxilium.gitstatus.model.NormalizedEvent;

/** Provider webhook adapter: authenticates and normalizes incoming deliveries. */
public interface WebhookAdapter {

    /** Path segment under {@code /webhooks/}, e.g. {@code github}. */
    String provider();

    /**
     * @param headers request headers with lower-case names
     * @return true only if the signature / secret matches
     */
    boolean verify(Map<String, String> headers, byte[] body);

    /** Provider delivery id used for de-duplication, or null if absent. */
    String deliveryId(Map<String, String> headers);

    /** Provider event name (e.g. {@code push}). */
    String eventName(Map<String, String> headers);

    /** Converts a verified delivery into zero or more internal events. */
    List<NormalizedEvent> normalize(Map<String, String> headers, byte[] body);
}
