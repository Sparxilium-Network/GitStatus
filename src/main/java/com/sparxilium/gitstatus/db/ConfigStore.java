package com.sparxilium.gitstatus.db;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.sparxilium.gitstatus.model.EventType;
import com.sparxilium.gitstatus.model.Language;
import com.sparxilium.gitstatus.model.Records.DeliveryLogEntry;
import com.sparxilium.gitstatus.model.Records.DestinationHealth;
import com.sparxilium.gitstatus.model.Records.DiscordDestination;
import com.sparxilium.gitstatus.model.Records.InstallationStatus;
import com.sparxilium.gitstatus.model.Records.NotificationRule;
import com.sparxilium.gitstatus.model.Records.ProviderInstallation;
import com.sparxilium.gitstatus.model.Records.RepositoryRecord;

/**
 * All SQL lives here. Configuration writes go through {@link #mutate}, which runs the change
 * and the revision bump in one transaction so the daemon never observes a half-applied change.
 */
@Component
public class ConfigStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ConfigStore(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public static String now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    // ---------------------------------------------------------------- revision

    /** Runs {@code change} and increments the configuration revision atomically. Rolls back on failure. */
    public void mutate(Consumer<JdbcTemplate> change) {
        tx.executeWithoutResult(status -> {
            change.accept(jdbc);
            jdbc.update("UPDATE configuration_state SET revision = revision + 1, updated_at = ? WHERE id = 1", now());
        });
    }

    public long revision() {
        return jdbc.queryForObject("SELECT revision FROM configuration_state WHERE id = 1", Long.class);
    }

    public String revisionUpdatedAt() {
        return jdbc.queryForObject("SELECT updated_at FROM configuration_state WHERE id = 1", String.class);
    }

    // ---------------------------------------------------------------- destinations

    public List<DiscordDestination> destinations() {
        return jdbc.query("SELECT id, name, guild_id, channel_id, bot_profile, enabled, language FROM discord_destination ORDER BY name",
                (rs, i) -> new DiscordDestination(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getInt(6) != 0, Language.fromCode(rs.getString(7))));
    }

    public void addDestination(String name, String guildId, String channelId, Language language) {
        mutate(j -> j.update("INSERT INTO discord_destination (name, guild_id, channel_id, bot_profile, enabled, created_at, language) "
                + "VALUES (?, ?, ?, 'default', 1, ?, ?)", name, guildId, channelId, now(), language.code()));
    }

    public void setDestinationLanguage(long id, Language language) {
        mutate(j -> j.update("UPDATE discord_destination SET language = ? WHERE id = ?", language.code(), id));
    }

    public void setDestinationEnabled(long id, boolean enabled) {
        mutate(j -> j.update("UPDATE discord_destination SET enabled = ? WHERE id = ?", enabled ? 1 : 0, id));
    }

    public void deleteDestination(long id) {
        mutate(j -> j.update("DELETE FROM discord_destination WHERE id = ?", id));
    }

    /** Clears the error state; bumps the revision so the daemon forgets its in-memory failure too. */
    public void resetDestinationHealth(long id) {
        mutate(j -> j.update("DELETE FROM destination_health WHERE destination_id = ?", id));
    }

    public Map<Long, DestinationHealth> destinationHealth() {
        Map<Long, DestinationHealth> map = new HashMap<>();
        jdbc.query("SELECT destination_id, status, last_error, last_attempt_at, last_success_at FROM destination_health",
                rs -> {
                    map.put(rs.getLong(1), new DestinationHealth(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5)));
                });
        return map;
    }

    public void recordSendSuccess(long destinationId) {
        String now = now();
        jdbc.update("INSERT INTO destination_health (destination_id, status, last_error, last_attempt_at, last_success_at) "
                + "VALUES (?, 'OK', NULL, ?, ?) ON CONFLICT (destination_id) DO UPDATE SET status = 'OK', last_error = NULL, "
                + "last_attempt_at = excluded.last_attempt_at, last_success_at = excluded.last_success_at",
                destinationId, now, now);
    }

    public void recordSendFailure(long destinationId, String status, String error) {
        jdbc.update("INSERT INTO destination_health (destination_id, status, last_error, last_attempt_at) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT (destination_id) DO UPDATE SET status = excluded.status, last_error = excluded.last_error, "
                + "last_attempt_at = excluded.last_attempt_at",
                destinationId, status, error, now());
    }

    // ---------------------------------------------------------------- installations

    public List<ProviderInstallation> installations(String provider) {
        return jdbc.query("SELECT id, provider, external_id, owner, type, status, permissions, events "
                + "FROM provider_installation WHERE provider = ? ORDER BY owner",
                (rs, i) -> new ProviderInstallation(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), InstallationStatus.valueOf(rs.getString(6)), rs.getString(7), rs.getString(8)),
                provider);
    }

    public Optional<ProviderInstallation> installation(long id) {
        return jdbc.query("SELECT id, provider, external_id, owner, type, status, permissions, events "
                + "FROM provider_installation WHERE id = ?",
                (rs, i) -> new ProviderInstallation(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), InstallationStatus.valueOf(rs.getString(6)), rs.getString(7), rs.getString(8)),
                id).stream().findFirst();
    }

    /** Snapshot of an installation as returned by a provider API. */
    public record DiscoveredInstallation(String externalId, String owner, String type, String permissionsJson,
                                         String eventsJson) {
    }

    /**
     * Upserts the provider's installation list. New installations start as AVAILABLE (not selected);
     * installations missing from the API response become REVOKED.
     */
    public void syncInstallations(String provider, List<DiscoveredInstallation> discovered) {
        mutate(j -> {
            String now = now();
            Set<String> seen = new HashSet<>();
            for (DiscoveredInstallation d : discovered) {
                seen.add(d.externalId());
                j.update("INSERT INTO provider_installation (provider, external_id, owner, type, status, permissions, events, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'AVAILABLE', ?, ?, ?) ON CONFLICT (provider, external_id) DO UPDATE SET "
                        + "owner = excluded.owner, type = excluded.type, permissions = excluded.permissions, "
                        + "events = excluded.events, updated_at = excluded.updated_at, "
                        + "status = CASE WHEN provider_installation.status = 'REVOKED' THEN 'AVAILABLE' ELSE provider_installation.status END",
                        provider, d.externalId(), d.owner(), d.type(), d.permissionsJson(), d.eventsJson(), now);
            }
            for (ProviderInstallation existing : installationsIn(j, provider)) {
                if (!seen.contains(existing.externalId())) {
                    j.update("UPDATE provider_installation SET status = 'REVOKED', updated_at = ? WHERE id = ?", now, existing.id());
                }
            }
        });
    }

    private static List<ProviderInstallation> installationsIn(JdbcTemplate j, String provider) {
        return j.query("SELECT id, provider, external_id, owner, type, status, permissions, events "
                + "FROM provider_installation WHERE provider = ?",
                (rs, i) -> new ProviderInstallation(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), InstallationStatus.valueOf(rs.getString(6)), rs.getString(7), rs.getString(8)),
                provider);
    }

    public void setInstallationSelected(long id, boolean selected) {
        mutate(j -> j.update("UPDATE provider_installation SET status = ?, updated_at = ? WHERE id = ? AND status <> 'REVOKED'",
                selected ? "SELECTED" : "AVAILABLE", now(), id));
    }

    // ---------------------------------------------------------------- repositories

    public record DiscoveredRepository(String externalRepoId, String fullName, String visibility, boolean archived) {
    }

    /** Replaces the repository cache for one installation; repositories no longer returned become inaccessible. */
    public void syncRepositories(long installationId, List<DiscoveredRepository> discovered) {
        mutate(j -> {
            String now = now();
            Set<String> seen = new HashSet<>();
            for (DiscoveredRepository r : discovered) {
                seen.add(r.externalRepoId());
                j.update("INSERT INTO repository (installation_id, external_repo_id, full_name, visibility, archived, accessible, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 1, ?) ON CONFLICT (installation_id, external_repo_id) DO UPDATE SET "
                        + "full_name = excluded.full_name, visibility = excluded.visibility, archived = excluded.archived, "
                        + "accessible = 1, updated_at = excluded.updated_at",
                        installationId, r.externalRepoId(), r.fullName(), r.visibility(), r.archived() ? 1 : 0, now);
            }
            List<String> existing = j.queryForList("SELECT external_repo_id FROM repository WHERE installation_id = ?",
                    String.class, installationId);
            for (String id : existing) {
                if (!seen.contains(id)) {
                    j.update("UPDATE repository SET accessible = 0, updated_at = ? WHERE installation_id = ? AND external_repo_id = ?",
                            now, installationId, id);
                }
            }
        });
    }

    /** Accessible repositories of an installation, with subscription state. */
    public List<RepositoryRecord> repositories(long installationId) {
        return jdbc.query("SELECT r.id, r.installation_id, r.external_repo_id, r.full_name, r.visibility, r.archived, r.accessible, "
                + "COALESCE(s.enabled, 0) FROM repository r LEFT JOIN subscription s ON s.repository_id = r.id "
                + "WHERE r.installation_id = ? AND r.accessible = 1 ORDER BY r.full_name COLLATE NOCASE",
                (rs, i) -> new RepositoryRecord(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getInt(6) != 0, rs.getInt(7) != 0, rs.getInt(8) != 0),
                installationId);
    }

    public void setSubscribed(long repositoryId, boolean enabled) {
        mutate(j -> j.update("INSERT INTO subscription (repository_id, enabled) VALUES (?, ?) "
                + "ON CONFLICT (repository_id) DO UPDATE SET enabled = excluded.enabled", repositoryId, enabled ? 1 : 0));
    }

    // ---------------------------------------------------------------- rules

    public List<NotificationRule> rules(long repositoryId) {
        List<NotificationRule> out = new ArrayList<>();
        jdbc.query("SELECT repository_id, event_type, destination_id, enabled FROM notification_rule WHERE repository_id = ?",
                rs -> {
                    EventType type = EventType.parse(rs.getString(2));
                    if (type != null) {
                        out.add(new NotificationRule(rs.getLong(1), type, rs.getLong(3), rs.getInt(4) != 0));
                    }
                }, repositoryId);
        return out;
    }

    /** Replaces all rules of a repository with the given enabled (event, destination) pairs. */
    public void saveRules(long repositoryId, Map<Long, Set<EventType>> enabledByDestination) {
        mutate(j -> {
            j.update("DELETE FROM notification_rule WHERE repository_id = ?", repositoryId);
            for (Map.Entry<Long, Set<EventType>> e : enabledByDestination.entrySet()) {
                for (EventType type : e.getValue()) {
                    j.update("INSERT INTO notification_rule (repository_id, event_type, destination_id, enabled) VALUES (?, ?, ?, 1)",
                            repositoryId, type.name(), e.getKey());
                }
            }
        });
    }

    // ---------------------------------------------------------------- delivery log

    /** Records a delivery; returns false if it was already seen (duplicate). */
    public boolean tryRecordDelivery(String provider, String deliveryId, String providerEvent) {
        return jdbc.update("INSERT OR IGNORE INTO delivery_log (provider, provider_delivery_id, provider_event, result, created_at) "
                + "VALUES (?, ?, ?, 'RECEIVED', ?)", provider, deliveryId, providerEvent, now()) == 1;
    }

    public void completeDelivery(String provider, String deliveryId, String repoId, String eventType, String result, long latencyMs) {
        jdbc.update("UPDATE delivery_log SET repo_id = ?, event_type = ?, result = ?, latency_ms = ? "
                + "WHERE provider = ? AND provider_delivery_id = ?", repoId, eventType, result, latencyMs, provider, deliveryId);
    }

    public Optional<DeliveryLogEntry> lastDelivery() {
        return jdbc.query("SELECT provider, provider_delivery_id, provider_event, repo_id, event_type, result, latency_ms, created_at "
                + "FROM delivery_log ORDER BY id DESC LIMIT 1",
                (rs, i) -> new DeliveryLogEntry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), (Long) rs.getObject(7, Long.class), rs.getString(8)))
                .stream().findFirst();
    }

    public int purgeDeliveryLog(int retentionDays) {
        String cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
        return jdbc.update("DELETE FROM delivery_log WHERE created_at < ?", cutoff);
    }

    // ---------------------------------------------------------------- runtime status

    public void putStatus(String key, String value) {
        jdbc.update("INSERT INTO runtime_status (key, value, updated_at) VALUES (?, ?, ?) "
                + "ON CONFLICT (key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at", key, value, now());
    }

    public record StatusValue(String value, String updatedAt) {
    }

    public Optional<StatusValue> status(String key) {
        return jdbc.query("SELECT value, updated_at FROM runtime_status WHERE key = ?",
                (rs, i) -> new StatusValue(rs.getString(1), rs.getString(2)), key).stream().findFirst();
    }
}
