package com.sparxilium.gitstatus.routing;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.sparxilium.gitstatus.GitStatusApplication;
import com.sparxilium.gitstatus.db.ConfigStore;

/**
 * Holds the active rule set and polls the configuration revision. A new rule set replaces the
 * old one atomically only after it loaded and validated; on failure the previous one stays active.
 */
@Component
@Profile(GitStatusApplication.PROFILE_DAEMON)
@DependsOn("schemaInitializer")
public class RuleSetHolder implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(RuleSetHolder.class);

    private final RuleSetLoader loader;
    private final ConfigStore store;
    private final AtomicReference<RuleSet> current = new AtomicReference<>(RuleSet.EMPTY);
    private final List<LongConsumer> reloadListeners = new CopyOnWriteArrayList<>();
    private volatile long failedRevision = -1;

    public RuleSetHolder(RuleSetLoader loader, ConfigStore store) {
        this.loader = loader;
        this.store = store;
    }

    public RuleSet current() {
        return current.get();
    }

    public void onReload(LongConsumer listener) {
        reloadListeners.add(listener);
    }

    @Override
    public void afterPropertiesSet() {
        reloadIfChanged();
    }

    @Scheduled(fixedDelayString = "${reload.intervalMs}", initialDelayString = "${reload.intervalMs}")
    public void reloadIfChanged() {
        try {
            long revision = store.revision();
            if (revision == current.get().revision() || revision == failedRevision) {
                heartbeat();
                return;
            }
            RuleSet next = loader.load();
            current.set(next);
            failedRevision = -1;
            log.info("Configuration revision {} loaded ({} routes)", next.revision(), next.routeCount());
            store.putStatus("daemon.reload_error", null);
            reloadListeners.forEach(l -> l.accept(next.revision()));
        } catch (RuntimeException e) {
            try {
                failedRevision = store.revision();
                store.putStatus("daemon.reload_error", "revision " + failedRevision + ": " + e.getMessage());
            } catch (RuntimeException ignored) {
                // database unavailable; keep previous rules
            }
            log.error("Configuration reload failed, keeping revision {}: {}", current.get().revision(), e.getMessage());
        }
        heartbeat();
    }

    private void heartbeat() {
        try {
            store.putStatus("daemon.loaded_revision", Long.toString(current.get().revision()));
        } catch (RuntimeException e) {
            log.warn("Could not write daemon heartbeat: {}", e.getMessage());
        }
    }
}
