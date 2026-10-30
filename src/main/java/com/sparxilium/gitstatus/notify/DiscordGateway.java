package com.sparxilium.gitstatus.notify;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.sparxilium.gitstatus.config.AppSettings;
import com.sparxilium.gitstatus.model.Records.DiscordDestination;

import jakarta.annotation.PreDestroy;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.utils.messages.MessageRequest;

/**
 * Discord Bot connection. Output only: no slash commands, no message listeners, no privileged
 * intents. Used by the daemon for sending and by the admin TUI for guild / channel discovery.
 */
@Component
public class DiscordGateway implements DiscordSender {

    private static final Logger log = LoggerFactory.getLogger(DiscordGateway.class);
    public static final String DEFAULT_PROFILE = "default";
    private static final EnumSet<Permission> REQUIRED = EnumSet.of(
            Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS);

    static {
        // SEC-005: no mention of any kind is ever parsed from our messages.
        MessageRequest.setDefaultMentions(EnumSet.noneOf(Message.MentionType.class));
    }

    private final AppSettings settings;
    private volatile JDA jda;

    public DiscordGateway(AppSettings settings) {
        this.settings = settings;
    }

    public record GuildInfo(String id, String name) {
    }

    public record ChannelInfo(String id, String name, String guildId, String guildName, boolean sendable) {
    }

    /** Connects (once) and waits until the guild cache is ready. */
    public synchronized JDA connect() {
        if (jda != null) {
            return jda;
        }
        String token = settings.readDiscordToken();
        try {
            JDA client = JDABuilder.createLight(token, List.of()).build();
            client.awaitReady();
            jda = client;
            log.info("Discord connected as {}", client.getSelfUser().getName());
            return client;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Discord login interrupted");
        } catch (RuntimeException e) {
            // never include the token in messages
            throw new IllegalStateException("Discord login failed: " + e.getClass().getSimpleName());
        }
    }

    public boolean connected() {
        JDA j = jda;
        return j != null && j.getStatus() == JDA.Status.CONNECTED;
    }

    public String statusText() {
        JDA j = jda;
        if (j == null) {
            return "未連線";
        }
        return j.getStatus() + " as " + j.getSelfUser().getName() + " (" + j.getGuilds().size() + " guilds)";
    }

    public List<GuildInfo> guilds() {
        return connect().getGuilds().stream()
                .map(g -> new GuildInfo(g.getId(), g.getName()))
                .sorted(Comparator.comparing(GuildInfo::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** Text / announcement channels in the guild that the bot can view, post to and embed in. */
    public List<ChannelInfo> sendableChannels(String guildId) {
        Guild guild = connect().getGuildById(guildId);
        if (guild == null) {
            return List.of();
        }
        List<ChannelInfo> out = new ArrayList<>();
        Member self = guild.getSelfMember();
        List<GuildMessageChannel> channels = new ArrayList<>();
        channels.addAll(guild.getTextChannels());
        channels.addAll(guild.getNewsChannels());
        for (GuildMessageChannel ch : channels) {
            if (self.hasPermission(ch, REQUIRED)) {
                out.add(new ChannelInfo(ch.getId(), ch.getName(), guild.getId(), guild.getName(), true));
            }
        }
        out.sort(Comparator.comparing(ChannelInfo::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** Looks up a channel by id among the bot's visible channels. */
    public Optional<ChannelInfo> channel(String channelId) {
        GuildMessageChannel ch;
        try {
            ch = connect().getChannelById(GuildMessageChannel.class, channelId);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (ch == null) {
            return Optional.empty();
        }
        Guild guild = ch.getGuild();
        return Optional.of(new ChannelInfo(ch.getId(), ch.getName(), guild.getId(), guild.getName(),
                guild.getSelfMember().hasPermission(ch, REQUIRED)));
    }

    @Override
    public void send(DiscordDestination destination, DiscordMessage message) throws SendException {
        if (!DEFAULT_PROFILE.equals(destination.botProfile())) {
            throw new SendException("bot profile '" + destination.botProfile() + "' is not configured", true);
        }
        JDA client;
        try {
            client = connect();
        } catch (IllegalStateException e) {
            throw new SendException(e.getMessage(), false);
        }
        GuildMessageChannel ch = client.getChannelById(GuildMessageChannel.class, destination.channelId());
        if (ch == null) {
            throw new SendException("channel not found or not visible to the bot", true);
        }
        try {
            ch.sendMessageEmbeds(toEmbed(message).build())
                    .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class))
                    .timeout(30, TimeUnit.SECONDS)
                    .complete();
        } catch (InsufficientPermissionException e) {
            throw new SendException("missing permission: " + e.getPermission().getName(), true);
        } catch (ErrorResponseException e) {
            ErrorResponse r = e.getErrorResponse();
            boolean permanent = r == ErrorResponse.UNKNOWN_CHANNEL || r == ErrorResponse.MISSING_ACCESS
                    || r == ErrorResponse.MISSING_PERMISSIONS || r == ErrorResponse.UNKNOWN_GUILD;
            throw new SendException("Discord error " + e.getErrorCode() + " " + r.name(), permanent);
        } catch (RuntimeException e) {
            throw new SendException("send failed: " + e.getClass().getSimpleName(), false);
        }
    }

    static EmbedBuilder toEmbed(DiscordMessage m) {
        EmbedBuilder eb = new EmbedBuilder();
        eb.setTitle(m.title(), m.url());
        if (m.description() != null && !m.description().isBlank()) {
            eb.setDescription(m.description());
        }
        eb.setColor(m.color());
        for (DiscordMessage.Field f : m.fields()) {
            eb.addField(f.name(), f.value(), f.inline());
        }
        if (m.footer() != null) {
            eb.setFooter(m.footer());
        }
        if (m.timestamp() != null) {
            eb.setTimestamp(m.timestamp());
        }
        return eb;
    }

    @PreDestroy
    public synchronized void shutdown() {
        if (jda != null) {
            jda.shutdown();
            try {
                jda.awaitShutdown(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            jda = null;
        }
    }
}
