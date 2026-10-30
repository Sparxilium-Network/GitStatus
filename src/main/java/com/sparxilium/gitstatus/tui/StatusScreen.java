package com.sparxilium.gitstatus.tui;

import java.util.List;
import java.util.Map;

import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.sparxilium.gitstatus.config.AppSettings;
import com.sparxilium.gitstatus.db.ConfigStore;
import com.sparxilium.gitstatus.model.Records.DestinationHealth;
import com.sparxilium.gitstatus.model.Records.DiscordDestination;
import com.sparxilium.gitstatus.notify.DiscordGateway;
import com.sparxilium.gitstatus.provider.GitProvider;
import com.sparxilium.gitstatus.provider.github.GitHubHookSources;

import tools.jackson.databind.json.JsonMapper;

/** FR-012 system status: provider auth, Discord, DB, daemon, last webhook and last send results. */
final class StatusScreen {

    private final WindowBasedTextGUI gui;
    private final ConfigStore store;
    private final DiscordGateway discord;
    private final List<GitProvider> providers;
    private final AppSettings settings;
    private final Label body = new Label("");

    StatusScreen(WindowBasedTextGUI gui, ConfigStore store, DiscordGateway discord, List<GitProvider> providers,
                 AppSettings settings) {
        this.gui = gui;
        this.store = store;
        this.discord = discord;
        this.providers = providers;
        this.settings = settings;
    }

    void show() {
        BasicWindow w = Ui.window("System Status");
        Panel panel = new Panel(new LinearLayout(Direction.VERTICAL));
        panel.addComponent(body);
        panel.addComponent(new EmptySpace());
        panel.addComponent(new Label("R: Refresh    D: Test Discord connection    Esc: Back"));
        w.setComponent(panel);
        Ui.onKeys(w, (win, key) -> {
            if (Ui.isChar(key, 'r')) {
                refresh(false);
                return true;
            }
            if (Ui.isChar(key, 'd')) {
                refresh(true);
                return true;
            }
            return false;
        });
        refresh(false);
        gui.addWindowAndWait(w);
    }

    private void refresh(boolean connectDiscord) {
        StringBuilder sb = new StringBuilder();

        sb.append("Database\n");
        try {
            sb.append("  ").append(settings.database()).append("  revision ").append(store.revision())
                    .append(" (").append(store.revisionUpdatedAt()).append(")\n");
        } catch (RuntimeException e) {
            sb.append("  Error: ").append(Ui.message(e)).append('\n');
        }

        sb.append("\nDaemon\n");
        store.status("daemon.loaded_revision").ifPresentOrElse(
                s -> sb.append("  Loaded revision ").append(s.value()).append(" (heartbeat ").append(s.updatedAt()).append(")\n"),
                () -> sb.append("  No heartbeat yet (daemon not running?)\n"));
        store.status("daemon.reload_error").filter(s -> s.value() != null)
                .ifPresent(s -> sb.append("  Reload error: ").append(s.value()).append('\n'));
        store.status("daemon.discord").ifPresent(s -> sb.append("  Discord: ").append(s.value()).append('\n'));
        sb.append("  Webhook endpoint: http://<host>:").append(settings.serverPort()).append("/webhooks/github\n");
        store.status(GitHubHookSources.STATUS_KEY).ifPresentOrElse(s -> {
            if (s.value() == null) {
                sb.append("  Source verification: DISABLED\n");
                return;
            }
            int count;
            try {
                count = JsonMapper.builder().build().readTree(s.value()).size();
            } catch (RuntimeException e) {
                count = 0;
            }
            sb.append("  Source verification: ").append(count).append(" GitHub hook ranges (updated ")
                    .append(s.updatedAt()).append(")\n");
        }, () -> sb.append("  Source verification: no GitHub hook ranges loaded (webhooks are rejected)\n"));

        sb.append("\nGit Providers\n");
        for (GitProvider p : providers) {
            sb.append("  ").append(p.displayName()).append(": ");
            if (!p.configured()) {
                sb.append("not configured\n");
                continue;
            }
            try {
                sb.append("authenticated as ")
                        .append(Ui.busy(gui, "Checking " + p.displayName() + " authentication...", p::checkAuth)).append('\n');
            } catch (RuntimeException e) {
                sb.append("failed: ").append(Ui.message(e)).append('\n');
            }
        }
        sb.append("  GitLab: not supported yet (Phase 5)\n");

        sb.append("\nDiscord (admin TUI connection)\n  ");
        if (connectDiscord) {
            try {
                Ui.busy(gui, "Connecting to Discord...", discord::connect);
            } catch (RuntimeException e) {
                sb.append("failed: ").append(Ui.message(e)).append("\n  ");
            }
        }
        sb.append(discord.statusText()).append('\n');

        sb.append("\nLast webhook\n  ");
        store.lastDelivery().ifPresentOrElse(d -> sb.append(d.createdAt()).append("  ").append(d.provider()).append(' ')
                        .append(d.providerEvent()).append("  repo ").append(d.repoId() == null ? "-" : d.repoId())
                        .append("  ").append(d.result()).append(d.latencyMs() == null ? "" : " (" + d.latencyMs() + " ms)"),
                () -> sb.append("none"));
        sb.append('\n');

        sb.append("\nDestination send results\n");
        Map<Long, DestinationHealth> health = store.destinationHealth();
        List<DiscordDestination> destinations = store.destinations();
        if (destinations.isEmpty()) {
            sb.append("  (no destinations)\n");
        }
        for (DiscordDestination d : destinations) {
            DestinationHealth h = health.get(d.id());
            sb.append("  ").append(Ui.pad(d.name(), 28)).append(' ');
            if (h == null) {
                sb.append("nothing sent yet");
            } else {
                sb.append(h.status()).append("  ").append(h.lastAttemptAt() == null ? "" : h.lastAttemptAt());
                if (h.lastError() != null) {
                    sb.append("  ").append(h.lastError());
                }
            }
            sb.append('\n');
        }
        body.setText(sb.toString());
    }
}
