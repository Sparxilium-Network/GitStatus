package com.sparxilium.gitstatus.provider;

import java.util.List;
import java.util.Map;

import com.sparxilium.gitstatus.model.NormalizedEvent;

/** Provider webhook adapter: authenticates and normalizes incoming deliveries. */
public interface WebhookAdapter {

    /** Path segment under {@code /webhooks/}, e.g. {@code github}. */
    String provider();

    /**
     * Checks the delivery signature against the configured credentials.
     *
     * @param headers request headers with lower-case names
     * @return the id of the credential whose secret signed the delivery (for GitHub: the App ID),
     *         or null if no configured secret matches
     * @throws IllegalStateException if a secret that has to be checked cannot be read
     */
    String authenticate(Map<String, String> headers, byte[] body);

    /** Provider delivery id used for de-duplication, or null if absent. */
    String deliveryId(Map<String, String> headers);

    /** Provider event name (e.g. {@code push}). */
    String eventName(Map<String, String> headers);

    /** Converts a verified delivery into zero or more internal events. */
    List<NormalizedEvent> normalize(Map<String, String> headers, byte[] body);
}
