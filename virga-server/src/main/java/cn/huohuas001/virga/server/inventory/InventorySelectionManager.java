package cn.huohuas001.virga.server.inventory;

import cn.huohuas001.virga.api.PlayerBinding;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Source-migrated one-time account selection used by the original Inventory Addon. */
public final class InventorySelectionManager {
    private final long ttlMillis;
    private final Map<String, Pending> pendingByOwner = new HashMap<String, Pending>();
    private final Map<String, Consumed> consumedByNonce = new HashMap<String, Consumed>();

    public InventorySelectionManager(Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        ttlMillis = Math.max(1L, ttl.toMillis());
    }

    public synchronized Pending create(String groupId, String userId, List<PlayerBinding> bindings) {
        requireNonBlank(groupId, "groupId");
        requireNonBlank(userId, "userId");
        Objects.requireNonNull(bindings, "bindings");
        if (bindings.size() < 2) throw new IllegalArgumentException("Multiple bindings are required");
        long now = System.currentTimeMillis();
        cleanup(now);
        List<Option> options = new ArrayList<Option>(bindings.size());
        for (PlayerBinding binding : bindings) options.add(new Option(binding));
        Pending pending = new Pending(
            now + ttlMillis,
            UUID.randomUUID().toString().replace("-", ""),
            options
        );
        pendingByOwner.put(ownerKey(groupId, userId), pending);
        return pending;
    }

    public synchronized Result consumeText(String groupId, String userId, int selection) {
        String key = ownerKey(groupId, userId);
        Pending pending = pendingByOwner.get(key);
        if (pending == null) return Result.expired();
        if (pending.expiresAt <= System.currentTimeMillis()) {
            pendingByOwner.remove(key);
            return Result.expired();
        }
        if (selection < 1 || selection > pending.options.size()) return Result.invalid();
        pendingByOwner.remove(key);
        return Result.selected(pending.options.get(selection - 1));
    }

    public synchronized Result consumeButton(
        String groupId,
        String userId,
        String nonce,
        int selection
    ) {
        String key = ownerKey(groupId, userId);
        Pending pending = pendingByOwner.get(key);
        long now = System.currentTimeMillis();
        if (pending == null || !pending.nonce.equals(nonce)) {
            Consumed consumed = consumedByNonce.get(nonce);
            if (consumed != null) {
                if (consumed.expiresAt <= now) {
                    consumedByNonce.remove(nonce);
                    return Result.expired();
                }
                return consumed.matches(groupId, userId) ? Result.duplicate() : Result.forbidden();
            }
            for (Pending candidate : pendingByOwner.values()) {
                if (candidate.expiresAt > now && candidate.nonce.equals(nonce)) {
                    return Result.forbidden();
                }
            }
            return Result.expired();
        }
        if (pending.expiresAt <= now) {
            pendingByOwner.remove(key);
            return Result.expired();
        }
        if (selection < 1 || selection > pending.options.size()) return Result.invalid();
        pendingByOwner.remove(key);
        consumedByNonce.put(nonce, new Consumed(groupId, userId, pending.expiresAt));
        cleanup(now);
        return Result.selected(pending.options.get(selection - 1));
    }

    private void cleanup(long now) {
        Iterator<Map.Entry<String, Pending>> pending = pendingByOwner.entrySet().iterator();
        while (pending.hasNext()) if (pending.next().getValue().expiresAt <= now) pending.remove();
        Iterator<Map.Entry<String, Consumed>> consumed = consumedByNonce.entrySet().iterator();
        while (consumed.hasNext()) if (consumed.next().getValue().expiresAt <= now) consumed.remove();
    }

    private static String ownerKey(String groupId, String userId) {
        requireNonBlank(groupId, "groupId");
        requireNonBlank(userId, "userId");
        return groupId.trim() + "\n" + userId.trim();
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
    }

    public enum Status { SELECTED, INVALID, EXPIRED, DUPLICATE, FORBIDDEN }

    public static final class Pending {
        private final long expiresAt;
        private final String nonce;
        private final List<Option> options;

        private Pending(long expiresAt, String nonce, List<Option> options) {
            this.expiresAt = expiresAt;
            this.nonce = nonce;
            this.options = Collections.unmodifiableList(new ArrayList<Option>(options));
        }

        public long getExpiresAt() { return expiresAt; }
        public String getNonce() { return nonce; }
        public List<Option> getOptions() { return options; }
    }

    public static final class Option {
        private final String bindingId;
        private final int slot;
        private final boolean primary;
        private final String playerName;

        private Option(PlayerBinding binding) {
            bindingId = binding.getBindingId().orElse(null);
            slot = binding.getSlot();
            primary = binding.isPrimary();
            playerName = binding.getPlayerName();
        }

        public int getSlot() { return slot; }
        public boolean isPrimary() { return primary; }
        public String getPlayerName() { return playerName; }

        public boolean matches(PlayerBinding binding) {
            if (bindingId != null) return bindingId.equals(binding.getBindingId().orElse(null));
            return playerName.equalsIgnoreCase(binding.getPlayerName());
        }
    }

    public static final class Result {
        private final Status status;
        private final Option option;

        private Result(Status status, Option option) {
            this.status = status;
            this.option = option;
        }

        public Status getStatus() { return status; }
        public Option getOption() { return option; }

        private static Result selected(Option option) { return new Result(Status.SELECTED, option); }
        private static Result invalid() { return new Result(Status.INVALID, null); }
        private static Result expired() { return new Result(Status.EXPIRED, null); }
        private static Result duplicate() { return new Result(Status.DUPLICATE, null); }
        private static Result forbidden() { return new Result(Status.FORBIDDEN, null); }
    }

    private static final class Consumed {
        private final String groupId;
        private final String userId;
        private final long expiresAt;

        private Consumed(String groupId, String userId, long expiresAt) {
            this.groupId = groupId;
            this.userId = userId;
            this.expiresAt = expiresAt;
        }

        private boolean matches(String candidateGroupId, String candidateUserId) {
            return groupId.equals(candidateGroupId) && userId.equals(candidateUserId);
        }
    }
}
