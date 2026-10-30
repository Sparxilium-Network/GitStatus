package com.sparxilium.gitstatus.routing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.sparxilium.gitstatus.db.ConfigStore;
import com.sparxilium.gitstatus.model.EventType;
import com.sparxilium.gitstatus.model.Records.DiscordDestination;
import com.sparxilium.gitstatus.routing.RuleSet.RepoKey;

/** Builds and validates a {@link RuleSet} from the database. */
@Component
public class RuleSetLoader {

    private static final String ROUTES_SQL = """
            SELECT pi.provider, pi.external_id, r.external_repo_id, nr.event_type,
                   d.id, d.name, d.guild_id, d.channel_id, d.bot_profile
            FROM notification_rule nr
            JOIN repository r             ON r.id = nr.repository_id AND r.accessible = 1
            JOIN subscription s           ON s.repository_id = r.id AND s.enabled = 1
            JOIN provider_installation pi ON pi.id = r.installation_id AND pi.status = 'SELECTED'
            JOIN discord_destination d    ON d.id = nr.destination_id AND d.enabled = 1
            WHERE nr.enabled = 1
            ORDER BY d.name
            """;

    private final JdbcTemplate jdbc;
    private final ConfigStore store;

    public RuleSetLoader(JdbcTemplate jdbc, ConfigStore store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    public static class InvalidConfigurationException extends RuntimeException {
        public InvalidConfigurationException(String message) {
            super(message);
        }
    }

    private record Row(String provider, String installationId, String repoId, String eventType, DiscordDestination dest) {
    }

    /**
     * Loads a consistent snapshot. The routes come from a single SELECT (atomic in SQLite);
     * reading the revision before and after detects a commit landing in between.
     */
    public RuleSet load() {
        for (int attempt = 0; attempt < 5; attempt++) {
            long before = store.revision();
            List<Row> rows = jdbc.query(ROUTES_SQL, (rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), new DiscordDestination(rs.getLong(5), rs.getString(6), rs.getString(7), rs.getString(8),
                            rs.getString(9), true)));
            long after = store.revision();
            if (before == after) {
                return build(after, rows);
            }
        }
        throw new IllegalStateException("configuration kept changing while loading");
    }

    private static RuleSet build(long revision, List<Row> rows) {
        Map<RepoKey, Map<EventType, List<DiscordDestination>>> routes = new HashMap<>();
        for (Row row : rows) {
            EventType type = EventType.parse(row.eventType());
            if (type == null) {
                throw new InvalidConfigurationException("unknown event type in rule: " + row.eventType());
            }
            if (!type.supported()) {
                continue;
            }
            if (!row.dest().channelId().matches("\\d{15,25}")) {
                throw new InvalidConfigurationException("destination " + row.dest().id() + " has an invalid channel id");
            }
            routes.computeIfAbsent(new RepoKey(row.provider(), row.installationId(), row.repoId()), k -> new HashMap<>())
                    .computeIfAbsent(type, k -> new ArrayList<>())
                    .add(row.dest());
        }
        // freeze
        Map<RepoKey, Map<EventType, List<DiscordDestination>>> frozen = new HashMap<>();
        routes.forEach((k, v) -> {
            Map<EventType, List<DiscordDestination>> m = new HashMap<>();
            v.forEach((t, l) -> m.put(t, List.copyOf(l)));
            frozen.put(k, Map.copyOf(m));
        });
        return new RuleSet(revision, Map.copyOf(frozen));
    }
}
