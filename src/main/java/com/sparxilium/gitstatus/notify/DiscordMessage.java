package com.sparxilium.gitstatus.notify;

import java.time.Instant;
import java.util.List;

/** Provider-independent embed description. All text is already sanitized. */
public record DiscordMessage(String title, String url, String description, int color,
                             List<Field> fields, String footer, Instant timestamp) {

    public record Field(String name, String value, boolean inline) {
    }
}
