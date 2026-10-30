package com.sparxilium.gitstatus.tui;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.googlecode.lanterna.TerminalSize;
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
import com.sparxilium.gitstatus.model.EventType;
import com.sparxilium.gitstatus.model.Records.DiscordDestination;
import com.sparxilium.gitstatus.model.Records.NotificationRule;
import com.sparxilium.gitstatus.model.Records.ProviderInstallation;
import com.sparxilium.gitstatus.model.Records.RepositoryRecord;
import com.sparxilium.gitstatus.provider.GitProvider;
import com.sparxilium.gitstatus.provider.GitProvider.Availability;

/**
 * 5.5 Repository 事件設定: Repository × Event × Destination. Left/Right switches the destination
 * being edited; S saves all destinations in one transaction.
 */
final class RepoEventsScreen {

    private final WindowBasedTextGUI gui;
    private final ConfigStore store;
    private final GitProvider provider;
    private final ProviderInstallation inst;
    private final RepositoryRecord repo;

    private final CheckBoxList<Item> list = new CheckBoxList<>(new TerminalSize(70, EventType.values().length));
    private final Label destinationLabel = new Label("");
    private final Label message = new Label("");
    private List<DiscordDestination> destinations;
    private final Map<Long, Set<EventType>> pending = new HashMap<>();
    private Map<EventType, Availability> capabilities;
    private int destIndex;
    private boolean dirty;
    private boolean suppress;

    private record Item(EventType type, Availability availability, boolean firstOfCategory) {
        @Override
        public String toString() {
            String category = firstOfCategory ? type.category() : "";
            String suffix = availability == Availability.AVAILABLE ? "" : "   (" + availability.reason() + ")";
            return Ui.pad(category, 16) + type.label() + suffix;
        }
    }

    RepoEventsScreen(WindowBasedTextGUI gui, ConfigStore store, GitProvider provider, ProviderInstallation inst,
                     RepositoryRecord repo) {
        this.gui = gui;
        this.store = store;
        this.provider = provider;
        this.inst = inst;
        this.repo = repo;
    }

    void show() {
        destinations = store.destinations();
        if (destinations.isEmpty()) {
            Ui.info(gui, repo.fullName(), "尚未建立 Discord 目的地，請先到「Discord 目的地」新增。");
            return;
        }
        try {
            capabilities = provider.capabilities(inst);
        } catch (RuntimeException e) {
            Ui.error(gui, "無法判斷 App 權限：" + Ui.message(e));
            return;
        }
        for (DiscordDestination d : destinations) {
            pending.put(d.id(), EnumSet.noneOf(EventType.class));
        }
        for (NotificationRule rule : store.rules(repo.id())) {
            if (rule.enabled() && pending.containsKey(rule.destinationId())) {
                pending.get(rule.destinationId()).add(rule.eventType());
            }
        }

        BasicWindow w = Ui.window("Repository 事件設定");
        Panel panel = new Panel(new LinearLayout(Direction.VERTICAL));
        panel.addComponent(new Label("Repository: " + repo.fullName() + "   [" + (repo.archived() ? "ARCHIVED" : repo.visibility()) + "]"));
        panel.addComponent(new Label(repo.subscribed() ? "訂閱: 啟用"
                : "訂閱: 未啟用（規則會保存，但需在 Repository 清單按 Space 啟用後才會通知）"));
        panel.addComponent(destinationLabel);
        panel.addComponent(new EmptySpace());
        panel.addComponent(list);
        panel.addComponent(new EmptySpace());
        panel.addComponent(message);
        panel.addComponent(new Label("Space: 開/關   Left/Right: 切換目的地   S: Save   Esc: 返回"));
        w.setComponent(panel);

        list.addListener((index, checked) -> {
            if (suppress) {
                return;
            }
            Item item = list.getItemAt(index);
            Set<EventType> set = pending.get(currentDestination().id());
            if (checked && item.availability() != Availability.AVAILABLE) {
                // cannot enable an event the installation cannot deliver; disabling is always allowed
                suppress = true;
                list.setChecked(item, false);
                suppress = false;
                message.setText(item.type().label() + ": " + item.availability().reason());
                return;
            }
            if (checked) {
                set.add(item.type());
            } else {
                set.remove(item.type());
            }
            dirty = true;
            message.setText("尚未保存");
        });

        Ui.onKeys(w, (win, key) -> {
            if (Ui.isChar(key, 's')) {
                save();
                return true;
            }
            if (key.getKeyType() == KeyType.ArrowLeft || key.getKeyType() == KeyType.ArrowRight) {
                int delta = key.getKeyType() == KeyType.ArrowLeft ? -1 : 1;
                destIndex = (destIndex + delta + destinations.size()) % destinations.size();
                render();
                return true;
            }
            if (key.getKeyType() == KeyType.Enter) {
                return true; // Space toggles; Enter does nothing here
            }
            if (key.getKeyType() == KeyType.Escape && dirty) {
                if (Ui.confirm(gui, "尚未保存", "放棄未保存的變更？")) {
                    win.close();
                }
                return true;
            }
            return false;
        });
        render();
        gui.addWindowAndWait(w);
    }

    private DiscordDestination currentDestination() {
        return destinations.get(destIndex);
    }

    private void render() {
        DiscordDestination d = currentDestination();
        destinationLabel.setText("目的地 (" + (destIndex + 1) + "/" + destinations.size() + "): < " + d.name()
                + (d.enabled() ? "" : "  [停用]") + " >");
        Set<EventType> enabled = pending.get(d.id());
        suppress = true;
        try {
            int selected = list.getSelectedIndex();
            list.clearItems();
            String lastCategory = null;
            for (EventType type : EventType.values()) {
                boolean first = !type.category().equals(lastCategory);
                lastCategory = type.category();
                list.addItem(new Item(type, capabilities.getOrDefault(type, Availability.UNSUPPORTED), first),
                        enabled.contains(type));
            }
            list.setSelectedIndex(Math.max(0, selected));
        } finally {
            suppress = false;
        }
    }

    private void save() {
        try {
            store.saveRules(repo.id(), pending);
            dirty = false;
            message.setText("已保存（Daemon 將自動套用新規則）");
        } catch (RuntimeException e) {
            // transaction rolled back, revision unchanged
            Ui.error(gui, "保存失敗，未變更任何設定：" + Ui.message(e));
        }
    }
}
