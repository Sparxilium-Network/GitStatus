package com.sparxilium.gitstatus.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * System-level settings from config.conf. Secrets are never stored in config.conf
 * itself: the config only holds paths to secret files, which are read on demand.
 */
@Component
public class AppSettings {

    private final String database;
    private final String githubApiUrl;
    private final String githubAppId;
    private final String githubPrivateKeyPath;
    private final String githubWebhookSecretPath;
    private final String discordTokenPath;
    private final long reloadIntervalMs;
    private final int webhookMaxBodyBytes;
    private final int serverPort;

    public AppSettings(@Value("${database}") String database,
                       @Value("${github.apiUrl}") String githubApiUrl,
                       @Value("${github.appId:}") String githubAppId,
                       @Value("${github.privateKey:}") String githubPrivateKeyPath,
                       @Value("${github.webhookSecret:}") String githubWebhookSecretPath,
                       @Value("${discord.token:}") String discordTokenPath,
                       @Value("${reload.intervalMs}") long reloadIntervalMs,
                       @Value("${webhook.maxBodyBytes}") int webhookMaxBodyBytes,
                       @Value("${server.port}") int serverPort) {
        this.database = database;
        this.githubApiUrl = githubApiUrl.replaceAll("/+$", "");
        this.githubAppId = githubAppId.trim();
        this.githubPrivateKeyPath = githubPrivateKeyPath.trim();
        this.githubWebhookSecretPath = githubWebhookSecretPath.trim();
        this.discordTokenPath = discordTokenPath.trim();
        this.reloadIntervalMs = reloadIntervalMs;
        this.webhookMaxBodyBytes = webhookMaxBodyBytes;
        this.serverPort = serverPort;
    }

    public String database() { return database; }
    public String githubApiUrl() { return githubApiUrl; }
    public String githubAppId() { return githubAppId; }
    public String githubPrivateKeyPath() { return githubPrivateKeyPath; }
    public long reloadIntervalMs() { return reloadIntervalMs; }
    public int webhookMaxBodyBytes() { return webhookMaxBodyBytes; }
    public int serverPort() { return serverPort; }

    public boolean githubConfigured() {
        return !githubAppId.isEmpty() && Files.isReadable(Path.of(githubPrivateKeyPath));
    }

    public String readGithubPrivateKey() { return readSecret("github.privateKey", githubPrivateKeyPath); }
    public String readGithubWebhookSecret() { return readSecret("github.webhookSecret", githubWebhookSecretPath); }
    public String readDiscordToken() { return readSecret("discord.token", discordTokenPath); }

    /** Reads a secret file. The error message names the setting and path, never the content. */
    static String readSecret(String setting, String path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalStateException(setting + " is not configured");
        }
        try {
            String value = Files.readString(Path.of(path), StandardCharsets.UTF_8).trim();
            if (value.isEmpty()) {
                throw new IllegalStateException(setting + " secret file is empty: " + path);
            }
            return value;
        } catch (IOException e) {
            throw new IllegalStateException(setting + " secret file is not readable: " + path);
        }
    }
}
