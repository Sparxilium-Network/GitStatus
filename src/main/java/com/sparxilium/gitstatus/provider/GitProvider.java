package com.sparxilium.gitstatus.provider;

import java.util.List;
import java.util.Map;

import com.sparxilium.gitstatus.db.ConfigStore.DiscoveredInstallation;
import com.sparxilium.gitstatus.db.ConfigStore.DiscoveredRepository;
import com.sparxilium.gitstatus.model.EventType;
import com.sparxilium.gitstatus.model.Records.ProviderInstallation;

/**
 * Provider API adapter: answers "which resources exist" (installations, repositories,
 * capabilities). Real-time events come through {@link WebhookAdapter}.
 */
public interface GitProvider {

    String id();

    String displayName();

    /** One set of credentials, e.g. one GitHub App. */
    record Credential(String id, String label) {
    }

    /** Configured credential sets; empty when the provider is not set up. */
    List<Credential> credentials();

    default boolean configured() {
        return !credentials().isEmpty();
    }

    /** Verifies credentials; returns a short human-readable identity (e.g. the App slug). */
    String checkAuth(Credential credential);

    /** Installations / accounts this credential is authorized for. Never more. */
    List<DiscoveredInstallation> listInstallations(Credential credential);

    /** Repositories the installation is actually authorized to access. */
    List<DiscoveredRepository> listRepositories(ProviderInstallation installation);

    /** Which event types the installation's permissions and subscriptions allow. */
    Map<EventType, Availability> capabilities(ProviderInstallation installation);

    enum Availability {
        AVAILABLE(null),
        NO_PERMISSION("unavailable / insufficient permission"),
        NOT_SUBSCRIBED("unavailable / App not subscribed to this webhook event"),
        UNSUPPORTED("unavailable / not supported yet");

        private final String reason;

        Availability(String reason) {
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }
}
