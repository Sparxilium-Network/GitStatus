package com.sparxilium.gitstatus.notify;

import java.util.ArrayList;
import java.util.List;

import com.sparxilium.gitstatus.model.EventType;
import com.sparxilium.gitstatus.model.NormalizedEvent;
import com.sparxilium.gitstatus.notify.DiscordMessage.Field;

/**
 * Builds the Discord embed from an allowlist of {@link NormalizedEvent} fields (SEC-007):
 * a short summary plus a link, never the provider payload.
 */
public final class MessageFormatter {

    private static final int GREEN = 0x2DA44E;
    private static final int RED = 0xCF222E;
    private static final int GREY = 0x6E7781;
    private static final int BLUE = 0x0969DA;
    private static final int PURPLE = 0x8250DF;

    private MessageFormatter() {
    }

    public static DiscordMessage format(NormalizedEvent e) {
        String repo = Sanitizer.inline(e.repositoryFullName(), 100);
        String title = Sanitizer.inline(e.title(), 200);
        String actor = Sanitizer.inline(e.actor(), 64);
        List<Field> fields = new ArrayList<>();
        if (actor != null) {
            fields.add(new Field("Actor", actor, true));
        }

        String heading;
        String description = title;
        int color;
        switch (e.eventType()) {
            case PUSH -> {
                String count = e.attr("commitCount");
                heading = "[" + repo + "] Push to " + Sanitizer.inline(e.ref(), 100);
                color = BLUE;
                if (e.ref() != null) {
                    fields.add(new Field("tag".equals(e.attr("refType")) ? "Tag" : "Branch", Sanitizer.code(e.ref(), 100), true));
                }
                if (count != null) {
                    fields.add(new Field("Commits", count, true));
                }
            }
            case PR_OPENED, PR_MERGED, PR_CLOSED -> {
                String verb = switch (e.eventType()) {
                    case PR_OPENED -> "opened";
                    case PR_MERGED -> "merged";
                    default -> "closed";
                };
                heading = "[" + repo + "] PR #" + Sanitizer.inline(e.attr("number"), 10) + " " + verb;
                color = e.eventType() == EventType.PR_MERGED ? PURPLE : e.eventType() == EventType.PR_OPENED ? GREEN : GREY;
                if (e.attr("headBranch") != null && e.attr("baseBranch") != null) {
                    fields.add(new Field("Branch", Sanitizer.code(e.attr("headBranch"), 80) + " → "
                            + Sanitizer.code(e.attr("baseBranch"), 80), false));
                }
            }
            case ACTION_STARTED, ACTION_SUCCESS, ACTION_FAILURE, ACTION_CANCELLED -> {
                String workflow = Sanitizer.inline(e.attr("workflow"), 100);
                String status = switch (e.eventType()) {
                    case ACTION_STARTED -> "started";
                    case ACTION_SUCCESS -> "succeeded";
                    case ACTION_FAILURE -> "failed";
                    default -> "cancelled";
                };
                heading = "[" + repo + "] " + (workflow != null ? workflow : "Workflow")
                        + (e.attr("runNumber") != null ? " #" + Sanitizer.inline(e.attr("runNumber"), 10) : "") + " " + status;
                color = switch (e.eventType()) {
                    case ACTION_SUCCESS -> GREEN;
                    case ACTION_FAILURE -> RED;
                    case ACTION_STARTED -> BLUE;
                    default -> GREY;
                };
                if (e.ref() != null) {
                    fields.add(new Field("Branch", Sanitizer.code(e.ref(), 100), true));
                }
                if (e.conclusion() != null) {
                    fields.add(new Field("Conclusion", Sanitizer.inline(e.conclusion(), 32), true));
                }
            }
            case RELEASE_PUBLISHED -> {
                heading = "[" + repo + "] Release " + Sanitizer.inline(e.attr("tag"), 100) + " published";
                color = GREEN;
                if ("true".equals(e.attr("prerelease"))) {
                    fields.add(new Field("Pre-release", "yes", true));
                }
            }
            default -> {
                heading = "[" + repo + "] " + e.eventType().category() + " · " + e.eventType().label();
                color = GREY;
            }
        }
        return new DiscordMessage(Sanitizer.truncate(heading, 256), Sanitizer.url(e.url()), description, color,
                List.copyOf(fields), e.provider(), e.occurredAt());
    }
}
