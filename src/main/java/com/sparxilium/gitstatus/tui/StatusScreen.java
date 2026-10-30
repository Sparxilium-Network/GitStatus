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

/** FR-012 系統狀態: provider auth, Discord, DB, daemon, last webhook and last send results. */
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
        BasicWindow w = Ui.window("系統狀態");
        Panel panel = new Panel(new LinearLayout(Direction.VERTICAL));
        panel.addComponent(body);
        panel.addComponent(new EmptySpace());
        panel.addComponent(new Label("R: 重新整理    D: 測試 Discord 連線    Esc: 返回"));
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

        sb.append("資料庫\n");
        try {
            sb.append("  ").append(settings.database()).append("  revision ").append(store.revision())
                    .append(" (").append(store.revisionUpdatedAt()).append(")\n");
        } catch (RuntimeException e) {
            sb.append("  錯誤: ").append(Ui.message(e)).append('\n');
        }

        sb.append("\nDaemon\n");
        store.status("daemon.loaded_revision").ifPresentOrElse(
                s -> sb.append("  已載入 revision ").append(s.value()).append("（心跳 ").append(s.updatedAt()).append("）\n"),
                () -> sb.append("  尚無心跳紀錄（Daemon 未啟動？）\n"));
        store.status("daemon.reload_error").filter(s -> s.value() != null)
                .ifPresent(s -> sb.append("  Reload 錯誤: ").append(s.value()).append('\n'));
        store.status("daemon.discord").ifPresent(s -> sb.append("  Discord: ").append(s.value()).append('\n'));
        sb.append("  Webhook endpoint: http://<host>:").append(settings.serverPort()).append("/webhooks/github\n");

        sb.append("\nGit Provider\n");
        for (GitProvider p : providers) {
            sb.append("  ").append(p.displayName()).append(": ");
            if (!p.configured()) {
                sb.append("未設定\n");
                continue;
            }
            try {
                sb.append("認證成功 ").append(Ui.busy(gui, "檢查 " + p.displayName() + " 認證…", p::checkAuth)).append('\n');
            } catch (RuntimeException e) {
                sb.append("失敗 ").append(Ui.message(e)).append('\n');
            }
        }
        sb.append("  GitLab: 尚未支援（Phase 5）\n");

        sb.append("\nDiscord（Admin TUI 連線）\n  ");
        if (connectDiscord) {
            try {
                Ui.busy(gui, "連線 Discord…", discord::connect);
            } catch (RuntimeException e) {
                sb.append("失敗 ").append(Ui.message(e)).append('\n');
            }
        }
        sb.append(discord.statusText()).append('\n');

        sb.append("\n最近一次 Webhook\n  ");
        store.lastDelivery().ifPresentOrElse(d -> sb.append(d.createdAt()).append("  ").append(d.provider()).append(' ')
                        .append(d.providerEvent()).append("  repo ").append(d.repoId() == null ? "-" : d.repoId())
                        .append("  ").append(d.result()).append(d.latencyMs() == null ? "" : " (" + d.latencyMs() + " ms)"),
                () -> sb.append("尚無"));
        sb.append('\n');

        sb.append("\n目的地發送結果\n");
        Map<Long, DestinationHealth> health = store.destinationHealth();
        List<DiscordDestination> destinations = store.destinations();
        if (destinations.isEmpty()) {
            sb.append("  （無目的地）\n");
        }
        for (DiscordDestination d : destinations) {
            DestinationHealth h = health.get(d.id());
            sb.append("  ").append(Ui.pad(d.name(), 28)).append(' ');
            if (h == null) {
                sb.append("尚未發送");
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
