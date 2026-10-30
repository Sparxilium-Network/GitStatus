package com.sparxilium.gitstatus.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SanitizerTest {

    @Test
    void neutralizesMassAndEntityMentions() {
        String out = Sanitizer.inline("ping @everyone and @here, also <@123456789012345678> <@&42> <#99>", 500);
        assertFalse(out.contains("@everyone"));
        assertFalse(out.contains("@here"));
        assertFalse(out.contains("<@123456789012345678>"));
        assertFalse(out.contains("<@&42>"));
        assertFalse(out.contains("<#99>"));
        assertTrue(out.contains("@​everyone"));
    }

    @Test
    void stripsControlCharactersAndCollapsesNewlines() {
        assertEquals("a b c", Sanitizer.inline("a\u0007\n b‮\r\nc", 100));
    }

    @Test
    void escapesMarkdown() {
        assertEquals("\\*\\*bold\\*\\* \\[x\\](y)", Sanitizer.inline("**bold** [x](y)", 100));
        assertEquals("\\# heading", Sanitizer.text("# heading", 100));
    }

    @Test
    void truncatesByCodePoints() {
        String out = Sanitizer.inline("一二三四五六七八九十", 5);
        assertEquals("一二三四…", out);
    }

    @Test
    void codeSpanCannotBreakOut() {
        assertEquals("`a'b @everyone`", Sanitizer.code("a`b @everyone", 100));
    }

    @Test
    void onlyHttpsUrls() {
        assertEquals("https://github.com/a/b", Sanitizer.url("https://github.com/a/b"));
        assertNull(Sanitizer.url("javascript:alert(1)"));
        assertNull(Sanitizer.url("http://example.com"));
        assertNull(Sanitizer.url(null));
    }
}
