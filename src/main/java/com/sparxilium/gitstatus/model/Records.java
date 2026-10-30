package com.sparxilium.gitstatus.model;

/** Small persistent entity records. */
public final class Records {

    private Records() {
    }

    public record DiscordDestination(long id, String name, String guildId, String channelId,
                                     String botProfile, boolean enabled, Language language) {
    }

    public record DestinationHealth(long destinationId, String status, String lastError,
                                    String lastAttemptAt, String lastSuccessAt) {
    }

    public enum InstallationStatus { SELECTED, AVAILABLE, REVOKED }

    public record ProviderInstallation(long id, String provider, String externalId, String owner,
                                       String type, InstallationStatus status, String permissionsJson,
                                       String eventsJson) {
    }

    public record RepositoryRecord(long id, long installationId, String externalRepoId, String fullName,
                                   String visibility, boolean archived, boolean accessible,
                                   boolean subscribed) {
    }

    public record NotificationRule(long repositoryId, EventType eventType, long destinationId, boolean enabled) {
    }

    public record DeliveryLogEntry(String provider, String deliveryId, String providerEvent, String repoId,
                                   String eventType, String result, Long latencyMs, String createdAt) {
    }
}
