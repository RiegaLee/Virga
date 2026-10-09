package cn.huohuas001.virga.api;

import java.util.Objects;

/** Permission identity associated with a command invocation. */
public final class Principal {
    private final String id;
    private final String openId;
    private final String username;
    private final PrincipalRole role;

    public Principal(String id, String openId, String username, PrincipalRole role) {
        this.id = id;
        this.openId = openId;
        this.username = Objects.requireNonNull(username, "username");
        this.role = Objects.requireNonNull(role, "role");
    }

    public String getId() { return id; }
    public String getOpenId() { return openId; }
    public String getUsername() { return username; }
    public PrincipalRole getRole() { return role; }
}
