package com.sparxilium.gitstatus.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppSettingsTest {

    @TempDir
    Path tmp;

    @Test
    void reportsMissingAndEmptySecretsWithoutContent() throws Exception {
        Path key = Files.writeString(tmp.resolve("github-app.pem"), "-----BEGIN PRIVATE KEY-----\nsecret-key\n");
        Path hook = Files.writeString(tmp.resolve("github-webhook-secret"), "\n");
        Path token = tmp.resolve("discord-token");

        List<String> problems = settings("", key, hook, token).setupProblems();

        assertEquals(3, problems.size());
        assertTrue(problems.contains("github.appId is not configured"));
        assertTrue(problems.contains("github.webhookSecret secret file is empty: " + hook));
        assertTrue(problems.contains("discord.token secret file is not readable: " + token));
        assertFalse(problems.toString().contains("secret-key"));
    }

    @Test
    void completeSetupHasNoProblems() throws Exception {
        Path key = Files.writeString(tmp.resolve("k"), "key");
        Path hook = Files.writeString(tmp.resolve("h"), "hook");
        Path token = Files.writeString(tmp.resolve("t"), "token");
        assertTrue(settings("123", key, hook, token).setupProblems().isEmpty());
    }

    private static AppSettings settings(String appId, Path key, Path hook, Path token) {
        return new AppSettings("bot.db", "https://api.github.com", appId, key.toString(), hook.toString(),
                token.toString(), 5000, 1000, 33444);
    }
}
