package cn.huohuas001.virga.api;

import java.util.Objects;

/** Stable identity supplied by an addon when it opens a plugin context. */
public final class PluginDescriptor {
    private final String id;
    private final String name;
    private final String version;
    private final ApiVersion requiredApiVersion;

    public PluginDescriptor(String id, String name, String version, ApiVersion requiredApiVersion) {
        this.id = requireText(id, "id");
        this.name = requireText(name, "name");
        this.version = requireText(version, "version");
        this.requiredApiVersion = Objects.requireNonNull(requiredApiVersion, "requiredApiVersion");
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getVersion() {
        return version;
    }

    public ApiVersion getRequiredApiVersion() {
        return requiredApiVersion;
    }
}
