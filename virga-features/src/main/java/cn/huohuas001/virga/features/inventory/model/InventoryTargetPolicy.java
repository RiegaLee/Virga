package cn.huohuas001.virga.features.inventory.model;

import cn.huohuas001.virga.api.BindingVerificationState;
import cn.huohuas001.virga.api.PlayerBinding;
import java.util.List;
import java.util.Objects;

/** Pure authorization decision used before player lookup or snapshot access. */
public final class InventoryTargetPolicy {
    public enum Status { OWN_VERIFIED, ADMIN_ONLINE_LOOKUP, BINDING_REQUIRED, UNVERIFIED, DENIED, INVALID_TARGET }

    public static Decision resolve(List<PlayerBinding> own, String selector, boolean administrator) {
        Objects.requireNonNull(own, "own");
        String value = selector == null ? "" : selector.trim();
        PlayerBinding selected = value.isEmpty() ? (own.isEmpty() ? null : own.get(0)) : find(own, value);
        if (selected != null) {
            return selected.getVerificationState() == BindingVerificationState.VERIFIED
                ? new Decision(Status.OWN_VERIFIED, selected)
                : new Decision(Status.UNVERIFIED, null);
        }
        if (value.isEmpty()) return new Decision(Status.BINDING_REQUIRED, null);
        if (!administrator) return new Decision(Status.DENIED, null);
        if (!value.matches("[A-Za-z0-9_]{1,16}")) return new Decision(Status.INVALID_TARGET, null);
        return new Decision(Status.ADMIN_ONLINE_LOOKUP, null);
    }

    private static PlayerBinding find(List<PlayerBinding> values, String selector) {
        for (PlayerBinding value : values) {
            if (Integer.toString(value.getSlot()).equals(selector) || value.getPlayerName().equalsIgnoreCase(selector) ||
                value.getBindingId().map(id -> id.equalsIgnoreCase(selector)).orElse(false)) return value;
        }
        return null;
    }

    private InventoryTargetPolicy() {}

    public static final class Decision {
        private final Status status;
        private final PlayerBinding binding;
        private Decision(Status status, PlayerBinding binding) { this.status = status; this.binding = binding; }
        public Status getStatus() { return status; }
        public PlayerBinding getBinding() { return binding; }
    }
}
