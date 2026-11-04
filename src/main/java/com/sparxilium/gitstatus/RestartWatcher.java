package com.sparxilium.gitstatus;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.sparxilium.gitstatus.db.ConfigStore;

/**
 * Restart requested from the admin TUI. The TUI cannot reach the daemon process, so it writes a
 * new token to the database; when the token changes after start-up the daemon shuts down cleanly
 * and exits, and Docker ({@code restart: unless-stopped}) starts the container again.
 */
@Component
@Profile(GitStatusApplication.PROFILE_DAEMON)
@DependsOn("schemaInitializer")
public class RestartWatcher {

    private static final Logger log = LoggerFactory.getLogger(RestartWatcher.class);

    private final ConfigStore store;
    private final Runnable shutdown;
    /** Request token present at start-up; it is already handled (or predates this process). */
    private final String startToken;
    private volatile boolean exiting;

    @Autowired
    public RestartWatcher(ConfigStore store, ApplicationContext context) {
        // closing the context waits for the scheduler, so it cannot run on a scheduler thread
        this(store, () -> Thread.ofPlatform().name("restart")
                .start(() -> System.exit(SpringApplication.exit(context, () -> 0))));
    }

    RestartWatcher(ConfigStore store, Runnable shutdown) {
        this.store = store;
        this.shutdown = shutdown;
        this.startToken = store.restartRequest();
        store.putStatus(ConfigStore.STATUS_DAEMON_STARTED, ConfigStore.now());
    }

    @Scheduled(fixedDelayString = "${reload.intervalMs}", initialDelayString = "${reload.intervalMs}")
    public void checkRestartRequest() {
        if (exiting) {
            return;
        }
        String token;
        try {
            token = store.restartRequest();
        } catch (RuntimeException e) {
            return; // database unavailable; the reload poll reports it
        }
        if (token == null || Objects.equals(token, startToken)) {
            return;
        }
        exiting = true;
        log.info("Restart requested from the admin TUI; shutting down so Docker starts the service again");
        shutdown.run();
    }
}
