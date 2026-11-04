package com.sparxilium.gitstatus.tui;

import java.util.ArrayList;
import java.util.List;

import com.googlecode.lanterna.input.KeyStroke;
import com.sparxilium.gitstatus.db.ConfigStore;
import com.sparxilium.gitstatus.model.Records.InstallationStatus;
import com.sparxilium.gitstatus.model.Records.ProviderInstallation;
import com.sparxilium.gitstatus.provider.GitProvider;

/** 5.3 Git provider sources: installations authorized for each configured GitHub App, multi-select. */
final class SourcesScreen extends Tui.View {

    private final Tui tui;
    private final ConfigStore store;
    private final List<GitProvider> providers;
    private List<ProviderInstallation> installations = List.of();
    private List<String> syncStatus = List.of();

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
        lines.add("(only installations your GitHub Apps are authorized for; a private App");
        lines.add("can only be installed on its own account, so add one App per organization).");
        lines.add("");
        lines.addAll(syncStatus);
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
                        + Tui.pad(i.owner(), 32) + " " + Tui.pad(i.type(), 14) + " " + i.provider()
                        + (i.appId() == null ? " (no App yet)" : " App " + i.appId()))
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
        List<String> status = new ArrayList<>();
        for (GitProvider p : providers) {
            List<GitProvider.Credential> credentials = p.credentials();
            if (credentials.isEmpty()) {
                status.add(p.displayName() + ": no App configured (run setup.sh to add one)");
                continue;
            }
            for (GitProvider.Credential c : credentials) {
                String name = p.displayName() + " " + c.label();
                try {
                    String identity = tui.busy("Checking " + name + "...", () -> p.checkAuth(c));
                    var discovered = tui.busy("Loading " + name + " installations...", () -> p.listInstallations(c));
                    store.syncInstallations(p.id(), c.id(), discovered);
                    status.add(p.displayName() + " " + identity + ": synced " + discovered.size() + " source(s)");
                } catch (RuntimeException e) {
                    // keep the cached list; API failure must not overwrite valid configuration
                    status.add(name + ": API failed, showing cached list: " + Tui.message(e));
                }
            }
        }
        syncStatus = status;
    }
}
