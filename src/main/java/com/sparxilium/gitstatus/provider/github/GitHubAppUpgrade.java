package com.sparxilium.gitstatus.provider.github;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import com.sparxilium.gitstatus.db.ConfigStore;

/**
 * Upgrade from a single-App setup: installations saved before multi-App support have no App.
 * With exactly one App configured they all belong to it, so they are assigned on startup and keep
 * routing. With several Apps the next "Git Provider Sources" refresh assigns them.
 */
@Component
@DependsOn("schemaInitializer")
public class GitHubAppUpgrade implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(GitHubAppUpgrade.class);

    private final ConfigStore store;
    private final GitHubApps apps;

    public GitHubAppUpgrade(ConfigStore store, GitHubApps apps) {
        this.store = store;
        this.apps = apps;
    }

    @Override
    public void afterPropertiesSet() {
        int unassigned = store.installationsWithoutApp(GitHubProvider.ID);
        if (unassigned == 0) {
            return;
        }
        List<String> ids = apps.ids();
        if (ids.size() == 1) {
            store.assignInstallationsWithoutApp(GitHubProvider.ID, ids.get(0));
            log.info("Assigned {} existing GitHub installation(s) to App {}", unassigned, ids.get(0));
        } else {
            log.warn("{} GitHub installation(s) have no App yet and receive no notifications: open the admin TUI "
                    + "-> Git Provider Sources to refresh them", unassigned);
        }
    }
}
