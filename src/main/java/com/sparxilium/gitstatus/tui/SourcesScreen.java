package com.sparxilium.gitstatus.tui;

import java.util.List;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.CheckBoxList;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.sparxilium.gitstatus.db.ConfigStore;
import com.sparxilium.gitstatus.model.Records.InstallationStatus;
import com.sparxilium.gitstatus.model.Records.ProviderInstallation;
import com.sparxilium.gitstatus.provider.GitProvider;

/** 5.3 Git provider sources: installations authorized for the GitHub App, multi-select. */
final class SourcesScreen {

    private final WindowBasedTextGUI gui;
    private final ConfigStore store;
    private final List<GitProvider> providers;
    private final CheckBoxList<Item> list = new CheckBoxList<>(new TerminalSize(64, 12));
    private final Label message = new Label("");
    private boolean suppress;

    private record Item(ProviderInstallation inst) {
        @Override
        public String toString() {
            return Ui.pad(inst.owner(), 32) + " " + Ui.pad(inst.type(), 14) + " " + inst.provider();
        }
    }

    SourcesScreen(WindowBasedTextGUI gui, ConfigStore store, List<GitProvider> providers) {
        this.gui = gui;
        this.store = store;
        this.providers = providers;
    }

    void show() {
        BasicWindow w = Ui.window("Git Provider Sources");
        Panel panel = new Panel(new LinearLayout(Direction.VERTICAL));
        panel.addComponent(new Label("Select accounts / organizations to notify on (only installations the App is authorized for)"));
        panel.addComponent(list);
        panel.addComponent(message);
        panel.addComponent(new Label("GitLab: not supported yet (Phase 5)"));
        panel.addComponent(new EmptySpace());
        panel.addComponent(new Label("Space: Select/clear    R: Refresh from API    Esc: Back"));
        w.setComponent(panel);

        list.addListener((index, checked) -> {
            if (suppress) {
                return;
            }
            Item item = list.getItemAt(index);
            try {
                store.setInstallationSelected(item.inst().id(), checked);
            } catch (RuntimeException e) {
                Ui.error(gui, "Save failed: " + Ui.message(e));
                reload();
            }
        });
        Ui.onKeys(w, (win, key) -> {
            if (Ui.isChar(key, 'r')) {
                refreshFromApi();
                return true;
            }
            return false;
        });
        refreshFromApi();
        gui.addWindowAndWait(w);
    }

    private void refreshFromApi() {
        StringBuilder status = new StringBuilder();
        for (GitProvider p : providers) {
            if (!p.configured()) {
                status.append(p.displayName()).append(": not configured (config.conf / secrets)  ");
                continue;
            }
            try {
                var discovered = Ui.busy(gui, "Loading " + p.displayName() + " installations...", p::listInstallations);
                store.syncInstallations(p.id(), discovered);
                status.append(p.displayName()).append(": synced ").append(discovered.size()).append(" source(s)  ");
            } catch (RuntimeException e) {
                // keep the cached list; API failure must not overwrite valid configuration
                status.append(p.displayName()).append(" API failed, showing cached list: ").append(Ui.message(e)).append("  ");
            }
        }
        message.setText(status.toString().trim());
        reload();
    }

    private void reload() {
        suppress = true;
        try {
            list.clearItems();
            for (GitProvider p : providers) {
                for (ProviderInstallation inst : store.installations(p.id())) {
                    if (inst.status() != InstallationStatus.REVOKED) {
                        list.addItem(new Item(inst), inst.status() == InstallationStatus.SELECTED);
                    }
                }
            }
        } finally {
            suppress = false;
        }
    }
}
