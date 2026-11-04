package com.sparxilium.gitstatus.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.sparxilium.gitstatus.db.ConfigStore;
import com.sparxilium.gitstatus.model.Language;
import com.sparxilium.gitstatus.model.Records.DestinationHealth;
import com.sparxilium.gitstatus.model.Records.DiscordDestination;
import com.sparxilium.gitstatus.notify.DiscordGateway;
import com.sparxilium.gitstatus.notify.DiscordGateway.ChannelInfo;
import com.sparxilium.gitstatus.notify.DiscordGateway.GuildInfo;
import com.sparxilium.gitstatus.notify.DiscordMessage;
import com.sparxilium.gitstatus.notify.DiscordSender.SendException;
import com.sparxilium.gitstatus.notify.MessageFormatter;

/** 5.2 Discord destinations. */
final class DestinationsScreen extends Tui.View {

    private static final String ADD_FROM_LIST = "+ Add destination (pick from channels visible to the bot)";
    private static final String ADD_MANUAL = "+ Add destination (enter Channel ID manually)";

    private final Tui tui;
    private final ConfigStore store;
    private final DiscordGateway discord;
    private List<DiscordDestination> destinations = List.of();
    private Map<Long, DestinationHealth> health = Map.of();

    DestinationsScreen(Tui tui, ConfigStore store, DiscordGateway discord) {
        this.tui = tui;
        this.store = store;
        this.discord = discord;
    }

    void show() {
        tui.show(this);
    }

    @Override
    String title() {
        return "Discord Destinations";
    }

    @Override
    List<String> header() {
        return destinations.isEmpty() ? List.of("No destination yet.")
                : List.of("    " + Tui.pad("Name", 24) + " " + Tui.pad("Channel", 26) + " " + Tui.pad("Lang", 6) + "State");
    }

    @Override
    List<String> rows() {
        destinations = store.destinations();
        health = store.destinationHealth();
        List<String> rows = new ArrayList<>();
        for (DiscordDestination d : destinations) {
            DestinationHealth h = health.get(d.id());
            String state = !d.enabled() ? "disabled" : h == null ? "enabled" : h.status();
            rows.add(Tui.pad(d.name(), 24) + " " + Tui.pad("ch " + d.channelId(), 26) + " "
                    + Tui.pad(d.language().code(), 6) + state);
        }
        rows.add(ADD_FROM_LIST);
        rows.add(ADD_MANUAL);
        return rows;
    }

    @Override
    String keys() {
        return "Up/Down Move    Enter Select / actions    Esc Back";
    }

    @Override
    boolean onKey(KeyStroke key) {
        if (key.getKeyType() != KeyType.Enter) {
            return super.onKey(key);
        }
        if (cursor < destinations.size()) {
            DiscordDestination d = destinations.get(cursor);
            destinationActions(d, health.get(d.id()));
        } else if (cursor == destinations.size()) {
            addFromList();
        } else {
            addManual();
        }
        return true;
    }

    private void addFromList() {
        List<GuildInfo> guilds;
        try {
            guilds = tui.busy("Connecting to Discord...", discord::guilds);
        } catch (RuntimeException e) {
            tui.error(Tui.message(e));
            return;
        }
        if (guilds.isEmpty()) {
            tui.info("Discord", "The bot has not joined any guild yet.");
            return;
        }
        GuildLabel guild = tui.choose("Select guild", "Discord servers the bot has joined",
                guilds.stream().map(GuildLabel::new).toList());
        if (guild == null) {
            return;
        }
        List<ChannelInfo> channels = discord.sendableChannels(guild.info().id());
        if (channels.isEmpty()) {
            tui.info("Discord", "No channel in this guild where the bot can View / Send / Embed Links.");
            return;
        }
        ChannelLabel channel = tui.choose("Select channel", guild.info().name(),
                channels.stream().map(ChannelLabel::new).toList());
        if (channel == null) {
            return;
        }
        save(channel.info().guildId(), channel.info().id(), guild.info().name() + " / #" + channel.info().name());
    }

    private void addManual() {
        String id = tui.input("Manual entry", "Discord Channel ID (digits)", "");
        if (id == null) {
            return;
        }
        id = id.trim();
        if (!id.matches("\\d{15,25}")) {
            tui.error("Invalid Channel ID.");
            return;
        }
        String channelId = id;
        ChannelInfo info;
        try {
            info = tui.busy("Connecting to Discord...", () -> discord.channel(channelId).orElse(null));
        } catch (RuntimeException e) {
            tui.error(Tui.message(e));
            return;
        }
        if (info == null) {
            if (tui.confirm("Channel not found", "The bot cannot see this channel. Save anyway?")) {
                save(null, channelId, "channel " + channelId);
            }
            return;
        }
        if (!info.sendable() && !tui.confirm("Missing permission",
                "The bot lacks View / Send / Embed Links in this channel. Save anyway?")) {
            return;
        }
        save(info.guildId(), info.id(), info.guildName() + " / #" + info.name());
    }

    private void save(String guildId, String channelId, String suggestedName) {
        String name = tui.input("Destination name", "Custom destination_name", suggestedName);
        if (name == null || name.isBlank()) {
            return;
        }
        Language language = chooseLanguage();
        if (language == null) {
            return;
        }
        try {
            store.addDestination(name.trim(), guildId, channelId, language);
            message = "Saved \"" + name.trim() + "\"";
        } catch (RuntimeException e) {
            tui.error("Save failed (name may already exist): " + Tui.message(e));
        }
    }

    private void destinationActions(DiscordDestination d, DestinationHealth h) {
        String detail = "Guild " + (d.guildId() == null ? "?" : d.guildId()) + "  Channel " + d.channelId()
                + (h != null && h.lastError() != null ? "\nLast error: " + h.lastError() : "")
                + (h != null && h.lastSuccessAt() != null ? "\nLast success: " + h.lastSuccessAt() : "");
        String test = "Send test message";
        String language = "Notification language: " + d.language().displayName();
        String toggle = d.enabled() ? "Disable" : "Enable";
        String reset = "Reset failure state";
        String delete = "Delete";
        String action = tui.choose(d.name(), detail, List.of(test, language, toggle, reset, delete));
        if (action == null) {
            return;
        }
        try {
            if (action.equals(test)) {
                sendTest(d);
            } else if (action.equals(language)) {
                Language chosen = chooseLanguage();
                if (chosen != null && chosen != d.language()) {
                    store.setDestinationLanguage(d.id(), chosen);
                }
            } else if (action.equals(toggle)) {
                store.setDestinationEnabled(d.id(), !d.enabled());
            } else if (action.equals(reset)) {
                store.resetDestinationHealth(d.id());
            } else if (action.equals(delete)
                    && tui.confirm("Delete", "Delete \"" + d.name() + "\" and all of its notification rules?")) {
                store.deleteDestination(d.id());
            }
        } catch (RuntimeException e) {
            tui.error(Tui.message(e));
        }
    }

    private Language chooseLanguage() {
        return tui.choose("Notification language", "Language of messages sent to this channel", List.of(Language.values()));
    }

    private void sendTest(DiscordDestination d) {
        DiscordMessage msg = MessageFormatter.testMessage(d.name(), d.language());
        try {
            tui.busy("Sending...", () -> {
                try {
                    discord.send(d, msg);
                    return null;
                } catch (SendException e) {
                    throw new IllegalStateException(e.getMessage());
                }
            });
            message = "Test message sent to " + d.name() + ".";
        } catch (RuntimeException e) {
            tui.error("Send failed: " + Tui.message(e));
        }
    }

    private record GuildLabel(GuildInfo info) {
        @Override
        public String toString() {
            return info.name() + "  (" + info.id() + ")";
        }
    }

    private record ChannelLabel(ChannelInfo info) {
        @Override
        public String toString() {
            return "#" + info.name() + "  (" + info.id() + ")";
        }
    }
}
