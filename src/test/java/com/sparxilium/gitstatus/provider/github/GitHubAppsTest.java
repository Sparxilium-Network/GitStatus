package com.sparxilium.gitstatus.provider.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitHubAppsTest {

    @TempDir
    Path tmp;

    private Path app(String id, String key, String secret) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("github-apps").resolve(id));
        if (key != null) {
            Files.writeString(dir.resolve(GitHubApps.PRIVATE_KEY_FILE), key);
        }
        if (secret != null) {
            Files.writeString(dir.resolve(GitHubApps.WEBHOOK_SECRET_FILE), secret);
        }
        return dir;
    }

    private GitHubApps apps(String legacyId) {
        return new GitHubApps(tmp.resolve("github-apps").toString(), legacyId,
                tmp.resolve("github-app.pem").toString(), tmp.resolve("github-webhook-secret").toString());
    }

    @Test
    void findsOneAppPerDirectoryOrderedByAppId() throws Exception {
        app("99", "k", "s");
        app("1234567", "k", "s");
        app("not-an-app", "k", "s");
        GitHubApps apps = apps("");

        assertEquals(List.of("99", "1234567"), apps.ids());
        assertEquals("s", apps.webhookSecret(apps.find("99").orElseThrow()));
        assertTrue(apps.find("5").isEmpty());
        assertEquals(List.of("GitHub App directory " + tmp.resolve("github-apps").resolve("not-an-app")
                + " is ignored: the directory name must be the numeric App ID"), apps.setupProblems());
    }

    @Test
    void appAddedLaterIsPickedUpWithoutRestart() throws Exception {
        app("1", "k", "s");
        GitHubApps apps = apps("");
        assertEquals(List.of("1"), apps.ids());
        app("2", "k", "s");
        assertEquals(List.of("1", "2"), apps.ids());
    }

    @Test
    void legacySingleAppSettingsStillCount() throws Exception {
        Files.writeString(tmp.resolve("github-app.pem"), "k");
        Files.writeString(tmp.resolve("github-webhook-secret"), "legacy");
        app("2", "k", "s");
        GitHubApps apps = apps("1");

        assertEquals(List.of("1", "2"), apps.ids());
        assertEquals("legacy", apps.webhookSecret(apps.find("1").orElseThrow()));
        assertTrue(apps.setupProblems().isEmpty());
        // a directory for the same App ID wins over the legacy settings
        app("1", "k", "dir");
        assertEquals("dir", apps.webhookSecret(apps.find("1").orElseThrow()));
    }

    @Test
    void reportsMissingAppsAndSecretsWithoutContent() throws Exception {
        assertEquals(1, apps("").setupProblems().size());
        assertTrue(apps("").setupProblems().get(0).startsWith("No GitHub App is configured"));

        Path dir = app("7", "-----BEGIN PRIVATE KEY-----\nsecret-key\n", null);
        List<String> problems = apps("").setupProblems();
        assertEquals(List.of("GitHub App 7 webhook secret file is not readable: "
                + dir.resolve(GitHubApps.WEBHOOK_SECRET_FILE)), problems);
        assertFalse(problems.toString().contains("secret-key"));
    }
}
