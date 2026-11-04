package com.sparxilium.gitstatus.provider.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import com.sparxilium.gitstatus.model.EventType;
import com.sparxilium.gitstatus.model.NormalizedEvent;

class GitHubWebhookAdapterTest {

    static final String SECRET = "s3cret";
    private final GitHubWebhookAdapter adapter = new GitHubWebhookAdapter(() -> List.of("1"), id -> SECRET);

    static String sign(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    static Map<String, String> headers(String event, String delivery, String signature) {
        Map<String, String> h = new HashMap<>();
        h.put("x-github-event", event);
        h.put("x-github-delivery", delivery);
        h.put("content-type", "application/json");
        if (signature != null) {
            h.put("x-hub-signature-256", signature);
        }
        return h;
    }

    static final String PUSH = """
            {"ref":"refs/heads/main","deleted":false,"compare":"https://github.com/acme/api/compare/a...b",
             "commits":[{"id":"a"},{"id":"b"}],
             "head_commit":{"message":"Fix bug @everyone\\n\\nlong body with secrets","timestamp":"2026-10-30T10:00:00+08:00"},
             "pusher":{"name":"alice"},"sender":{"login":"alice"},
             "repository":{"id":42,"full_name":"acme/api"},"installation":{"id":7}}
            """;

    @Test
    void verifiesSignature() throws Exception {
        byte[] body = PUSH.getBytes(StandardCharsets.UTF_8);
        assertEquals("1", adapter.authenticate(headers("push", "d1", sign(SECRET, body)), body));
        assertNull(adapter.authenticate(headers("push", "d1", sign("wrong", body)), body));
        assertNull(adapter.authenticate(headers("push", "d1", null), body));
        assertNull(adapter.authenticate(headers("push", "d1", "sha256=zz"), body));
        byte[] tampered = PUSH.replace("alice", "mallory").getBytes(StandardCharsets.UTF_8);
        assertNull(adapter.authenticate(headers("push", "d1", sign(SECRET, body)), tampered));
    }

    private static final Map<String, String> APP_SECRETS = Map.of("111", "secret-a", "222", "secret-b");
    private final GitHubWebhookAdapter twoApps = new GitHubWebhookAdapter(
            () -> List.of("111", "222"), id -> {
                if (!APP_SECRETS.containsKey(id)) {
                    throw new IllegalStateException("unknown App " + id);
                }
                return APP_SECRETS.get(id);
            });

    private static Map<String, String> fromApp(String appId, String signature) {
        Map<String, String> h = headers("push", "d1", signature);
        h.put("x-github-hook-installation-target-type", "integration");
        h.put("x-github-hook-installation-target-id", appId);
        return h;
    }

    @Test
    void identifiesTheAppThatSignedTheDelivery() throws Exception {
        byte[] body = PUSH.getBytes(StandardCharsets.UTF_8);
        assertEquals("111", twoApps.authenticate(fromApp("111", sign("secret-a", body)), body));
        assertEquals("222", twoApps.authenticate(fromApp("222", sign("secret-b", body)), body));
        // the target header only picks the secret: claiming App 222 with App 111's signature fails
        assertNull(twoApps.authenticate(fromApp("222", sign("secret-a", body)), body));
        // an App that is not configured here
        assertNull(twoApps.authenticate(fromApp("999", sign("secret-a", body)), body));
        // without the header every App's secret is tried
        assertEquals("222", twoApps.authenticate(headers("push", "d1", sign("secret-b", body)), body));
        assertNull(twoApps.authenticate(headers("push", "d1", sign("other", body)), body));
    }

    @Test
    void appsSharingASecretAreAmbiguousWithoutTheTargetHeader() throws Exception {
        GitHubWebhookAdapter shared = new GitHubWebhookAdapter(() -> List.of("111", "222"), id -> "same");
        byte[] body = PUSH.getBytes(StandardCharsets.UTF_8);
        assertNull(shared.authenticate(headers("push", "d1", sign("same", body)), body));
        assertEquals("222", shared.authenticate(fromApp("222", sign("same", body)), body));
    }

    @Test
    void normalizesPushWithoutCommitBody() {
        List<NormalizedEvent> events = adapter.normalize(headers("push", "d1", null), PUSH.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, events.size());
        NormalizedEvent e = events.get(0);
        assertEquals(EventType.PUSH, e.eventType());
        assertEquals("7", e.installationId());
        assertEquals("42", e.repositoryId());
        assertEquals("main", e.ref());
        assertEquals("Fix bug @everyone", e.title());
        assertEquals("2", e.attr("commitCount"));
        assertEquals("alice", e.actor());
    }

    @Test
    void ignoresBranchDeletion() {
        String body = PUSH.replace("\"deleted\":false", "\"deleted\":true");
        assertTrue(adapter.normalize(headers("push", "d1", null), body.getBytes(StandardCharsets.UTF_8)).isEmpty());
    }

    @Test
    void mapsWorkflowConclusions() {
        String template = """
                {"action":"completed","workflow_run":{"name":"CI","run_number":12,"head_branch":"main",
                 "conclusion":"%s","html_url":"https://github.com/acme/api/actions/runs/1",
                 "triggering_actor":{"login":"bob"},"updated_at":"2026-10-30T02:00:00Z"},
                 "repository":{"id":42,"full_name":"acme/api"},"installation":{"id":7},"sender":{"login":"bob"}}
                """;
        Map<String, EventType> expected = Map.of("success", EventType.ACTION_SUCCESS, "failure", EventType.ACTION_FAILURE,
                "timed_out", EventType.ACTION_FAILURE, "cancelled", EventType.ACTION_CANCELLED);
        expected.forEach((conclusion, type) -> {
            byte[] body = template.formatted(conclusion).getBytes(StandardCharsets.UTF_8);
            List<NormalizedEvent> events = adapter.normalize(headers("workflow_run", "d", null), body);
            assertEquals(type, events.get(0).eventType(), conclusion);
            assertEquals("CI", events.get(0).attr("workflow"));
        });
        byte[] skipped = template.formatted("skipped").getBytes(StandardCharsets.UTF_8);
        assertTrue(adapter.normalize(headers("workflow_run", "d", null), skipped).isEmpty());
    }

    @Test
    void mapsPullRequestMergedAndClosed() {
        String template = """
                {"action":"closed","pull_request":{"number":5,"title":"Add feature","merged":%s,
                 "html_url":"https://github.com/acme/api/pull/5","base":{"ref":"main"},"head":{"ref":"feat"},
                 "closed_at":"2026-10-30T02:00:00Z"},
                 "repository":{"id":42,"full_name":"acme/api"},"installation":{"id":7},"sender":{"login":"carol"}}
                """;
        assertEquals(EventType.PR_MERGED, adapter.normalize(headers("pull_request", "d", null),
                template.formatted("true").getBytes(StandardCharsets.UTF_8)).get(0).eventType());
        assertEquals(EventType.PR_CLOSED, adapter.normalize(headers("pull_request", "d", null),
                template.formatted("false").getBytes(StandardCharsets.UTF_8)).get(0).eventType());
    }

    @Test
    void acceptsFormEncodedPayload() {
        String form = "payload=" + java.net.URLEncoder.encode(PUSH, StandardCharsets.UTF_8);
        Map<String, String> h = headers("push", "d1", null);
        h.put("content-type", "application/x-www-form-urlencoded");
        assertEquals(EventType.PUSH, adapter.normalize(h, form.getBytes(StandardCharsets.US_ASCII)).get(0).eventType());
    }
}
