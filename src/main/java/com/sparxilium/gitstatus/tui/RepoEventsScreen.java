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
 * 5.5 Repository event settings: Repository × Event × Destination. Left/Right switches the destination
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
            Ui.info(gui, repo.fullName(), "No Discord destination yet. Add one under \"Discord Destinations\" first.");
            return;
        }
        try {
            capabilities = provider.capabilities(inst);
        } catch (RuntimeException e) {
            Ui.error(gui, "Cannot determine App permissions: " + Ui.message(e));
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

        BasicWindow w = Ui.window("Repository Event Settings");
        Panel panel = new Panel(new LinearLayout(Direction.VERTICAL));
        panel.addComponent(new Label("Repository: " + repo.fullName() + "   [" + (repo.archived() ? "ARCHIVED" : repo.visibility()) + "]"));
        panel.addComponent(new Label(repo.subscribed() ? "Subscription: enabled"
                : "Subscription: disabled (rules are saved, but nothing is sent until enabled with Space in the repository list)"));
        panel.addComponent(destinationLabel);
        panel.addComponent(new EmptySpace());
        panel.addComponent(list);
        panel.addComponent(new EmptySpace());
        panel.addComponent(message);
        panel.addComponent(new Label("Space: On/off   Left/Right: Switch destination   S: Save   Esc: Back"));
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
            message.setText("Unsaved changes");
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
                if (Ui.confirm(gui, "Unsaved changes", "Discard unsaved changes?")) {
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
        destinationLabel.setText("Destination (" + (destIndex + 1) + "/" + destinations.size() + "): < " + d.name()
                + (d.enabled() ? "" : "  [disabled]") + " >");
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
            message.setText("Saved (the daemon applies the new rules automatically)");
        } catch (RuntimeException e) {
            // transaction rolled back, revision unchanged
            Ui.error(gui, "Save failed, nothing was changed: " + Ui.message(e));
        }
    }
}
