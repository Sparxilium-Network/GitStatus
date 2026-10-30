package com.sparxilium.gitstatus.notify;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.sparxilium.gitstatus.model.EventType;
import com.sparxilium.gitstatus.model.Language;
import com.sparxilium.gitstatus.model.NormalizedEvent;
import com.sparxilium.gitstatus.notify.DiscordMessage.Field;

/**
 * Builds the Discord embed from an allowlist of {@link NormalizedEvent} fields (SEC-007):
 * a short summary plus a link, never the provider payload. Wording follows the
 * destination's {@link Language}; data values (repo, branch, titles) are never translated.
 */
public final class MessageFormatter {

    private static final int GREEN = 0x2DA44E;
    private static final int RED = 0xCF222E;
    private static final int GREY = 0x6E7781;
    private static final int BLUE = 0x0969DA;
    private static final int PURPLE = 0x8250DF;

    /** All user-visible wording of a notification. */
    private record Texts(
            String actor, String branch, String tag, String commits, String conclusion, String preRelease, String yes,
            String pushTo, String pr, String prOpened, String prMerged, String prClosed,
            String workflow, String actionStarted, String actionSucceeded, String actionFailed, String actionCancelled,
            String release, String releasePublished,
            String testTitle, String testBody) {
    }

    private static final Texts EN = new Texts(
            "Actor", "Branch", "Tag", "Commits", "Conclusion", "Pre-release", "yes",
            "Push to %s", "PR #%s", "opened", "merged", "closed",
            "Workflow", "started", "succeeded", "failed", "cancelled",
            "Release %s", "published",
            "Git Notification Manager test message",
            "This channel is configured as notification destination \"%s\".");

    private static final Texts ZH_TW = new Texts(
            "執行者", "分支", "標籤", "Commit 數", "結果", "預先發行版", "是",
            "推送到 %s", "PR #%s", "已開啟", "已合併", "已關閉",
            "工作流程", "已開始", "成功", "失敗", "已取消",
            "版本 %s", "已發布",
            "Git 通知系統測試訊息",
            "此頻道已設定為通知目的地「%s」。");

    private MessageFormatter() {
    }

    private static Texts texts(Language language) {
        return language == Language.ZH_TW ? ZH_TW : EN;
    }

    public static DiscordMessage format(NormalizedEvent e, Language language) {
        Texts t = texts(language);
        String repo = Sanitizer.inline(e.repositoryFullName(), 100);
        String title = Sanitizer.inline(e.title(), 200);
        String actor = Sanitizer.inline(e.actor(), 64);
        List<Field> fields = new ArrayList<>();
        if (actor != null) {
            fields.add(new Field(t.actor(), actor, true));
        }

        String heading;
        String description = title;
        int color;
        switch (e.eventType()) {
            case PUSH -> {
                String count = e.attr("commitCount");
                heading = "[" + repo + "] " + t.pushTo().formatted(Sanitizer.inline(e.ref(), 100));
                color = BLUE;
                if (e.ref() != null) {
                    fields.add(new Field("tag".equals(e.attr("refType")) ? t.tag() : t.branch(), Sanitizer.code(e.ref(), 100), true));
                }
                if (count != null) {
                    fields.add(new Field(t.commits(), count, true));
                }
            }
            case PR_OPENED, PR_MERGED, PR_CLOSED -> {
                String verb = switch (e.eventType()) {
                    case PR_OPENED -> t.prOpened();
                    case PR_MERGED -> t.prMerged();
                    default -> t.prClosed();
                };
                heading = "[" + repo + "] " + t.pr().formatted(Sanitizer.inline(e.attr("number"), 10)) + " " + verb;
                color = e.eventType() == EventType.PR_MERGED ? PURPLE : e.eventType() == EventType.PR_OPENED ? GREEN : GREY;
                if (e.attr("headBranch") != null && e.attr("baseBranch") != null) {
                    fields.add(new Field(t.branch(), Sanitizer.code(e.attr("headBranch"), 80) + " → "
                            + Sanitizer.code(e.attr("baseBranch"), 80), false));
                }
            }
            case ACTION_STARTED, ACTION_SUCCESS, ACTION_FAILURE, ACTION_CANCELLED -> {
                String workflow = Sanitizer.inline(e.attr("workflow"), 100);
                String status = switch (e.eventType()) {
                    case ACTION_STARTED -> t.actionStarted();
                    case ACTION_SUCCESS -> t.actionSucceeded();
                    case ACTION_FAILURE -> t.actionFailed();
                    default -> t.actionCancelled();
                };
                heading = "[" + repo + "] " + (workflow != null ? workflow : t.workflow())
                        + (e.attr("runNumber") != null ? " #" + Sanitizer.inline(e.attr("runNumber"), 10) : "") + " " + status;
                color = switch (e.eventType()) {
                    case ACTION_SUCCESS -> GREEN;
                    case ACTION_FAILURE -> RED;
                    case ACTION_STARTED -> BLUE;
                    default -> GREY;
                };
                if (e.ref() != null) {
                    fields.add(new Field(t.branch(), Sanitizer.code(e.ref(), 100), true));
                }
                if (e.conclusion() != null) {
                    fields.add(new Field(t.conclusion(), Sanitizer.inline(e.conclusion(), 32), true));
                }
            }
            case RELEASE_PUBLISHED -> {
                heading = "[" + repo + "] " + t.release().formatted(Sanitizer.inline(e.attr("tag"), 100)) + " " + t.releasePublished();
                color = GREEN;
                if ("true".equals(e.attr("prerelease"))) {
                    fields.add(new Field(t.preRelease(), t.yes(), true));
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

    /** Message sent by the admin TUI's "Send test message" action. */
    public static DiscordMessage testMessage(String destinationName, Language language) {
        Texts t = texts(language);
        return new DiscordMessage(t.testTitle(), null, t.testBody().formatted(Sanitizer.inline(destinationName, 100)),
                BLUE, List.of(), "gitstatus", Instant.now());
    }
}
