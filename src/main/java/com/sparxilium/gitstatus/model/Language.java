package com.sparxilium.gitstatus.model;

/** Language of the Discord notifications sent to a destination. The admin UI itself is English only. */
public enum Language {
    EN("en", "English"),
    ZH_TW("zh-TW", "Traditional Chinese");

    private final String code;
    private final String displayName;

    Language(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    /** Value stored in the database. */
    public String code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    /** Unknown or missing codes fall back to English. */
    public static Language fromCode(String code) {
        for (Language l : values()) {
            if (l.code.equalsIgnoreCase(code)) {
                return l;
            }
        }
        return EN;
    }

    @Override
    public String toString() {
        return displayName + " (" + code + ")";
    }
}
