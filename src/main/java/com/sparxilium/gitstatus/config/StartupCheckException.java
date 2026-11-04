package com.sparxilium.gitstatus.config;

/**
 * A startup problem the administrator can fix. Reported as "Description / Action" instead of
 * a stack trace (see {@link StartupCheckFailureAnalyzer}).
 */
public class StartupCheckException extends RuntimeException {

    private final String action;

    public StartupCheckException(String description, String action) {
        super(description);
        this.action = action;
    }

    public String action() {
        return action;
    }

    /** Finds a StartupCheckException in the cause chain, or null. */
    public static StartupCheckException find(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof StartupCheckException e) {
                return e;
            }
        }
        return null;
    }
}
