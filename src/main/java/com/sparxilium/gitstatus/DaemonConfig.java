package com.sparxilium.gitstatus;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

import com.sparxilium.gitstatus.db.ConfigStore;
import com.sparxilium.gitstatus.notify.DiscordGateway;
import com.sparxilium.gitstatus.notify.NotificationDispatcher;
import com.sparxilium.gitstatus.provider.WebhookAdapter;
import com.sparxilium.gitstatus.routing.RuleSetHolder;
import com.sparxilium.gitstatus.webhook.WebhookService;

/** Wiring that only exists in daemon ({@code run}) mode. */
@Configuration
@Profile(GitStatusApplication.PROFILE_DAEMON)
public class DaemonConfig {

    private static final Logger log = LoggerFactory.getLogger(DaemonConfig.class);

    private final ConfigStore store;
    private final DiscordGateway discord;
    private final int retentionDays;

    public DaemonConfig(ConfigStore store, DiscordGateway discord, @Value("${deliveryLog.retentionDays}") int retentionDays) {
        this.store = store;
        this.discord = discord;
        this.retentionDays = retentionDays;
    }

    @Bean(destroyMethod = "shutdown")
    public NotificationDispatcher notificationDispatcher(RuleSetHolder rules) {
        NotificationDispatcher dispatcher = new NotificationDispatcher(discord, store, 1000, 4, 2000);
        dispatcher.loadFailedDestinations();
        // A TUI "reset" clears destination_health and bumps the revision.
        rules.onReload(revision -> dispatcher.loadFailedDestinations());
        return dispatcher;
    }

    @Bean
    public WebhookService webhookService(List<WebhookAdapter> adapters, RuleSetHolder rules, NotificationDispatcher dispatcher) {
        Map<String, WebhookAdapter> byProvider = adapters.stream()
                .collect(Collectors.toMap(WebhookAdapter::provider, Function.identity()));
        return new WebhookService(byProvider::get, store, rules::current, dispatcher);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void connectDiscord() {
        Thread.ofVirtual().name("discord-connect").start(() -> {
            try {
                discord.connect();
                store.putStatus("daemon.discord", discord.statusText());
            } catch (RuntimeException e) {
                store.putStatus("daemon.discord", "ERROR " + e.getMessage());
                log.error("{}", e.getMessage());
            }
        });
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void reportDiscordStatus() {
        store.putStatus("daemon.discord", discord.connected() ? discord.statusText() : "DISCONNECTED");
    }

    @Scheduled(cron = "0 17 3 * * *")
    public void purgeDeliveryLog() {
        int removed = store.purgeDeliveryLog(retentionDays);
        if (removed > 0) {
            log.info("Purged {} delivery log entries older than {} days", removed, retentionDays);
        }
    }
}
