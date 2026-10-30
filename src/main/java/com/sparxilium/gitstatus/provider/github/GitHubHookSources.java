package com.sparxilium.gitstatus.provider.github;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.sparxilium.gitstatus.GitStatusApplication;
import com.sparxilium.gitstatus.db.ConfigStore;
import com.sparxilium.gitstatus.model.Records.InstallationStatus;
import com.sparxilium.gitstatus.model.Records.ProviderInstallation;
import com.sparxilium.gitstatus.webhook.IpRange;
import com.sparxilium.gitstatus.webhook.SourceVerifier;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Webhook source verification: only GitHub's published hook IP ranges ({@code GET /meta}) may
 * deliver. The list is refreshed periodically and persisted, so a restart while GitHub is
 * unreachable keeps the last known list. With no list at all, every delivery is rejected.
 */
@Component
@Profile(GitStatusApplication.PROFILE_DAEMON)
@DependsOn("schemaInitializer")
public class GitHubHookSources implements SourceVerifier, InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(GitHubHookSources.class);
    public static final String STATUS_KEY = "github.hook_cidrs";

    private final Supplier<List<String>> fetch;
    private final ConfigStore store;
    private final boolean enabled;
    private final ObjectMapper json = JsonMapper.builder().build();
    private final AtomicReference<List<IpRange>> ranges = new AtomicReference<>(List.of());
    private final long refreshIntervalMs;
    private volatile long lastSuccess;

    @Autowired
    public GitHubHookSources(GitHubProvider github, ConfigStore store, @Value("${webhook.verifySource}") boolean enabled,
                             @Value("${webhook.sourceRefreshMs}") long refreshIntervalMs) {
        this(() -> github.hookCidrs(anyInstallation(store)), store, enabled, refreshIntervalMs);
    }

    public GitHubHookSources(Supplier<List<String>> fetch, ConfigStore store, boolean enabled) {
        this(fetch, store, enabled, 21_600_000);
    }

    GitHubHookSources(Supplier<List<String>> fetch, ConfigStore store, boolean enabled, long refreshIntervalMs) {
        this.fetch = fetch;
        this.store = store;
        this.enabled = enabled;
        this.refreshIntervalMs = refreshIntervalMs;
    }

    private static String anyInstallation(ConfigStore store) {
        return store.installations(GitHubProvider.ID).stream()
                .filter(i -> i.status() != InstallationStatus.REVOKED)
                .map(ProviderInstallation::externalId)
                .findFirst().orElse(null);
    }

    @Override
    public void afterPropertiesSet() {
        if (!enabled) {
            log.warn("Webhook source verification is DISABLED (webhook.verifySource=false)");
            store.putStatus(STATUS_KEY, null); // shown as "disabled" in the TUI
            return;
        }
        store.status(STATUS_KEY).map(ConfigStore.StatusValue::value).ifPresent(saved -> {
            List<String> cidrs = new ArrayList<>();
            json.readTree(saved).forEach(n -> cidrs.add(n.asString()));
            apply(cidrs);
            log.info("Loaded {} saved GitHub hook source ranges", cidrs.size());
        });
        refresh();
    }

    /** Checks every 10 minutes; fetches when the interval has passed, or sooner while no list is loaded. */
    @Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
    public void refreshIfDue() {
        if (ranges.get().isEmpty() || System.currentTimeMillis() - lastSuccess >= refreshIntervalMs) {
            refresh();
        }
    }

    public void refresh() {
        if (!enabled) {
            return;
        }
        try {
            List<String> cidrs = fetch.get();
            apply(cidrs);
            lastSuccess = System.currentTimeMillis();
            store.putStatus(STATUS_KEY, json.writeValueAsString(cidrs));
            log.info("GitHub hook source ranges updated ({} ranges)", cidrs.size());
        } catch (RuntimeException e) {
            // keep the previous list
            log.warn("Could not refresh GitHub hook source ranges, keeping {} known ranges: {}",
                    ranges.get().size(), e.getMessage());
        }
    }

    private void apply(List<String> cidrs) {
        List<IpRange> parsed = new ArrayList<>();
        for (String c : cidrs) {
            parsed.add(IpRange.parse(c));
        }
        ranges.set(List.copyOf(parsed));
    }

    @Override
    public boolean allowed(String provider, String clientIp) {
        if (!enabled) {
            return true;
        }
        if (!GitHubProvider.ID.equals(provider)) {
            return false;
        }
        for (IpRange r : ranges.get()) {
            if (r.contains(clientIp)) {
                return true;
            }
        }
        return false;
    }
}
