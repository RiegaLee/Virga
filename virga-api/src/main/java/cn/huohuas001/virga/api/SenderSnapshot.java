package cn.huohuas001.virga.api;

import java.util.Objects;

/** Immutable sender identity normalized by the host. */
public final class SenderSnapshot {
    private final String id;
    private final String openId;
    private final String username;
    private final String role;
    private final String unionOpenId;

    public SenderSnapshot(String id, String openId, String username, String role) {
        this(id, openId, username, role, null);
    }

    public SenderSnapshot(String id, String openId, String username, String role, String unionOpenId) {
        this.id = id;
        this.openId = openId;
        this.username = Objects.requireNonNull(username, "username");
        this.role = role;
        this.unionOpenId = unionOpenId;
    }

    public String getId() { return id; }
    public String getOpenId() { return openId; }
    public String getUsername() { return username; }
    public String getRole() { return role; }
    public String getUnionOpenId() { return unionOpenId; }
}
