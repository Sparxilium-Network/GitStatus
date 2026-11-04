package com.sparxilium.gitstatus.tui;

import java.util.ArrayList;
import java.util.List;

import com.googlecode.lanterna.input.KeyStroke;
import com.sparxilium.gitstatus.db.ConfigStore;
import com.sparxilium.gitstatus.model.Records.InstallationStatus;
import com.sparxilium.gitstatus.model.Records.ProviderInstallation;
import com.sparxilium.gitstatus.provider.GitProvider;

/** 5.3 Git provider sources: installations authorized for the GitHub App, multi-select. */
final class SourcesScreen extends Tui.View {

    private final Tui tui;
    private final ConfigStore store;
    private final List<GitProvider> providers;
    private List<ProviderInstallation> installations = List.of();
    private String syncStatus = "";

    SourcesScreen(Tui tui, ConfigStore store, List<GitProvider> providers) {
        this.tui = tui;
        this.store = store;
        this.providers = providers;
    }

    void show() {
        refreshFromApi();
        tui.show(this);
    }

    @Override
    String title() {
        return "Git Provider Sources";
    }

    @Override
    List<String> header() {
        List<String> lines = new ArrayList<>();
        lines.add("Select the accounts / organizations to notify on");
        lines.add("(only installations the App is authorized for).");
        lines.add("");
        lines.add(syncStatus);
        lines.add("GitLab: not supported yet (Phase 5)");
        if (installations.isEmpty()) {
            lines.add("");
            lines.add("No installation found. Install the GitHub App on an account first.");
        }
        return lines;
    }

    @Override
    List<String> rows() {
        installations = new ArrayList<>();
        for (GitProvider p : providers) {
            for (ProviderInstallation inst : store.installations(p.id())) {
                if (inst.status() != InstallationStatus.REVOKED) {
                    installations.add(inst);
                }
            }
        }
        return installations.stream()
                .map(i -> Tui.checkbox(i.status() == InstallationStatus.SELECTED)
                        + Tui.pad(i.owner(), 32) + " " + Tui.pad(i.type(), 14) + " " + i.provider())
                .toList();
    }

    @Override
    String keys() {
        return "Up/Down Move    Space Select/clear    R Refresh from API    Esc Back";
    }

    @Override
    boolean onKey(KeyStroke key) {
        if (Tui.isSpace(key) && cursor < installations.size()) {
            ProviderInstallation inst = installations.get(cursor);
            try {
                store.setInstallationSelected(inst.id(), inst.status() != InstallationStatus.SELECTED);
            } catch (RuntimeException e) {
                message = "Save failed: " + Tui.message(e);
            }
            return true;
        }
        if (Tui.isChar(key, 'r')) {
            refreshFromApi();
            return true;
        }
        return super.onKey(key);
    }

    private void refreshFromApi() {
        StringBuilder status = new StringBuilder();
        for (GitProvider p : providers) {
            if (!p.configured()) {
                status.append(p.displayName()).append(": not configured (config.conf / secrets)  ");
                continue;
            }
            try {
                var discovered = tui.busy("Loading " + p.displayName() + " installations...", p::listInstallations);
                store.syncInstallations(p.id(), discovered);
                status.append(p.displayName()).append(": synced ").append(discovered.size()).append(" source(s)  ");
            } catch (RuntimeException e) {
                // keep the cached list; API failure must not overwrite valid configuration
                status.append(p.displayName()).append(" API failed, showing cached list: ").append(Tui.message(e)).append("  ");
            }
        }
        syncStatus = status.toString().trim();
    }
}
