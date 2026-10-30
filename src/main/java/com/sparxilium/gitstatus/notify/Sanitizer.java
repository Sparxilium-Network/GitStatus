package com.sparxilium.gitstatus.notify;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * Cleans user-controlled text before it reaches Discord (SEC-005/006): strips control
 * characters, neutralizes mentions, escapes Markdown and limits length. Mentions are also
 * disabled at the API level via allowed_mentions; this is the second layer.
 */
public final class Sanitizer {

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\p{Cf}&&[^\\n]]");
    private static final Pattern MARKDOWN = Pattern.compile("([\\\\*_~`|\\[\\]])");
    private static final Pattern LINE_START_MARKDOWN = Pattern.compile("(?m)^(\\s*)([#>-])");
    private static final Pattern MASS_MENTION = Pattern.compile("@(everyone|here)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENTITY_MENTION = Pattern.compile("<(@[!&]?|#)(\\d+)>");

    private Sanitizer() {
    }

    /** Single-line text: newlines collapsed. */
    public static String inline(String s, int maxLength) {
        if (s == null) {
            return null;
        }
        return text(s.replaceAll("\\s*\\n\\s*", " "), maxLength);
    }

    /** Multi-line text, newlines kept. */
    public static String text(String s, int maxLength) {
        if (s == null) {
            return null;
        }
        String out = CONTROL.matcher(s).replaceAll("");
        out = truncate(out.strip(), maxLength);
        out = MARKDOWN.matcher(out).replaceAll("\\\\$1");
        out = LINE_START_MARKDOWN.matcher(out).replaceAll("$1\\\\$2");
        out = ENTITY_MENTION.matcher(out).replaceAll("<$1​$2>");
        out = MASS_MENTION.matcher(out).replaceAll("@​$1");
        return out;
    }

    /** Text for a code span (e.g. branch names): backticks removed instead of escaped. */
    public static String code(String s, int maxLength) {
        if (s == null) {
            return null;
        }
        String out = CONTROL.matcher(s).replaceAll("").replace("`", "'").replace("\n", " ");
        return "`" + truncate(out.strip(), maxLength) + "`";
    }

    /** Only absolute https URLs are passed through; anything else is dropped. */
    public static String url(String s) {
        if (s == null || s.length() > 2000) {
            return null;
        }
        try {
            URI uri = URI.create(s);
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null) {
                return uri.toASCIIString();
            }
        } catch (IllegalArgumentException e) {
            // fall through
        }
        return null;
    }

    static String truncate(String s, int maxLength) {
        if (s.codePointCount(0, s.length()) <= maxLength) {
            return s;
        }
        int end = s.offsetByCodePoints(0, Math.max(0, maxLength - 1));
        return s.substring(0, end) + "…";
    }
}
