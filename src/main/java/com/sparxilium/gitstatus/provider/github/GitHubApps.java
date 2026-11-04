package com.sparxilium.gitstatus.provider.github;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.sparxilium.gitstatus.config.AppSettings;

/**
 * The configured GitHub Apps. A private GitHub App can only be installed on the account that owns
 * it, so covering several organizations takes one App per organization.
 * <p>
 * Each App is a sub-directory of {@code github.appsDir} named after its App ID:
 * <pre>
 *   github-apps/1234567/private-key.pem
 *   github-apps/1234567/webhook-secret
 * </pre>
 * The directory is scanned on every call, so an App added later is picked up without a restart.
 * The single-App settings {@code github.appId} / {@code github.privateKey} / {@code github.webhookSecret}
 * from earlier releases still count as one App when {@code github.appId} is set.
 */
@Component
public class GitHubApps {

    public static final String PRIVATE_KEY_FILE = "private-key.pem";
    public static final String WEBHOOK_SECRET_FILE = "webhook-secret";
    private static final String APP_ID = "\\d{1,19}";

    public record App(String id, Path privateKey, Path webhookSecret) {
        public String label() {
            return "App " + id;
        }
    }

    private final Path dir;
    private final String legacyId;
    private final String legacyKey;
    private final String legacySecret;

    @Autowired
    public GitHubApps(AppSettings settings) {
        this(settings.githubAppsDir(), settings.githubAppId(), settings.githubPrivateKeyPath(),
                settings.githubWebhookSecretPath());
    }

    public GitHubApps(String dir, String legacyId, String legacyKey, String legacySecret) {
        this.dir = dir == null || dir.isBlank() ? null : Path.of(dir);
        this.legacyId = legacyId == null ? "" : legacyId.trim();
        this.legacyKey = legacyKey == null ? "" : legacyKey.trim();
        this.legacySecret = legacySecret == null ? "" : legacySecret.trim();
    }

    /** Configured Apps ordered by App ID. */
    public List<App> apps() {
        List<App> apps = new ArrayList<>();
        for (Path d : appDirectories()) {
            String name = d.getFileName().toString();
            if (name.matches(APP_ID)) {
                apps.add(new App(name, d.resolve(PRIVATE_KEY_FILE), d.resolve(WEBHOOK_SECRET_FILE)));
            }
        }
        if (!legacyId.isEmpty() && apps.stream().noneMatch(a -> a.id().equals(legacyId))) {
            apps.add(new App(legacyId, Path.of(legacyKey), Path.of(legacySecret)));
        }
        apps.sort(Comparator.comparing(a -> new BigInteger(a.id().matches(APP_ID) ? a.id() : "0")));
        return apps;
    }

    public List<String> ids() {
        return apps().stream().map(App::id).toList();
    }

    public Optional<App> find(String appId) {
        if (appId == null) {
            return Optional.empty();
        }
        return apps().stream().filter(a -> a.id().equals(appId)).findFirst();
    }

    /** Reads the App's private key; the error names the App and path, never the content. */
    public String privateKey(App app) {
        return AppSettings.readSecret("GitHub " + app.label() + " private key", app.privateKey().toString());
    }

    /** Reads the App's webhook secret; the error names the App and path, never the content. */
    public String webhookSecret(App app) {
        return AppSettings.readSecret("GitHub " + app.label() + " webhook", app.webhookSecret().toString());
    }

    /** Missing Apps and unreadable App secrets; names and paths only. */
    public List<String> setupProblems() {
        List<String> problems = new ArrayList<>();
        for (Path d : appDirectories()) {
            if (!d.getFileName().toString().matches(APP_ID)) {
                problems.add("GitHub App directory " + d + " is ignored: the directory name must be the numeric App ID");
            }
        }
        if (!legacyId.isEmpty() && !legacyId.matches(APP_ID)) {
            problems.add("github.appId must be the numeric App ID");
        }
        List<App> apps = apps();
        if (apps.isEmpty()) {
            problems.add("No GitHub App is configured (expected " + (dir == null ? "github.appsDir" : dir)
                    + "/<App ID>/" + PRIVATE_KEY_FILE + " and " + WEBHOOK_SECRET_FILE + ")");
        }
        for (App app : apps) {
            check(problems, () -> privateKey(app));
            check(problems, () -> webhookSecret(app));
        }
        return problems;
    }

    private static void check(List<String> problems, Runnable read) {
        try {
            read.run();
        } catch (IllegalStateException e) {
            problems.add(e.getMessage());
        }
    }

    private List<Path> appDirectories() {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(Files::isDirectory).sorted().toList();
        } catch (IOException | SecurityException e) {
            return List.of();
        }
    }
}
