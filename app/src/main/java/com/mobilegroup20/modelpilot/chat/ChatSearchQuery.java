package com.mobilegroup20.modelpilot.chat;

/** Literal substring search, not a SQL/FTS query language. Original ModelPilot enhancement. */
public final class ChatSearchQuery {
    public static final int MAX_CHARACTERS = 160;
    public static final int RESULT_LIMIT = 50;
    public final String text;
    /** null = all projects, empty = unfiled, otherwise the actual project ID. */
    public final String projectId;
    public final String pattern;
    public ChatSearchQuery(String raw, String projectId) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() > MAX_CHARACTERS) value = value.substring(0, MAX_CHARACTERS);
        text = value; this.projectId = projectId; pattern = literalPattern(value);
    }
    public static String literalPattern(String text) {
        return "%" + text.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
    public boolean sameAs(ChatSearchQuery other) {
        return other != null && text.equals(other.text) && java.util.Objects.equals(projectId, other.projectId);
    }
}
