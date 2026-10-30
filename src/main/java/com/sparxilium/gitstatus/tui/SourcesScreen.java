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

/** 5.3 Git Provider 來源選擇: installations authorized for the GitHub App, multi-select. */
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
        BasicWindow w = Ui.window("Git Provider 來源");
        Panel panel = new Panel(new LinearLayout(Direction.VERTICAL));
        panel.addComponent(new Label("勾選要納入通知系統的帳號 / Organization（僅列出 App 已授權的 Installation）"));
        panel.addComponent(list);
        panel.addComponent(message);
        panel.addComponent(new Label("GitLab: 尚未支援（Phase 5）"));
        panel.addComponent(new EmptySpace());
        panel.addComponent(new Label("Space: 選取/取消    R: 從 API 重新整理    Esc: 返回"));
        w.setComponent(panel);

        list.addListener((index, checked) -> {
            if (suppress) {
                return;
            }
            Item item = list.getItemAt(index);
            try {
                store.setInstallationSelected(item.inst().id(), checked);
            } catch (RuntimeException e) {
                Ui.error(gui, "保存失敗：" + Ui.message(e));
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
                status.append(p.displayName()).append(": 未設定（config.conf / secrets）  ");
                continue;
            }
            try {
                var discovered = Ui.busy(gui, "讀取 " + p.displayName() + " Installations…", p::listInstallations);
                store.syncInstallations(p.id(), discovered);
                status.append(p.displayName()).append(": 已同步 ").append(discovered.size()).append(" 個來源  ");
            } catch (RuntimeException e) {
                // keep the cached list; API failure must not overwrite valid configuration
                status.append(p.displayName()).append(" API 失敗，顯示快取: ").append(Ui.message(e)).append("  ");
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
