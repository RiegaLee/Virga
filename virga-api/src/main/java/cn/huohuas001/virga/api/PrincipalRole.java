package cn.huohuas001.virga.api;

/** Host-normalized role; addons must not parse QQ SDK metadata themselves. */
public enum PrincipalRole {
    OWNER,
    ADMIN,
    MEMBER,
    UNKNOWN;

    public boolean isAdministrator() {
        return this == OWNER || this == ADMIN;
    }
}
