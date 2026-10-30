package com.sparxilium.gitstatus.provider.github;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.sparxilium.gitstatus.config.AppSettings;
import com.sparxilium.gitstatus.db.ConfigStore.DiscoveredInstallation;
import com.sparxilium.gitstatus.db.ConfigStore.DiscoveredRepository;
import com.sparxilium.gitstatus.model.EventType;
import com.sparxilium.gitstatus.model.Records.ProviderInstallation;
import com.sparxilium.gitstatus.provider.GitProvider;
import com.sparxilium.gitstatus.provider.ProviderException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * GitHub App API adapter. Installation access tokens are kept in memory only and refreshed
 * before they expire. Only read operations are performed.
 */
@Component
public class GitHubProvider implements GitProvider {

    public static final String ID = "github";

    private static final Pattern NEXT_LINK = Pattern.compile("<([^>]+)>;\\s*rel=\"next\"");
    private static final Duration TOKEN_REFRESH_MARGIN = Duration.ofMinutes(5);

    private final AppSettings settings;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = JsonMapper.builder().build();
    private final Map<String, InstallationToken> tokens = new ConcurrentHashMap<>();
    private volatile GitHubAppAuth auth;

    private record InstallationToken(String value, Instant expiresAt) {
    }

    public GitHubProvider(AppSettings settings) {
        this.settings = settings;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "GitHub";
    }

    @Override
    public boolean configured() {
        return settings.githubConfigured();
    }

    @Override
    public String checkAuth() {
        JsonNode app = getJson(settings.githubApiUrl() + "/app", appJwt());
        return app.path("slug").asString("?") + " (App ID " + settings.githubAppId() + ")";
    }

    @Override
    public List<DiscoveredInstallation> listInstallations() {
        List<DiscoveredInstallation> out = new ArrayList<>();
        for (JsonNode inst : getPaged(settings.githubApiUrl() + "/app/installations?per_page=100", this::appJwt, null)) {
            if (!inst.path("suspended_at").isNull() && !inst.path("suspended_at").isMissingNode()) {
                continue;
            }
            JsonNode account = inst.path("account");
            out.add(new DiscoveredInstallation(
                    inst.path("id").asString(),
                    account.path("login").asString("?"),
                    account.path("type").asString("User"),
                    json.writeValueAsString(inst.path("permissions")),
                    json.writeValueAsString(inst.path("events"))));
        }
        return out;
    }

    @Override
    public List<DiscoveredRepository> listRepositories(ProviderInstallation installation) {
        String installationId = installation.externalId();
        List<DiscoveredRepository> out = new ArrayList<>();
        for (JsonNode repo : getPaged(settings.githubApiUrl() + "/installation/repositories?per_page=100",
                () -> installationToken(installationId), "repositories")) {
            String visibility = repo.path("visibility").asString("");
            if (visibility.isEmpty()) {
                visibility = repo.path("private").asBoolean(true) ? "private" : "public";
            }
            out.add(new DiscoveredRepository(repo.path("id").asString(), repo.path("full_name").asString(),
                    visibility.toUpperCase(), repo.path("archived").asBoolean(false)));
        }
        return out;
    }

    @Override
    public Map<EventType, Availability> capabilities(ProviderInstallation installation) {
        JsonNode permissions = json.readTree(installation.permissionsJson());
        Set<String> events = new HashSet<>();
        json.readTree(installation.eventsJson()).forEach(e -> events.add(e.asString()));

        Map<EventType, Availability> out = new EnumMap<>(EventType.class);
        for (EventType type : EventType.values()) {
            out.put(type, switch (type) {
                case PUSH -> availability(permissions, events, "contents", "push");
                case PR_OPENED, PR_MERGED, PR_CLOSED -> availability(permissions, events, "pull_requests", "pull_request");
                case ACTION_STARTED, ACTION_SUCCESS, ACTION_FAILURE, ACTION_CANCELLED ->
                        availability(permissions, events, "actions", "workflow_run");
                case RELEASE_PUBLISHED -> availability(permissions, events, "contents", "release");
                case SECURITY_DEPENDENCY_ALERT, SECURITY_CODE_SCANNING -> Availability.UNSUPPORTED;
            });
        }
        return out;
    }

    private static Availability availability(JsonNode permissions, Set<String> events, String permission, String event) {
        String level = permissions.path(permission).asString("");
        if (!level.equals("read") && !level.equals("write")) {
            return Availability.NO_PERMISSION;
        }
        if (!events.contains(event)) {
            return Availability.NOT_SUBSCRIBED;
        }
        return Availability.AVAILABLE;
    }

    // ---------------------------------------------------------------- auth

    private GitHubAppAuth auth() {
        GitHubAppAuth a = auth;
        if (a == null) {
            synchronized (this) {
                if (auth == null) {
                    if (settings.githubAppId().isEmpty()) {
                        throw new ProviderException("github.appId 未設定");
                    }
                    try {
                        auth = new GitHubAppAuth(settings.githubAppId(), settings.readGithubPrivateKey());
                    } catch (IllegalStateException e) {
                        throw new ProviderException(e.getMessage());
                    }
                }
                a = auth;
            }
        }
        return a;
    }

    private String appJwt() {
        return auth().createJwt(Instant.now());
    }

    private String installationToken(String installationId) {
        InstallationToken cached = tokens.get(installationId);
        if (cached != null && Instant.now().plus(TOKEN_REFRESH_MARGIN).isBefore(cached.expiresAt())) {
            return cached.value();
        }
        HttpRequest request = baseRequest(settings.githubApiUrl() + "/app/installations/" + installationId + "/access_tokens", appJwt())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        JsonNode body = json.readTree(send(request).body());
        InstallationToken token = new InstallationToken(body.path("token").asString(), Instant.parse(body.path("expires_at").asString()));
        tokens.put(installationId, token);
        return token.value();
    }

    // ---------------------------------------------------------------- http

    private interface TokenSupplier {
        String get();
    }

    private List<JsonNode> getPaged(String firstUrl, TokenSupplier token, String arrayField) {
        List<JsonNode> items = new ArrayList<>();
        String url = firstUrl;
        int pages = 0;
        while (url != null && pages++ < 100) {
            HttpResponse<String> response = send(baseRequest(url, token.get()).GET().build());
            JsonNode body = json.readTree(response.body());
            (arrayField == null ? body : body.path(arrayField)).forEach(items::add);
            url = response.headers().firstValue("link").map(GitHubProvider::nextLink).orElse(null);
        }
        return items;
    }

    private static String nextLink(String header) {
        Matcher m = NEXT_LINK.matcher(header);
        return m.find() ? m.group(1) : null;
    }

    private JsonNode getJson(String url, String bearer) {
        return json.readTree(send(baseRequest(url, bearer).GET().build()).body());
    }

    private static HttpRequest.Builder baseRequest(String url, String bearer) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "gitstatus-notifier")
                .header("Authorization", "Bearer " + bearer);
    }

    private HttpResponse<String> send(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ProviderException("GitHub API 連線失敗: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("GitHub API 請求被中斷");
        }
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return response;
        }
        String remaining = response.headers().firstValue("x-ratelimit-remaining").orElse("");
        if (status == 429 || (status == 403 && ("0".equals(remaining) || response.headers().firstValue("retry-after").isPresent()))) {
            String wait = response.headers().firstValue("retry-after").map(s -> s + " 秒後")
                    .or(() -> response.headers().firstValue("x-ratelimit-reset")
                            .map(s -> Instant.ofEpochSecond(Long.parseLong(s)) + " 之後"))
                    .orElse("稍後");
            throw new ProviderException("GitHub API rate limit，請於 " + wait + "再試");
        }
        // Only GitHub's short "message" is surfaced; the body is not logged.
        String message;
        try {
            message = json.readTree(response.body()).path("message").asString("");
        } catch (RuntimeException e) {
            message = "";
        }
        if (message.length() > 200) {
            message = message.substring(0, 200);
        }
        throw new ProviderException("GitHub API HTTP " + status + (message.isEmpty() ? "" : ": " + message));
    }
}
