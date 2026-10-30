package com.sparxilium.gitstatus.tui;

import java.util.List;
import java.util.Map;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.ActionListBox;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.sparxilium.gitstatus.db.ConfigStore;
import com.sparxilium.gitstatus.model.Language;
import com.sparxilium.gitstatus.model.Records.DestinationHealth;
import com.sparxilium.gitstatus.model.Records.DiscordDestination;
import com.sparxilium.gitstatus.notify.DiscordGateway;
import com.sparxilium.gitstatus.notify.DiscordGateway.ChannelInfo;
import com.sparxilium.gitstatus.notify.DiscordGateway.GuildInfo;
import com.sparxilium.gitstatus.notify.DiscordMessage;
import com.sparxilium.gitstatus.notify.MessageFormatter;
import com.sparxilium.gitstatus.notify.DiscordSender.SendException;

/** 5.2 Discord destinations. */
final class DestinationsScreen {

    private final WindowBasedTextGUI gui;
    private final ConfigStore store;
    private final DiscordGateway discord;
    private final ActionListBox list = new ActionListBox(new TerminalSize(72, 14));

    DestinationsScreen(WindowBasedTextGUI gui, ConfigStore store, DiscordGateway discord) {
        this.gui = gui;
        this.store = store;
        this.discord = discord;
    }

    void show() {
        BasicWindow w = Ui.window("Discord Destinations");
        Panel panel = new Panel(new LinearLayout(Direction.VERTICAL));
        panel.addComponent(list);
        panel.addComponent(new EmptySpace());
        panel.addComponent(new Label("Enter: Select / actions    Esc: Back"));
        w.setComponent(panel);
        Ui.onKeys(w, (win, key) -> false);
        refresh();
        gui.addWindowAndWait(w);
    }

    private void refresh() {
        int selected = list.getSelectedIndex();
        list.clearItems();
        Map<Long, DestinationHealth> health = store.destinationHealth();
        for (DiscordDestination d : store.destinations()) {
            DestinationHealth h = health.get(d.id());
            String state = !d.enabled() ? "disabled" : h == null ? "enabled" : "FAILED".equals(h.status()) ? "FAILED" : h.status();
            String label = Ui.pad(d.name(), 24) + " " + Ui.pad("ch " + d.channelId(), 26) + " " + Ui.pad(d.language().code(), 6)
                    + "[" + state + "]";
            list.addItem(label, () -> destinationActions(d, h));
        }
        list.addItem("+ Add destination (pick from channels visible to the bot)", this::addFromList);
        list.addItem("+ Add destination (enter Channel ID manually)", this::addManual);
        if (selected >= 0 && selected < list.getItemCount()) {
            list.setSelectedIndex(selected);
        }
    }

    private void addFromList() {
        List<GuildInfo> guilds;
        try {
            guilds = Ui.busy(gui, "Connecting to Discord...", discord::guilds);
        } catch (RuntimeException e) {
            Ui.error(gui, Ui.message(e));
            return;
        }
        if (guilds.isEmpty()) {
            Ui.info(gui, "Discord", "The bot has not joined any guild yet.");
            return;
        }
        GuildLabel guild = Ui.choose(gui, "Select guild", "Discord servers the bot has joined",
                guilds.stream().map(GuildLabel::new).toArray(GuildLabel[]::new));
        if (guild == null) {
            return;
        }
        List<ChannelInfo> channels = discord.sendableChannels(guild.info().id());
        if (channels.isEmpty()) {
            Ui.info(gui, "Discord", "No channel in this guild where the bot can View / Send / Embed Links.");
            return;
        }
        ChannelLabel channel = Ui.choose(gui, "Select channel", guild.info().name(),
                channels.stream().map(ChannelLabel::new).toArray(ChannelLabel[]::new));
        if (channel == null) {
            return;
        }
        save(channel.info().guildId(), channel.info().id(), guild.info().name() + " / #" + channel.info().name());
    }

    private void addManual() {
        String id = Ui.input(gui, "Manual entry", "Discord Channel ID (digits)", "");
        if (id == null) {
            return;
        }
        id = id.trim();
        if (!id.matches("\\d{15,25}")) {
            Ui.error(gui, "Invalid Channel ID.");
            return;
        }
        String channelId = id;
        ChannelInfo info;
        try {
            info = Ui.busy(gui, "Connecting to Discord...", () -> discord.channel(channelId).orElse(null));
        } catch (RuntimeException e) {
            Ui.error(gui, Ui.message(e));
            return;
        }
        if (info == null) {
            if (!Ui.confirm(gui, "Channel not found", "The bot cannot see this channel. Save anyway?")) {
                return;
            }
            save(null, channelId, "channel " + channelId);
            return;
        }
        if (!info.sendable() && !Ui.confirm(gui, "Missing permission",
                "The bot lacks View / Send / Embed Links in this channel. Save anyway?")) {
            return;
        }
        save(info.guildId(), info.id(), info.guildName() + " / #" + info.name());
    }

    private void save(String guildId, String channelId, String suggestedName) {
        String name = Ui.input(gui, "Destination name", "Custom destination_name", suggestedName);
        if (name == null || name.isBlank()) {
            return;
        }
        Language language = chooseLanguage();
        if (language == null) {
            return;
        }
        try {
            store.addDestination(name.trim(), guildId, channelId, language);
        } catch (RuntimeException e) {
            Ui.error(gui, "Save failed (name may already exist): " + Ui.message(e));
            return;
        }
        refresh();
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
        String action = Ui.choose(gui, d.name(), detail, test, language, toggle, reset, delete);
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
                    && Ui.confirm(gui, "Delete", "Delete \"" + d.name() + "\" and all of its notification rules?")) {
                store.deleteDestination(d.id());
            }
        } catch (RuntimeException e) {
            Ui.error(gui, Ui.message(e));
        }
        refresh();
    }

    private Language chooseLanguage() {
        return Ui.choose(gui, "Notification language", "Language of messages sent to this channel", Language.values());
    }

    private void sendTest(DiscordDestination d) {
        DiscordMessage msg = MessageFormatter.testMessage(d.name(), d.language());
        try {
            Ui.busy(gui, "Sending...", () -> {
                try {
                    discord.send(d, msg);
                    return null;
                } catch (SendException e) {
                    throw new IllegalStateException(e.getMessage());
                }
            });
            Ui.info(gui, "Done", "Test message sent.");
        } catch (RuntimeException e) {
            Ui.error(gui, "Send failed: " + Ui.message(e));
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
