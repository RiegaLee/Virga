package cn.huohuas001.virga.api;

import java.util.Objects;

/** Group-scoped QQ request to prove control of one Minecraft player name. */
public final class BindingChallengeRequest {
    private final String groupOpenId;
    private final String qqOpenId;
    private final String targetPlayerName;

    public BindingChallengeRequest(String groupOpenId, String qqOpenId, String targetPlayerName) {
        this.groupOpenId = requireText(groupOpenId, "groupOpenId");
        this.qqOpenId = requireText(qqOpenId, "qqOpenId");
        this.targetPlayerName = requireText(targetPlayerName, "targetPlayerName");
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    public String getGroupOpenId() { return groupOpenId; }
    public String getQqOpenId() { return qqOpenId; }
    public String getTargetPlayerName() { return targetPlayerName; }
}
