package com.sparxilium.gitstatus.notify;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sparxilium.gitstatus.db.ConfigStore;
import com.sparxilium.gitstatus.model.Records.DiscordDestination;
import com.sparxilium.gitstatus.notify.DiscordSender.SendException;

/**
 * In-process bounded send queue with limited retries and exponential backoff (FR-011).
 * A permanent failure (unknown channel, missing permission) marks the destination as
 * failed; it is skipped until an administrator resets it in the TUI.
 */
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final DiscordSender sender;
    private final ConfigStore store;
    private final ScheduledExecutorService executor;
    private final Semaphore capacity;
    private final int maxAttempts;
    private final long baseBackoffMs;
    private final Set<Long> failedDestinations = ConcurrentHashMap.newKeySet();

    public NotificationDispatcher(DiscordSender sender, ConfigStore store, int queueCapacity, int maxAttempts, long baseBackoffMs) {
        this.sender = sender;
        this.store = store;
        this.capacity = new Semaphore(queueCapacity);
        this.maxAttempts = maxAttempts;
        this.baseBackoffMs = baseBackoffMs;
        ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(2, r -> {
            Thread t = new Thread(r, "discord-sender");
            t.setDaemon(true);
            return t;
        });
        ex.setRemoveOnCancelPolicy(true);
        this.executor = ex;
    }

    /** Loads persisted failure state so a restart does not hammer a broken channel. */
    public void loadFailedDestinations() {
        failedDestinations.clear();
        store.destinationHealth().values().stream()
                .filter(h -> "FAILED".equals(h.status()))
                .forEach(h -> failedDestinations.add(h.destinationId()));
    }

    public boolean isFailed(long destinationId) {
        return failedDestinations.contains(destinationId);
    }

    /** @return false if the queue is full and the message was dropped */
    public boolean enqueue(DiscordDestination destination, DiscordMessage message, String context) {
        if (failedDestinations.contains(destination.id())) {
            log.info("Skipping destination {} (marked failed) for {}", destination.name(), context);
            return false;
        }
        if (!capacity.tryAcquire()) {
            log.warn("Send queue full, dropping message to {} for {}", destination.name(), context);
            return false;
        }
        executor.execute(() -> attempt(destination, message, context, 1));
        return true;
    }

    private void attempt(DiscordDestination destination, DiscordMessage message, String context, int attempt) {
        long start = System.nanoTime();
        try {
            sender.send(destination, message);
            capacity.release();
            store.recordSendSuccess(destination.id());
            log.info("Sent {} to {} in {} ms", context, destination.name(), (System.nanoTime() - start) / 1_000_000);
        } catch (SendException e) {
            if (e.permanent()) {
                capacity.release();
                failedDestinations.add(destination.id());
                safeRecordFailure(destination.id(), "FAILED", e.getMessage());
                log.error("Destination {} marked failed ({}); not retrying {}", destination.name(), e.getMessage(), context);
            } else if (attempt < maxAttempts) {
                long delay = baseBackoffMs * (1L << (attempt - 1));
                log.warn("Send to {} failed ({}), retry {}/{} in {} ms", destination.name(), e.getMessage(), attempt,
                        maxAttempts - 1, delay);
                executor.schedule(() -> attempt(destination, message, context, attempt + 1), delay, TimeUnit.MILLISECONDS);
            } else {
                capacity.release();
                safeRecordFailure(destination.id(), "RETRY_EXHAUSTED", e.getMessage());
                log.error("Giving up sending {} to {} after {} attempts: {}", context, destination.name(), attempt, e.getMessage());
            }
        } catch (RuntimeException e) {
            capacity.release();
            log.error("Unexpected error sending {} to {}: {}", context, destination.name(), e.getClass().getSimpleName());
        }
    }

    private void safeRecordFailure(long destinationId, String status, String error) {
        try {
            store.recordSendFailure(destinationId, status, error);
        } catch (RuntimeException e) {
            log.warn("Could not record destination health: {}", e.getMessage());
        }
    }

    public void shutdown() {
        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
