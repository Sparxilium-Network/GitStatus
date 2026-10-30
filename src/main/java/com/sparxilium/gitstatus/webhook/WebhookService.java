package com.sparxilium.gitstatus.webhook;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sparxilium.gitstatus.db.ConfigStore;
import com.sparxilium.gitstatus.model.Language;
import com.sparxilium.gitstatus.model.NormalizedEvent;
import com.sparxilium.gitstatus.model.Records.DiscordDestination;
import com.sparxilium.gitstatus.notify.DiscordMessage;
import com.sparxilium.gitstatus.notify.MessageFormatter;
import com.sparxilium.gitstatus.notify.NotificationDispatcher;
import com.sparxilium.gitstatus.provider.WebhookAdapter;
import com.sparxilium.gitstatus.routing.RuleSet;

/**
 * Webhook pipeline: verify → de-duplicate → normalize → route → enqueue.
 * Logs metadata only (delivery id, provider, repo id, event type, result, latency); never bodies.
 */
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    public record Result(int status, String body) {
    }

    private final Function<String, WebhookAdapter> adapters;
    private final ConfigStore store;
    private final Supplier<RuleSet> rules;
    private final NotificationDispatcher dispatcher;
    private final SourceVerifier sources;

    public WebhookService(Function<String, WebhookAdapter> adapters, ConfigStore store, Supplier<RuleSet> rules,
                          NotificationDispatcher dispatcher, SourceVerifier sources) {
        this.adapters = adapters;
        this.store = store;
        this.rules = rules;
        this.dispatcher = dispatcher;
        this.sources = sources;
    }

    /** @param remoteAddr the real client IP (already resolved through the trusted proxy) */
    public Result handle(String provider, Map<String, String> headers, byte[] body, String remoteAddr) {
        long start = System.nanoTime();
        WebhookAdapter adapter = adapters.apply(provider);
        if (adapter == null) {
            return new Result(404, "unknown provider");
        }
        if (!sources.allowed(provider, remoteAddr)) {
            log.warn("Rejected {} webhook from {}: source not in allowed ranges", provider, remoteAddr);
            return new Result(403, "forbidden");
        }
        String deliveryId = adapter.deliveryId(headers);
        String event = adapter.eventName(headers);
        if (deliveryId == null || event == null || deliveryId.length() > 100 || event.length() > 100) {
            log.warn("Rejected {} webhook from {}: missing delivery/event header", provider, remoteAddr);
            return new Result(400, "missing headers");
        }

        boolean valid;
        try {
            valid = adapter.verify(headers, body);
        } catch (RuntimeException e) {
            log.error("Webhook secret unavailable: {}", e.getMessage());
            return new Result(503, "unavailable");
        }
        if (!valid) {
            log.warn("Rejected {} webhook delivery={} event={} from {}: invalid signature", provider, deliveryId, event, remoteAddr);
            return new Result(401, "invalid signature");
        }

        if (!store.tryRecordDelivery(provider, deliveryId, event)) {
            log.info("Duplicate {} delivery={} ignored", provider, deliveryId);
            return new Result(200, "duplicate");
        }

        List<NormalizedEvent> events;
        try {
            events = adapter.normalize(headers, body);
        } catch (RuntimeException e) {
            store.completeDelivery(provider, deliveryId, null, null, "PARSE_ERROR", elapsed(start));
            log.warn("Could not parse {} delivery={} event={}: {}", provider, deliveryId, event, e.getClass().getSimpleName());
            return new Result(400, "bad payload");
        }
        if (events.isEmpty()) {
            store.completeDelivery(provider, deliveryId, null, null, "IGNORED", elapsed(start));
            log.info("{} delivery={} event={} ignored (no notifiable event)", provider, deliveryId, event);
            return new Result(202, "ignored");
        }

        RuleSet ruleSet = rules.get();
        int queued = 0;
        NormalizedEvent last = null;
        for (NormalizedEvent e : events) {
            last = e;
            List<DiscordDestination> destinations = ruleSet.destinations(e.provider(), e.installationId(), e.repositoryId(), e.eventType());
            if (destinations.isEmpty()) {
                continue;
            }
            Map<Language, DiscordMessage> messages = new EnumMap<>(Language.class);
            String context = provider + " delivery=" + deliveryId + " repo=" + e.repositoryId() + " " + e.eventType();
            for (DiscordDestination d : destinations) {
                DiscordMessage message = messages.computeIfAbsent(d.language(), lang -> MessageFormatter.format(e, lang));
                if (dispatcher.enqueue(d, message, context)) {
                    queued++;
                }
            }
        }
        String result = queued > 0 ? "ROUTED:" + queued : "NO_ROUTE";
        long latency = elapsed(start);
        store.completeDelivery(provider, deliveryId, last.repositoryId(), last.eventType().name(), result, latency);
        log.info("{} delivery={} repo={} event={} result={} rev={} latency={}ms", provider, deliveryId, last.repositoryId(),
                last.eventType(), result, ruleSet.revision(), latency);
        return new Result(202, result);
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
