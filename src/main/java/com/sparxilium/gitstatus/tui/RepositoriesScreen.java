package com.sparxilium.gitstatus.tui;

import java.util.List;
import java.util.Optional;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.ActionListBox;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.CheckBoxList;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.input.KeyType;
import com.sparxilium.gitstatus.db.ConfigStore;
import com.sparxilium.gitstatus.model.Records.InstallationStatus;
import com.sparxilium.gitstatus.model.Records.ProviderInstallation;
import com.sparxilium.gitstatus.model.Records.RepositoryRecord;
import com.sparxilium.gitstatus.provider.GitProvider;

/** 5.4 Repository selection: paged list from the provider API, Space subscribes, Enter opens event rules. */
final class RepositoriesScreen {

    private static final int PAGE_SIZE = 15;

    private final WindowBasedTextGUI gui;
    private final ConfigStore store;
    private final List<GitProvider> providers;

    RepositoriesScreen(WindowBasedTextGUI gui, ConfigStore store, List<GitProvider> providers) {
        this.gui = gui;
        this.store = store;
        this.providers = providers;
    }

    void show() {
        BasicWindow w = Ui.window("Repositories / Notification Rules");
        ActionListBox sources = new ActionListBox(new TerminalSize(50, 10));
        for (GitProvider p : providers) {
            for (ProviderInstallation inst : store.installations(p.id())) {
                if (inst.status() == InstallationStatus.SELECTED) {
                    sources.addItem(p.displayName() + " / " + inst.owner(), () -> openInstallation(p, inst));
                }
            }
        }
        Panel panel = new Panel(new LinearLayout(Direction.VERTICAL));
        if (sources.isEmpty()) {
            panel.addComponent(new Label("No source selected. Select accounts / organizations under \"Git Provider Sources\" first."));
        } else {
            panel.addComponent(new Label("Select source"));
            panel.addComponent(sources);
        }
        panel.addComponent(new EmptySpace());
        panel.addComponent(new Label("Enter: Open    Esc: Back"));
        w.setComponent(panel);
        Ui.onKeys(w, (win, key) -> false);
        gui.addWindowAndWait(w);
    }

    private void openInstallation(GitProvider provider, ProviderInstallation inst) {
        String warning = "";
        try {
            var repos = Ui.busy(gui, "Loading repositories...", () -> provider.listRepositories(inst));
            store.syncRepositories(inst.id(), repos);
        } catch (RuntimeException e) {
            warning = "API failed, showing cached list: " + Ui.message(e);
        }
        new RepoList(provider, inst, warning).show();
    }

    private final class RepoList {
        private final GitProvider provider;
        private final ProviderInstallation inst;
        private final CheckBoxList<Item> list = new CheckBoxList<>(new TerminalSize(64, PAGE_SIZE));
        private final Label header = new Label("");
        private final Label message;
        private List<RepositoryRecord> repos = List.of();
        private int page;
        private boolean suppress;

        private record Item(RepositoryRecord repo) {
            @Override
            public String toString() {
                String flag = repo.archived() ? "ARCHIVED" : repo.visibility();
                return Ui.pad(repo.fullName(), 50) + " " + flag;
            }
        }

        RepoList(GitProvider provider, ProviderInstallation inst, String warning) {
            this.provider = provider;
            this.inst = inst;
            this.message = new Label(warning);
        }

        void show() {
            BasicWindow w = Ui.window("Repository");
            Panel panel = new Panel(new LinearLayout(Direction.VERTICAL));
            panel.addComponent(header);
            panel.addComponent(list);
            panel.addComponent(message);
            panel.addComponent(new Label("Space: Enable/disable   Enter: Repo settings   Left/Right: Page   Esc: Back"));
            w.setComponent(panel);

            list.addListener((index, checked) -> {
                if (suppress) {
                    return;
                }
                RepositoryRecord r = list.getItemAt(index).repo();
                try {
                    store.setSubscribed(r.id(), checked);
                    reloadData();
                } catch (RuntimeException e) {
                    Ui.error(gui, "Save failed: " + Ui.message(e));
                    reloadData();
                    render();
                }
            });
            Ui.onKeys(w, (win, key) -> {
                if (key.getKeyType() == KeyType.Enter) {
                    Item selected = list.getSelectedItem();
                    if (selected != null) {
                        openEvents(selected.repo());
                    }
                    return true;
                }
                if (key.getKeyType() == KeyType.ArrowLeft) {
                    turnPage(-1);
                    return true;
                }
                if (key.getKeyType() == KeyType.ArrowRight) {
                    turnPage(1);
                    return true;
                }
                return false;
            });
            reloadData();
            render();
            gui.addWindowAndWait(w);
        }

        private int pageCount() {
            return Math.max(1, (repos.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        }

        private void turnPage(int delta) {
            int next = Math.max(0, Math.min(pageCount() - 1, page + delta));
            if (next != page) {
                page = next;
                render();
            }
        }

        private void reloadData() {
            repos = store.repositories(inst.id());
            page = Math.min(page, pageCount() - 1);
        }

        private void render() {
            String type = "Organization".equals(inst.type()) ? "Organization" : "User";
            header.setText(type + ": " + inst.owner() + "    Page " + (page + 1) + "/" + pageCount()
                    + "    (" + repos.size() + " repos)");
            suppress = true;
            try {
                int selected = list.getSelectedIndex();
                list.clearItems();
                int from = page * PAGE_SIZE;
                for (RepositoryRecord r : repos.subList(Math.min(from, repos.size()), Math.min(from + PAGE_SIZE, repos.size()))) {
                    list.addItem(new Item(r), r.subscribed());
                }
                if (!list.isEmpty()) {
                    list.setSelectedIndex(Math.max(0, Math.min(selected, list.getItemCount() - 1)));
                }
            } finally {
                suppress = false;
            }
        }

        private void openEvents(RepositoryRecord repo) {
            Optional<RepositoryRecord> fresh = repos.stream().filter(r -> r.id() == repo.id()).findFirst();
            ProviderInstallation current = store.installation(inst.id()).orElse(inst);
            new RepoEventsScreen(gui, store, provider, current, fresh.orElse(repo)).show();
            reloadData();
            render();
        }
    }
}
