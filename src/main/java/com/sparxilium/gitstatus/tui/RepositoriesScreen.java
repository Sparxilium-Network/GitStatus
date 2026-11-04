package com.sparxilium.gitstatus.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.sparxilium.gitstatus.db.ConfigStore;
import com.sparxilium.gitstatus.model.Records.InstallationStatus;
import com.sparxilium.gitstatus.model.Records.ProviderInstallation;
import com.sparxilium.gitstatus.model.Records.RepositoryRecord;
import com.sparxilium.gitstatus.provider.GitProvider;

/** 5.4 Repository selection: list from the provider API, Space subscribes, Enter opens event rules. */
final class RepositoriesScreen {

    private final Tui tui;
    private final ConfigStore store;
    private final List<GitProvider> providers;

    private record Source(GitProvider provider, ProviderInstallation inst) {
        @Override
        public String toString() {
            return provider.displayName() + " / " + inst.owner() + (inst.appId() == null ? "" : "  (App " + inst.appId() + ")");
        }
    }

    RepositoriesScreen(Tui tui, ConfigStore store, List<GitProvider> providers) {
        this.tui = tui;
        this.store = store;
        this.providers = providers;
    }

    void show() {
        List<Source> sources = new ArrayList<>();
        for (GitProvider p : providers) {
            for (ProviderInstallation inst : store.installations(p.id())) {
                if (inst.status() == InstallationStatus.SELECTED) {
                    sources.add(new Source(p, inst));
                }
            }
        }
        if (sources.isEmpty()) {
            tui.info("Repositories / Notification Rules",
                    "No source selected. Select accounts / organizations under \"Git Provider Sources\" first.");
            return;
        }
        while (true) {
            Source source = tui.choose("Repositories / Notification Rules", "Select source", sources);
            if (source == null) {
                return;
            }
            openInstallation(source.provider(), source.inst());
        }
    }

    private void openInstallation(GitProvider provider, ProviderInstallation inst) {
        String warning = "";
        try {
            var repos = tui.busy("Loading repositories...", () -> provider.listRepositories(inst));
            store.syncRepositories(inst.id(), repos);
        } catch (RuntimeException e) {
            warning = "API failed, showing cached list: " + Tui.message(e);
        }
        RepoList list = new RepoList(provider, inst);
        list.message = warning;
        tui.show(list);
    }

    private final class RepoList extends Tui.View {
        private final GitProvider provider;
        private final ProviderInstallation inst;
        private List<RepositoryRecord> repos = List.of();
        private Set<String> shared = Set.of();

        RepoList(GitProvider provider, ProviderInstallation inst) {
            this.provider = provider;
            this.inst = inst;
        }

        @Override
        String title() {
            return "Repositories / Notification Rules";
        }

        @Override
        List<String> header() {
            String type = "Organization".equals(inst.type()) ? "Organization" : "User";
            long enabled = repos.stream().filter(RepositoryRecord::subscribed).count();
            List<String> lines = new ArrayList<>();
            lines.add(type + ": " + inst.owner() + (inst.appId() == null ? "" : " via App " + inst.appId())
                    + "    (" + repos.size() + " repos, " + enabled + " enabled)");
            if (repos.stream().anyMatch(r -> shared.contains(r.externalRepoId()))) {
                lines.add("* also reachable through another App: enabling it in both sends every notification twice");
            }
            if (repos.isEmpty()) {
                lines.add("");
                lines.add("No repository. Add repositories to the GitHub App installation first.");
            }
            return lines;
        }

        @Override
        List<String> rows() {
            repos = store.repositories(inst.id());
            shared = store.repositoriesInSeveralSources();
            return repos.stream()
                    .map(r -> Tui.checkbox(r.subscribed()) + Tui.pad(r.fullName(), 50) + " "
                            + (r.archived() ? "ARCHIVED" : r.visibility())
                            + (shared.contains(r.externalRepoId()) ? "  *" : ""))
                    .toList();
        }

        @Override
        String keys() {
            return "Up/Down Move    Space Enable/disable    Enter Event settings    Esc Back";
        }

        @Override
        boolean onKey(KeyStroke key) {
            if (cursor >= repos.size()) {
                return super.onKey(key);
            }
            RepositoryRecord repo = repos.get(cursor);
            if (Tui.isSpace(key)) {
                try {
                    store.setSubscribed(repo.id(), !repo.subscribed());
                } catch (RuntimeException e) {
                    message = "Save failed: " + Tui.message(e);
                }
                return true;
            }
            if (key.getKeyType() == KeyType.Enter) {
                ProviderInstallation current = store.installation(inst.id()).orElse(inst);
                new RepoEventsScreen(tui, store, provider, current, repo).show();
                return true;
            }
            return super.onKey(key);
        }
    }
}
