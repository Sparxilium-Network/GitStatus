package com.sparxilium.gitstatus.provider.github;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.sparxilium.gitstatus.model.EventType;
import com.sparxilium.gitstatus.model.NormalizedEvent;
import com.sparxilium.gitstatus.provider.WebhookAdapter;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * GitHub webhook: verifies {@code X-Hub-Signature-256} against the sending App's secret and maps push, pull_request,
 * workflow_run and release deliveries to {@link NormalizedEvent}. Only allowlisted
 * fields are copied out of the payload.
 */
@Component
public class GitHubWebhookAdapter implements WebhookAdapter {

    private final Supplier<List<String>> appIds;
    private final Function<String, String> secretOf;
    private final ObjectMapper json = JsonMapper.builder().build();

    @Autowired
    public GitHubWebhookAdapter(GitHubApps apps) {
        this(apps::ids, id -> apps.find(id).map(apps::webhookSecret)
                .orElseThrow(() -> new IllegalStateException("GitHub App " + id + " is no longer configured")));
    }

    /**
     * @param appIds   the configured App IDs
     * @param secretOf an App's webhook secret; throws IllegalStateException if it cannot be read
     */
    GitHubWebhookAdapter(Supplier<List<String>> appIds, Function<String, String> secretOf) {
        this.appIds = appIds;
        this.secretOf = secretOf;
    }

    @Override
    public String provider() {
        return GitHubProvider.ID;
    }

    @Override
    public String deliveryId(Map<String, String> headers) {
        return headers.get("x-github-delivery");
    }

    @Override
    public String eventName(Map<String, String> headers) {
        return headers.get("x-github-event");
    }

    /**
     * GitHub names the App that sent an App webhook in {@code X-GitHub-Hook-Installation-Target-ID}
     * (target type {@code integration}). That header is not signed, so it only picks which App's
     * secret to check; the HMAC has to match that secret. Without the header every App's secret is
     * tried and exactly one has to match.
     */
    @Override
    public String authenticate(Map<String, String> headers, byte[] body) {
        String header = headers.get("x-hub-signature-256");
        if (header == null || !header.startsWith("sha256=")) {
            return null;
        }
        byte[] given;
        try {
            given = HexFormat.of().parseHex(header.substring(7));
        } catch (IllegalArgumentException e) {
            return null;
        }
        List<String> configured = appIds.get();
        String targetType = headers.get("x-github-hook-installation-target-type");
        String targetId = headers.get("x-github-hook-installation-target-id");
        if ("integration".equals(targetType) && targetId != null) {
            return configured.contains(targetId) && signedBy(targetId, body, given) ? targetId : null;
        }
        String matched = null;
        IllegalStateException unreadable = null;
        for (String appId : configured) {
            boolean ok;
            try {
                ok = signedBy(appId, body, given);
            } catch (IllegalStateException e) {
                unreadable = e;
                continue;
            }
            if (ok) {
                if (matched != null) {
                    return null; // two Apps share the secret: cannot tell which one sent it
                }
                matched = appId;
            }
        }
        if (matched == null && unreadable != null) {
            throw unreadable;
        }
        return matched;
    }

    private boolean signedBy(String appId, byte[] body, byte[] given) {
        String secret = secretOf.apply(appId);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return MessageDigest.isEqual(mac.doFinal(body), given);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    @Override
    public List<NormalizedEvent> normalize(Map<String, String> headers, byte[] body) {
        String event = eventName(headers);
        if (event == null) {
            return List.of();
        }
        JsonNode p = json.readTree(jsonBody(headers, body));
        Ctx ctx = new Ctx(deliveryId(headers), p);
        return switch (event) {
            case "push" -> push(ctx);
            case "pull_request" -> pullRequest(ctx);
            case "workflow_run" -> workflowRun(ctx);
            case "release" -> release(ctx);
            default -> List.of();
        };
    }

    /** Common envelope fields. */
    private record Ctx(String deliveryId, JsonNode p) {
        String installationId() {
            return text(p.path("installation").path("id"));
        }

        String repoId() {
            return text(p.path("repository").path("id"));
        }

        String repoFullName() {
            return text(p.path("repository").path("full_name"));
        }

        String sender() {
            return text(p.path("sender").path("login"));
        }

        NormalizedEvent event(EventType type, String actor, String ref, String title, String conclusion, String url,
                              Instant at, Map<String, String> attrs) {
            return new NormalizedEvent(GitHubProvider.ID, deliveryId, installationId(), repoId(), repoFullName(), type,
                    actor, ref, title, conclusion, url, at == null ? Instant.now() : at, attrs);
        }
    }

    private static List<NormalizedEvent> push(Ctx c) {
        JsonNode p = c.p();
        if (p.path("deleted").asBoolean(false)) {
            return List.of();
        }
        String ref = text(p.path("ref"));
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("commitCount", Integer.toString(p.path("commits").size()));
        if (ref != null && ref.startsWith("refs/tags/")) {
            attrs.put("refType", "tag");
        }
        JsonNode head = p.path("head_commit");
        String actor = text(p.path("pusher").path("name"));
        return List.of(c.event(EventType.PUSH, actor != null ? actor : c.sender(), shortRef(ref),
                firstLine(text(head.path("message"))), null, text(p.path("compare")),
                parseTime(text(head.path("timestamp"))), attrs));
    }

    private static List<NormalizedEvent> pullRequest(Ctx c) {
        JsonNode p = c.p();
        JsonNode pr = p.path("pull_request");
        String action = text(p.path("action"));
        EventType type;
        if ("opened".equals(action)) {
            type = EventType.PR_OPENED;
        } else if ("closed".equals(action)) {
            type = pr.path("merged").asBoolean(false) ? EventType.PR_MERGED : EventType.PR_CLOSED;
        } else {
            return List.of();
        }
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("number", text(pr.path("number")));
        attrs.put("baseBranch", text(pr.path("base").path("ref")));
        attrs.put("headBranch", text(pr.path("head").path("ref")));
        String at = type == EventType.PR_OPENED ? text(pr.path("created_at")) : text(pr.path("closed_at"));
        return List.of(c.event(type, c.sender(), text(pr.path("base").path("ref")), text(pr.path("title")),
                type == EventType.PR_OPENED ? "open" : type == EventType.PR_MERGED ? "merged" : "closed",
                text(pr.path("html_url")), parseTime(at), attrs));
    }

    private static List<NormalizedEvent> workflowRun(Ctx c) {
        JsonNode p = c.p();
        JsonNode run = p.path("workflow_run");
        String action = text(p.path("action"));
        String conclusion = text(run.path("conclusion"));
        EventType type;
        if ("requested".equals(action)) {
            type = EventType.ACTION_STARTED;
        } else if ("completed".equals(action) && conclusion != null) {
            type = switch (conclusion) {
                case "success" -> EventType.ACTION_SUCCESS;
                case "failure", "timed_out", "startup_failure" -> EventType.ACTION_FAILURE;
                case "cancelled" -> EventType.ACTION_CANCELLED;
                default -> null;
            };
            if (type == null) {
                return List.of();
            }
        } else {
            return List.of();
        }
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("workflow", text(run.path("name")));
        attrs.put("runNumber", text(run.path("run_number")));
        attrs.put("trigger", text(run.path("event")));
        String actor = text(run.path("triggering_actor").path("login"));
        String at = type == EventType.ACTION_STARTED ? text(run.path("run_started_at")) : text(run.path("updated_at"));
        return List.of(c.event(type, actor != null ? actor : c.sender(), text(run.path("head_branch")),
                text(run.path("display_title")), conclusion != null ? conclusion : "requested",
                text(run.path("html_url")), parseTime(at), attrs));
    }

    private static List<NormalizedEvent> release(Ctx c) {
        JsonNode p = c.p();
        if (!"published".equals(text(p.path("action")))) {
            return List.of();
        }
        JsonNode rel = p.path("release");
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("tag", text(rel.path("tag_name")));
        attrs.put("prerelease", Boolean.toString(rel.path("prerelease").asBoolean(false)));
        String name = text(rel.path("name"));
        return List.of(c.event(EventType.RELEASE_PUBLISHED, c.sender(), text(rel.path("tag_name")),
                name != null && !name.isBlank() ? name : text(rel.path("tag_name")), null,
                text(rel.path("html_url")), parseTime(text(rel.path("published_at"))), attrs));
    }

    // ---------------------------------------------------------------- helpers

    /** GitHub can deliver either application/json or form-encoded {@code payload=<json>}. */
    private static byte[] jsonBody(Map<String, String> headers, byte[] body) {
        String contentType = headers.getOrDefault("content-type", "");
        if (!contentType.startsWith("application/x-www-form-urlencoded")) {
            return body;
        }
        String form = new String(body, StandardCharsets.US_ASCII);
        for (String pair : form.split("&")) {
            if (pair.startsWith("payload=")) {
                return URLDecoder.decode(pair.substring(8), StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
            }
        }
        throw new IllegalArgumentException("form body without payload");
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || node.isContainer()) {
            return null;
        }
        return node.asString();
    }

    private static String firstLine(String s) {
        if (s == null) {
            return null;
        }
        int nl = s.indexOf('\n');
        return nl >= 0 ? s.substring(0, nl) : s;
    }

    private static String shortRef(String ref) {
        if (ref == null) {
            return null;
        }
        if (ref.startsWith("refs/heads/")) {
            return ref.substring("refs/heads/".length());
        }
        if (ref.startsWith("refs/tags/")) {
            return ref.substring("refs/tags/".length());
        }
        return ref;
    }

    private static Instant parseTime(String s) {
        if (s == null) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
