package com.sparxilium.gitstatus.model;

/**
 * Internal, provider-neutral event types. Routing rules are stored with these names.
 * {@code supported=false} entries are shown in the TUI but cannot be enabled yet.
 */
public enum EventType {
    PUSH("Code", "Push", true),
    PR_OPENED("Pull Request", "Opened", true),
    PR_MERGED("Pull Request", "Merged", true),
    PR_CLOSED("Pull Request", "Closed", true),
    ACTION_STARTED("GitHub Actions", "Started", true),
    ACTION_SUCCESS("GitHub Actions", "Success", true),
    ACTION_FAILURE("GitHub Actions", "Failure", true),
    ACTION_CANCELLED("GitHub Actions", "Cancelled", true),
    RELEASE_PUBLISHED("Release", "Published", true),
    SECURITY_DEPENDENCY_ALERT("Security", "Dependabot / Dependency alert", false),
    SECURITY_CODE_SCANNING("Security", "Code scanning", false);

    private final String category;
    private final String label;
    private final boolean supported;

    EventType(String category, String label, boolean supported) {
        this.category = category;
        this.label = label;
        this.supported = supported;
    }

    public String category() { return category; }
    public String label() { return label; }
    public boolean supported() { return supported; }

    public static EventType parse(String name) {
        try {
            return EventType.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
