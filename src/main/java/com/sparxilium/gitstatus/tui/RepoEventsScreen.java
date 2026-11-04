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
 * 5.5 Repository event settings: Repository × Event × Destination. Left/Right switches the destination
 * being edited; S saves all destinations in one transaction.
 */
final class RepoEventsScreen extends Tui.View {

    private static final EventType[] EVENTS = EventType.values();

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
        String lastCategory = null;
        for (EventType type : EVENTS) {
            String category = type.category().equals(lastCategory) ? "" : type.category();
            lastCategory = type.category();
            Availability a = capabilities.getOrDefault(type, Availability.UNSUPPORTED);
            rows.add(Tui.checkbox(enabled.contains(type)) + Tui.pad(category, 16) + type.label()
                    + (a == Availability.AVAILABLE ? "" : "   (" + a.reason() + ")"));
        }
        return rows;
    }

    @Override
    String keys() {
        return "Up/Down Move    Space On/off    Left/Right Switch destination\nS Save    Esc Back";
    }

    @Override
    boolean onKey(KeyStroke key) {
        if (Tui.isSpace(key)) {
            toggle(EVENTS[cursor]);
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
        Availability a = capabilities.getOrDefault(type, Availability.UNSUPPORTED);
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
