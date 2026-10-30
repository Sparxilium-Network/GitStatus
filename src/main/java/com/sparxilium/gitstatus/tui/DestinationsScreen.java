package com.sparxilium.gitstatus.tui;

import java.time.Instant;
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
import com.sparxilium.gitstatus.model.Records.DestinationHealth;
import com.sparxilium.gitstatus.model.Records.DiscordDestination;
import com.sparxilium.gitstatus.notify.DiscordGateway;
import com.sparxilium.gitstatus.notify.DiscordGateway.ChannelInfo;
import com.sparxilium.gitstatus.notify.DiscordGateway.GuildInfo;
import com.sparxilium.gitstatus.notify.DiscordMessage;
import com.sparxilium.gitstatus.notify.DiscordSender.SendException;

/** 5.2 Discord 目的地設定. */
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
        BasicWindow w = Ui.window("Discord 目的地");
        Panel panel = new Panel(new LinearLayout(Direction.VERTICAL));
        panel.addComponent(list);
        panel.addComponent(new EmptySpace());
        panel.addComponent(new Label("Enter: 選擇/操作    Esc: 返回"));
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
            String state = !d.enabled() ? "停用" : h == null ? "啟用" : "FAILED".equals(h.status()) ? "異常" : h.status();
            String label = Ui.pad(d.name(), 24) + " " + Ui.pad("ch " + d.channelId(), 26) + " [" + state + "]";
            list.addItem(label, () -> destinationActions(d, h));
        }
        list.addItem("+ 新增目的地（從 Bot 可見的頻道選擇）", this::addFromList);
        list.addItem("+ 新增目的地（手動輸入 Channel ID）", this::addManual);
        if (selected >= 0 && selected < list.getItemCount()) {
            list.setSelectedIndex(selected);
        }
    }

    private void addFromList() {
        List<GuildInfo> guilds;
        try {
            guilds = Ui.busy(gui, "連線 Discord…", discord::guilds);
        } catch (RuntimeException e) {
            Ui.error(gui, Ui.message(e));
            return;
        }
        if (guilds.isEmpty()) {
            Ui.info(gui, "Discord", "Bot 尚未加入任何 Guild。");
            return;
        }
        GuildLabel guild = Ui.choose(gui, "選擇 Guild", "Bot 已加入的 Discord Server",
                guilds.stream().map(GuildLabel::new).toArray(GuildLabel[]::new));
        if (guild == null) {
            return;
        }
        List<ChannelInfo> channels = discord.sendableChannels(guild.info().id());
        if (channels.isEmpty()) {
            Ui.info(gui, "Discord", "此 Guild 中沒有 Bot 可發送訊息（View / Send / Embed Links）的頻道。");
            return;
        }
        ChannelLabel channel = Ui.choose(gui, "選擇頻道", guild.info().name(),
                channels.stream().map(ChannelLabel::new).toArray(ChannelLabel[]::new));
        if (channel == null) {
            return;
        }
        save(channel.info().guildId(), channel.info().id(), guild.info().name() + " / #" + channel.info().name());
    }

    private void addManual() {
        String id = Ui.input(gui, "手動輸入", "Discord Channel ID（數字）", "");
        if (id == null) {
            return;
        }
        id = id.trim();
        if (!id.matches("\\d{15,25}")) {
            Ui.error(gui, "Channel ID 格式不正確。");
            return;
        }
        String channelId = id;
        ChannelInfo info;
        try {
            info = Ui.busy(gui, "連線 Discord…", () -> discord.channel(channelId).orElse(null));
        } catch (RuntimeException e) {
            Ui.error(gui, Ui.message(e));
            return;
        }
        if (info == null) {
            if (!Ui.confirm(gui, "找不到頻道", "Bot 目前看不到此頻道，仍要保存嗎？")) {
                return;
            }
            save(null, channelId, "channel " + channelId);
            return;
        }
        if (!info.sendable() && !Ui.confirm(gui, "權限不足", "Bot 在此頻道缺少 View / Send / Embed Links 權限，仍要保存嗎？")) {
            return;
        }
        save(info.guildId(), info.id(), info.guildName() + " / #" + info.name());
    }

    private void save(String guildId, String channelId, String suggestedName) {
        String name = Ui.input(gui, "目的地名稱", "自訂 destination_name", suggestedName);
        if (name == null || name.isBlank()) {
            return;
        }
        try {
            store.addDestination(name.trim(), guildId, channelId);
        } catch (RuntimeException e) {
            Ui.error(gui, "保存失敗（名稱可能重複）：" + Ui.message(e));
            return;
        }
        refresh();
    }

    private void destinationActions(DiscordDestination d, DestinationHealth h) {
        String detail = "Guild " + (d.guildId() == null ? "?" : d.guildId()) + "  Channel " + d.channelId()
                + (h != null && h.lastError() != null ? "\n最近錯誤: " + h.lastError() : "")
                + (h != null && h.lastSuccessAt() != null ? "\n最近成功: " + h.lastSuccessAt() : "");
        String test = "發送測試訊息";
        String toggle = d.enabled() ? "停用" : "啟用";
        String reset = "重設異常狀態";
        String delete = "刪除";
        String action = Ui.choose(gui, d.name(), detail, test, toggle, reset, delete);
        if (action == null) {
            return;
        }
        try {
            if (action.equals(test)) {
                sendTest(d);
            } else if (action.equals(toggle)) {
                store.setDestinationEnabled(d.id(), !d.enabled());
            } else if (action.equals(reset)) {
                store.resetDestinationHealth(d.id());
            } else if (action.equals(delete)
                    && Ui.confirm(gui, "刪除", "刪除「" + d.name() + "」及其所有通知規則？")) {
                store.deleteDestination(d.id());
            }
        } catch (RuntimeException e) {
            Ui.error(gui, Ui.message(e));
        }
        refresh();
    }

    private void sendTest(DiscordDestination d) {
        DiscordMessage msg = new DiscordMessage("Git Notification Manager 測試訊息", null,
                "此頻道已設定為通知目的地「" + d.name().replace("@", "@​") + "」。", 0x0969DA, List.of(), "gitstatus",
                Instant.now());
        try {
            Ui.busy(gui, "發送中…", () -> {
                try {
                    discord.send(d, msg);
                    return null;
                } catch (SendException e) {
                    throw new IllegalStateException(e.getMessage());
                }
            });
            Ui.info(gui, "完成", "測試訊息已發送。");
        } catch (RuntimeException e) {
            Ui.error(gui, "發送失敗：" + Ui.message(e));
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
