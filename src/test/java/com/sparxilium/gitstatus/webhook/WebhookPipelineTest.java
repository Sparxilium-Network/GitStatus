package com.sparxilium.gitstatus.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import com.sparxilium.gitstatus.config.AppSettings;
import com.sparxilium.gitstatus.db.ConfigStore;
import com.sparxilium.gitstatus.db.ConfigStore.DiscoveredInstallation;
import com.sparxilium.gitstatus.db.ConfigStore.DiscoveredRepository;
import com.sparxilium.gitstatus.db.SchemaInitializer;
import com.sparxilium.gitstatus.model.EventType;
import com.sparxilium.gitstatus.model.Language;
import com.sparxilium.gitstatus.model.Records.DiscordDestination;
import com.sparxilium.gitstatus.model.Records.RepositoryRecord;
import com.sparxilium.gitstatus.notify.DiscordMessage;
import com.sparxilium.gitstatus.notify.DiscordSender;
import com.sparxilium.gitstatus.notify.NotificationDispatcher;
import com.sparxilium.gitstatus.provider.github.GitHubHookSources;
import com.sparxilium.gitstatus.provider.github.GitHubWebhookAdapter;
import com.sparxilium.gitstatus.routing.RuleSetHolder;
import com.sparxilium.gitstatus.routing.RuleSetLoader;

/** End-to-end: webhook → verify → dedupe → normalize → route → send, against a real SQLite file. */
class WebhookPipelineTest {

    private static final String SECRET = "s3cret";

    @TempDir
    Path tmp;

    private ConfigStore store;
    private RuleSetHolder holder;
    private WebhookService service;
    private NotificationDispatcher dispatcher;
    private SourceVerifier sources = SourceVerifier.ALLOW_ALL;
    private String clientIp = "127.0.0.1";
    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private DiscordDestination build;
    private DiscordDestination alerts;
    private RepositoryRecord repo;

    record Sent(String destination, DiscordMessage message) {
    }

    @BeforeEach
    void setUp() throws Exception {
        String db = tmp.resolve("bot.db").toString();
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:sqlite:" + db + "?journal_mode=WAL&busy_timeout=5000&foreign_keys=true&transaction_mode=IMMEDIATE");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        AppSettings settings = new AppSettings(db, "https://api.github.com", "", "", "", "", 5000, 1_000_000, 8080);
        new SchemaInitializer(jdbc, settings).afterPropertiesSet();
        store = new ConfigStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)));
        holder = new RuleSetHolder(new RuleSetLoader(jdbc, store), store);
        holder.afterPropertiesSet();

        DiscordSender sender = (d, m) -> sent.add(new Sent(d.name(), m));
        dispatcher = new NotificationDispatcher(sender, store, 100, 2, 10);
        GitHubWebhookAdapter adapter = new GitHubWebhookAdapterFactory().create(SECRET);
        service = new WebhookService(p -> "github".equals(p) ? adapter : null, store, holder::current, dispatcher,
                (p, ip) -> sources.allowed(p, ip));

        // configuration as the TUI would write it
        store.syncInstallations("github", List.of(new DiscoveredInstallation("7", "acme", "Organization",
                "{\"contents\":\"read\",\"actions\":\"read\"}", "[\"push\",\"workflow_run\"]")));
        long instId = store.installations("github").get(0).id();
        store.setInstallationSelected(instId, true);
        store.syncRepositories(instId, List.of(new DiscoveredRepository("42", "acme/api", "PRIVATE", false)));
        repo = store.repositories(instId).get(0);
        store.setSubscribed(repo.id(), true);
        store.addDestination("A / #build", "1", "111111111111111111", Language.EN);
        store.addDestination("B / #alerts", "2", "222222222222222222", Language.ZH_TW);
        build = store.destinations().stream().filter(d -> d.name().contains("build")).findFirst().orElseThrow();
        alerts = store.destinations().stream().filter(d -> d.name().contains("alerts")).findFirst().orElseThrow();
        store.saveRules(repo.id(), Map.of(
                build.id(), EnumSet.of(EventType.PUSH, EventType.ACTION_FAILURE),
                alerts.id(), EnumSet.of(EventType.ACTION_FAILURE)));
        holder.reloadIfChanged();
    }

    /** Let in-flight sends finish so the SQLite files can be deleted (Windows keeps them locked). */
    @AfterEach
    void tearDown() {
        dispatcher.shutdown();
    }

    private WebhookService.Result deliver(String event, String delivery, String body, String secret) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Map<String, String> h = new java.util.HashMap<>(Map.of("x-github-event", event, "x-github-delivery", delivery,
                "content-type", "application/json", "x-hub-signature-256", sign(secret, bytes)));
        return service.handle("github", h, bytes, clientIp);
    }

    @Test
    void rejectsSourceOutsideGitHubHookRanges() throws Exception {
        sources = new GitHubHookSources(() -> List.of("192.30.252.0/22", "2a0a:a440::/29"), store, true);
        ((GitHubHookSources) sources).afterPropertiesSet();

        clientIp = "203.0.113.7";
        assertEquals(403, deliver("push", "s-1", PUSH, SECRET).status());
        awaitSent(0);
        assertTrue(sent.isEmpty());

        // a rejected source is not recorded, so the real delivery with the same id still works
        clientIp = "192.30.252.10";
        assertEquals(202, deliver("push", "s-1", PUSH, SECRET).status());
        awaitSent(1);
        assertEquals(1, sent.size());
    }

    @Test
    void sourceVerificationFailsClosedAndKeepsSavedRanges() throws Exception {
        clientIp = "192.30.252.10";
        GitHubHookSources unreachable = new GitHubHookSources(() -> {
            throw new IllegalStateException("GitHub down");
        }, store, true);
        unreachable.afterPropertiesSet();
        sources = unreachable;
        assertEquals(403, deliver("push", "s-2", PUSH, SECRET).status()); // no list ever loaded

        new GitHubHookSources(() -> List.of("192.30.252.0/22"), store, true).afterPropertiesSet(); // saves the list
        GitHubHookSources restarted = new GitHubHookSources(() -> {
            throw new IllegalStateException("GitHub down");
        }, store, true);
        restarted.afterPropertiesSet();
        sources = restarted;
        assertEquals(202, deliver("push", "s-3", PUSH, SECRET).status());
        awaitSent(1);
    }

    private static String sign(String secret, byte[] body) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + java.util.HexFormat.of().formatHex(mac.doFinal(body));
    }

    private static final String PUSH = """
            {"ref":"refs/heads/main","compare":"https://github.com/acme/api/compare/a...b","commits":[{}],
             "head_commit":{"message":"hello @everyone"},"pusher":{"name":"alice"},
             "repository":{"id":42,"full_name":"acme/api"},"installation":{"id":7}}
            """;

    private static final String FAILURE = """
            {"action":"completed","workflow_run":{"name":"CI","conclusion":"failure","head_branch":"main",
             "html_url":"https://github.com/acme/api/actions/runs/1"},
             "repository":{"id":42,"full_name":"acme/api"},"installation":{"id":7},"sender":{"login":"bob"}}
            """;

    private void awaitSent(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (sent.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        Thread.sleep(100); // make sure nothing extra arrives
    }

    @Test
    void routesEventToEachConfiguredDestination() throws Exception {
        assertEquals(202, deliver("workflow_run", "d-1", FAILURE, SECRET).status());
        awaitSent(2);
        assertEquals(2, sent.size());
        assertEquals(List.of("A / #build", "B / #alerts"), sent.stream().map(Sent::destination).sorted().toList());
    }

    @Test
    void eachDestinationGetsItsOwnLanguage() throws Exception {
        deliver("workflow_run", "d-9", FAILURE, SECRET);
        awaitSent(2);
        Map<String, String> titles = new java.util.HashMap<>();
        sent.forEach(s -> titles.put(s.destination(), s.message().title()));
        assertEquals("[acme/api] CI failed", titles.get("A / #build"));
        assertEquals("[acme/api] CI 失敗", titles.get("B / #alerts"));
    }

    @Test
    void pushGoesOnlyToBuildAndMentionsAreNeutralized() throws Exception {
        deliver("push", "d-2", PUSH, SECRET);
        awaitSent(1);
        assertEquals(1, sent.size());
        assertEquals("A / #build", sent.get(0).destination());
        assertFalse(sent.get(0).message().description().contains("@everyone"));
    }

    @Test
    void rejectsBadSignature() throws Exception {
        assertEquals(401, deliver("push", "d-3", PUSH, "wrong").status());
        awaitSent(0);
        assertTrue(sent.isEmpty());
        // not recorded, so a correctly signed retry with the same id is still processed
        assertEquals(202, deliver("push", "d-3", PUSH, SECRET).status());
        awaitSent(1);
    }

    @Test
    void ignoresDuplicateDelivery() throws Exception {
        deliver("push", "d-4", PUSH, SECRET);
        assertEquals("duplicate", deliver("push", "d-4", PUSH, SECRET).body());
        awaitSent(1);
        assertEquals(1, sent.size());
    }

    @Test
    void appliesNewRulesWithoutRestart() throws Exception {
        store.saveRules(repo.id(), Map.of(alerts.id(), EnumSet.of(EventType.PUSH)));
        holder.reloadIfChanged();
        deliver("push", "d-5", PUSH, SECRET);
        awaitSent(1);
        assertEquals(List.of("B / #alerts"), sent.stream().map(Sent::destination).toList());
    }

    @Test
    void disabledDestinationAndUnsubscribedRepoReceiveNothing() throws Exception {
        store.setDestinationEnabled(build.id(), false);
        holder.reloadIfChanged();
        deliver("push", "d-6", PUSH, SECRET);
        awaitSent(0);
        assertTrue(sent.isEmpty());

        store.setDestinationEnabled(build.id(), true);
        store.setSubscribed(repo.id(), false);
        holder.reloadIfChanged();
        assertEquals("NO_ROUTE", deliver("push", "d-7", PUSH, SECRET).body());
    }

    @Test
    void repositoryRemovedFromInstallationStopsRouting() throws Exception {
        long instId = store.installations("github").get(0).id();
        store.syncRepositories(instId, List.of());
        holder.reloadIfChanged();
        assertEquals("NO_ROUTE", deliver("push", "d-8", PUSH, SECRET).body());
    }

    /** Uses the package-private test constructor. */
    static final class GitHubWebhookAdapterFactory {
        GitHubWebhookAdapter create(String secret) throws Exception {
            var ctor = GitHubWebhookAdapter.class.getDeclaredConstructor(java.util.function.Supplier.class);
            ctor.setAccessible(true);
            java.util.function.Supplier<String> s = () -> secret;
            return ctor.newInstance(s);
        }
    }
}
