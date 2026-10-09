package cn.huohuas001.virga.api;

import java.util.Objects;

/** Immutable mention normalized by the host. */
public final class MentionSnapshot {
    private final String id;
    private final String openId;
    private final String username;
    private final String role;
    private final boolean bot;
    private final boolean selfMention;

    public MentionSnapshot(String id, String openId, String username, String role) {
        this(id, openId, username, role, false, false);
    }

    public MentionSnapshot(
        String id,
        String openId,
        String username,
        String role,
        boolean bot,
        boolean selfMention
    ) {
        this.id = id;
        this.openId = openId;
        this.username = Objects.requireNonNull(username, "username");
        this.role = role;
        this.bot = bot;
        this.selfMention = selfMention;
    }

    public String getId() { return id; }
    public String getOpenId() { return openId; }
    public String getUsername() { return username; }
    public String getRole() { return role; }
    public boolean isBot() { return bot; }
    public boolean isSelfMention() { return selfMention; }
}
