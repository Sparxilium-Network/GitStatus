package com.sparxilium.gitstatus.webhook;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sparxilium.gitstatus.GitStatusApplication;
import com.sparxilium.gitstatus.config.AppSettings;
import com.sparxilium.gitstatus.db.ConfigStore;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Public HTTP surface: webhook receivers and a health check. There is intentionally no
 * endpoint that reads or changes configuration (SEC-008).
 */
@RestController
@Profile(GitStatusApplication.PROFILE_DAEMON)
public class WebhookController {

    private final WebhookService service;
    private final AppSettings settings;
    private final ConfigStore store;

    public WebhookController(WebhookService service, AppSettings settings, ConfigStore store) {
        this.service = service;
        this.settings = settings;
        this.store = store;
    }

    @PostMapping("/webhooks/{provider}")
    public ResponseEntity<String> receive(@PathVariable String provider, HttpServletRequest request) throws IOException {
        long declared = request.getContentLengthLong();
        if (declared > settings.webhookMaxBodyBytes()) {
            return ResponseEntity.status(413).body("payload too large");
        }
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(settings.webhookMaxBodyBytes() + 1);
        }
        if (body.length > settings.webhookMaxBodyBytes()) {
            return ResponseEntity.status(413).body("payload too large");
        }
        Map<String, String> headers = new HashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
        }
        WebhookService.Result result = service.handle(provider.toLowerCase(Locale.ROOT), headers, body, request.getRemoteAddr());
        return ResponseEntity.status(result.status()).contentType(MediaType.TEXT_PLAIN).body(result.body());
    }

    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> health() {
        try {
            store.revision();
            return ResponseEntity.ok("{\"status\":\"UP\"}");
        } catch (RuntimeException e) {
            return ResponseEntity.status(503).body("{\"status\":\"DOWN\"}");
        }
    }
}
