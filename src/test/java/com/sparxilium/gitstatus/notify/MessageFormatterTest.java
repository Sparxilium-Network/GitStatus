package com.sparxilium.gitstatus.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sparxilium.gitstatus.model.EventType;
import com.sparxilium.gitstatus.model.Language;
import com.sparxilium.gitstatus.model.NormalizedEvent;

class MessageFormatterTest {

    private static NormalizedEvent push(String url) {
        return new NormalizedEvent("github", "d", "7", "42", "acme/api", EventType.PUSH, "alice", "main",
                "fix @everyone", null, url, Instant.EPOCH, Map.of("commitCount", "3"));
    }

    private static NormalizedEvent pr(EventType type) {
        return new NormalizedEvent("github", "d", "7", "42", "acme/api", type, "bob", "main", "Add feature", null,
                "https://github.com/acme/api/pull/5", Instant.EPOCH,
                Map.of("number", "5", "headBranch", "feat", "baseBranch", "main"));
    }

    @Test
    void englishWording() {
        DiscordMessage m = MessageFormatter.format(push("https://github.com/acme/api/compare/a...b"), Language.EN);
        assertEquals("[acme/api] Push to main", m.title());
        assertEquals("Actor", m.fields().get(0).name());
        assertEquals("Commits", m.fields().get(2).name());
        assertEquals("[acme/api] PR #5 merged", MessageFormatter.format(pr(EventType.PR_MERGED), Language.EN).title());
    }

    @Test
    void traditionalChineseWording() {
        DiscordMessage m = MessageFormatter.format(push("https://github.com/acme/api/compare/a...b"), Language.ZH_TW);
        assertEquals("[acme/api] 推送到 main", m.title());
        assertEquals("執行者", m.fields().get(0).name());
        assertEquals("分支", m.fields().get(1).name());
        assertEquals("[acme/api] PR #5 已合併", MessageFormatter.format(pr(EventType.PR_MERGED), Language.ZH_TW).title());
        assertEquals("[acme/api] PR #5 已開啟", MessageFormatter.format(pr(EventType.PR_OPENED), Language.ZH_TW).title());
    }

    @Test
    void sanitizesInEveryLanguage() {
        for (Language lang : Language.values()) {
            DiscordMessage m = MessageFormatter.format(push("javascript:alert(1)"), lang);
            assertFalse(m.description().contains("@everyone"), lang.name());
            assertNull(m.url(), lang.name());
        }
    }

    @Test
    void testMessageIsLocalizedAndSanitized() {
        DiscordMessage zh = MessageFormatter.testMessage("@everyone", Language.ZH_TW);
        assertEquals("Git 通知系統測試訊息", zh.title());
        assertFalse(zh.description().contains("@everyone"));
        assertEquals("Git Notification Manager test message", MessageFormatter.testMessage("x", Language.EN).title());
    }

    @Test
    void unknownLanguageCodeFallsBackToEnglish() {
        assertEquals(Language.EN, Language.fromCode("fr"));
        assertEquals(Language.EN, Language.fromCode(null));
        assertEquals(Language.ZH_TW, Language.fromCode("zh-tw"));
    }
}
