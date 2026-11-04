package com.sparxilium.gitstatus.tui;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.googlecode.lanterna.input.KeyStroke;
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
 * 5.5 Repository event settings: Repository × Event × Destination. Events are listed under their
 * category; Space on a category turns all of its available events on or off. Left/Right switches the
 * destination being edited; S saves all destinations in one transaction.
 */
final class RepoEventsScreen extends Tui.View {

    /** A list row: a category heading (event == null) or one event. */
    private record Row(String category, EventType event) {
    }

    private static final List<Row> ROWS = buildRows();

    private final Tui tui;
    private final ConfigStore store;
    private final GitProvider provider;
    private final ProviderInstallation inst;
    private final RepositoryRecord repo;

    private List<DiscordDestination> destinations;
    private final Map<Long, Set<EventType>> pending = new HashMap<>();
    private Map<EventType, Availability> capabilities;
    private int destIndex;
    private boolean dirty;

    RepoEventsScreen(Tui tui, ConfigStore store, GitProvider provider, ProviderInstallation inst, RepositoryRecord repo) {
        this.tui = tui;
        this.store = store;
        this.provider = provider;
        this.inst = inst;
        this.repo = repo;
    }

    void show() {
        destinations = store.destinations();
        if (destinations.isEmpty()) {
            tui.info(repo.fullName(), "No Discord destination yet. Add one under \"Discord Destinations\" first.");
            return;
        }
        try {
            capabilities = provider.capabilities(inst);
        } catch (RuntimeException e) {
            tui.error("Cannot determine App permissions: " + Tui.message(e));
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
        tui.show(this);
    }

    private DiscordDestination currentDestination() {
        return destinations.get(destIndex);
    }

    private static List<Row> buildRows() {
        List<Row> rows = new ArrayList<>();
        String lastCategory = null;
        for (EventType type : EventType.values()) {
            if (!type.category().equals(lastCategory)) {
                rows.add(new Row(type.category(), null));
                lastCategory = type.category();
            }
            rows.add(new Row(type.category(), type));
        }
        return List.copyOf(rows);
    }

    private static List<EventType> eventsIn(String category) {
        return ROWS.stream().filter(r -> r.event() != null && r.category().equals(category)).map(Row::event).toList();
    }

    private Availability availability(EventType type) {
        return capabilities.getOrDefault(type, Availability.UNSUPPORTED);
    }

    @Override
    String title() {
        return "Repository Event Settings";
    }

    @Override
    List<String> header() {
        DiscordDestination d = currentDestination();
        List<String> lines = new ArrayList<>();
        lines.add("Repository   : " + repo.fullName() + "  [" + (repo.archived() ? "ARCHIVED" : repo.visibility()) + "]");
        lines.add("Subscription : " + (repo.subscribed() ? "enabled"
                : "disabled (rules are saved, but nothing is sent until enabled in the repository list)"));
        lines.add("Destination  : < " + d.name() + (d.enabled() ? "" : "  [disabled]") + " >  ("
                + (destIndex + 1) + "/" + destinations.size() + ")");
        return lines;
    }

    @Override
    List<String> rows() {
        Set<EventType> enabled = pending.get(currentDestination().id());
        List<String> rows = new ArrayList<>();
        for (Row row : ROWS) {
            if (row.event() == null) {
                List<EventType> events = eventsIn(row.category());
                long on = events.stream().filter(enabled::contains).count();
                String box = on == 0 ? "[ ] " : on == events.size() ? "[*] " : "[-] ";
                boolean anyAvailable = events.stream().anyMatch(e -> availability(e) == Availability.AVAILABLE);
                rows.add(box + row.category() + (anyAvailable ? "" : "   (" + availability(events.get(0)).reason() + ")"));
            } else {
                Availability a = availability(row.event());
                rows.add("    " + Tui.checkbox(enabled.contains(row.event())) + row.event().label()
                        + (a == Availability.AVAILABLE ? "" : "   (" + a.reason() + ")"));
            }
        }
        return rows;
    }

    @Override
    String keys() {
        return "Up/Down Move    Space On/off (category: all)    Left/Right Switch destination\nS Save    Esc Back";
    }

    @Override
    boolean onKey(KeyStroke key) {
        if (Tui.isSpace(key)) {
            Row row = ROWS.get(cursor);
            if (row.event() == null) {
                toggleCategory(row.category());
            } else {
                toggle(row.event());
            }
            return true;
        }
        if (Tui.isChar(key, 's')) {
            save();
            return true;
        }
        if (key.getKeyType() == KeyType.ArrowLeft || key.getKeyType() == KeyType.ArrowRight) {
            int delta = key.getKeyType() == KeyType.ArrowLeft ? -1 : 1;
            destIndex = (destIndex + delta + destinations.size()) % destinations.size();
            message = dirty ? "Unsaved changes" : "";
            return true;
        }
        if (key.getKeyType() == KeyType.Escape) {
            return dirty && !tui.confirm("Unsaved changes", "Discard unsaved changes?");
        }
        return true;
    }

    private void toggle(EventType type) {
        Set<EventType> set = pending.get(currentDestination().id());
        Availability a = availability(type);
        if (!set.contains(type) && a != Availability.AVAILABLE) {
            // cannot enable an event the installation cannot deliver; disabling is always allowed
            message = type.label() + ": " + a.reason();
            return;
        }
        if (!set.remove(type)) {
            set.add(type);
        }
        dirty = true;
        message = "Unsaved changes";
    }

    /** Turns every available event of the category on, or all of them off when they are already on. */
    private void toggleCategory(String category) {
        Set<EventType> set = pending.get(currentDestination().id());
        List<EventType> events = eventsIn(category);
        List<EventType> available = events.stream().filter(e -> availability(e) == Availability.AVAILABLE).toList();
        if (!available.isEmpty() && !set.containsAll(available)) {
            set.addAll(available);
        } else if (events.stream().anyMatch(set::contains)) {
            events.forEach(set::remove);
        } else {
            // nothing to turn on and nothing to turn off
            message = category + ": " + availability(events.get(0)).reason();
            return;
        }
        dirty = true;
        message = "Unsaved changes";
    }

    private void save() {
        try {
            store.saveRules(repo.id(), pending);
            dirty = false;
            message = "Saved (the daemon applies the new rules automatically)";
        } catch (RuntimeException e) {
            // transaction rolled back, revision unchanged
            tui.error("Save failed, nothing was changed: " + Tui.message(e));
        }
    }
}
