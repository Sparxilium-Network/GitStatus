package com.sparxilium.gitstatus.routing;

import java.util.List;
import java.util.Map;

import com.sparxilium.gitstatus.model.EventType;
import com.sparxilium.gitstatus.model.Records.DiscordDestination;

/**
 * Immutable routing snapshot: Repository × Event Type → Discord Destinations.
 * Only contains routes that are fully active (installation selected and assigned to an App, repository accessible and
 * subscribed, rule enabled, destination enabled).
 */
public record RuleSet(long revision, Map<RepoKey, Map<EventType, List<DiscordDestination>>> routes) {

    public static final RuleSet EMPTY = new RuleSet(-1, Map.of());

    /**
     * @param appId the App whose webhook secret must have signed the delivery; an event signed by
     *              another App never reaches this installation's routes
     */
    public record RepoKey(String provider, String appId, String installationId, String repositoryId) {
    }

    public List<DiscordDestination> destinations(String provider, String appId, String installationId, String repositoryId,
                                                 EventType type) {
        if (appId == null) {
            return List.of();
        }
        Map<EventType, List<DiscordDestination>> byEvent = routes.get(new RepoKey(provider, appId, installationId, repositoryId));
        if (byEvent == null) {
            return List.of();
        }
        return byEvent.getOrDefault(type, List.of());
    }

    public int routeCount() {
        return routes.values().stream().mapToInt(m -> m.values().stream().mapToInt(List::size).sum()).sum();
    }
}
