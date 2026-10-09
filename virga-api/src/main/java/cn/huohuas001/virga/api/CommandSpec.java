package cn.huohuas001.virga.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Metadata for a callback-based addon command. */
public final class CommandSpec {
    private final String id;
    private final List<String> aliases;
    private final String description;
    private final CommandPermission permission;
    private final boolean publishToMenu;

    public CommandSpec(
        String id,
        List<String> aliases,
        String description,
        CommandPermission permission,
        boolean publishToMenu
    ) {
        this.id = normalizeName(id, "id");
        Objects.requireNonNull(aliases, "aliases");
        List<String> normalizedAliases = new ArrayList<String>();
        for (String alias : aliases) normalizedAliases.add(normalizeName(alias, "alias"));
        this.aliases = Collections.unmodifiableList(normalizedAliases);
        this.description = Objects.requireNonNull(description, "description").trim();
        this.permission = Objects.requireNonNull(permission, "permission");
        this.publishToMenu = publishToMenu;
    }

    private static String normalizeName(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.startsWith("/") || normalized.matches(".*\\s+.*")) {
            throw new IllegalArgumentException(field + " must be a non-blank command token without slash or whitespace");
        }
        return normalized;
    }

    public String getId() { return id; }
    public List<String> getAliases() { return aliases; }
    public String getDescription() { return description; }
    public CommandPermission getPermission() { return permission; }
    public boolean isPublishToMenu() { return publishToMenu; }
}
