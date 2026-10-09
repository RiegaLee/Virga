package cn.huohuas001.virga.server.binding;

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

/** Keeps short-lived, one-time, user-scoped account choices for QQ unbind buttons. */
final class UnbindSelectionManager {
    private final long ttlMillis;
    private final Map<String, Pending> pendingByOwner = new HashMap<String, Pending>();
    private final Map<String, Consumed> consumedByNonce = new HashMap<String, Consumed>();

    UnbindSelectionManager(Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        this.ttlMillis = Math.max(1L, ttl.toMillis());
    }

    synchronized Pending create(String groupId, String userId, List<PlayerBinding> bindings) {
        return create(groupId, userId, userId, bindings);
    }

    synchronized Pending create(
        String groupId,
        String ownerUserId,
        String targetUserId,
        List<PlayerBinding> bindings
    ) {
        requireNonBlank(groupId, "groupId");
        requireNonBlank(ownerUserId, "ownerUserId");
        requireNonBlank(targetUserId, "targetUserId");
        Objects.requireNonNull(bindings, "bindings");
        if (bindings.isEmpty()) throw new IllegalArgumentException("At least one binding is required");
        long now = System.currentTimeMillis();
        cleanup(now);
        List<Option> options = new ArrayList<Option>(bindings.size());
        for (PlayerBinding binding : bindings) options.add(new Option(binding));
        Pending pending = new Pending(
            now + ttlMillis,
            UUID.randomUUID().toString().replace("-", ""),
            targetUserId,
            options
        );
        pendingByOwner.put(ownerKey(groupId, ownerUserId), pending);
        return pending;
    }

    synchronized Result consume(
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
            for (Map.Entry<String, Pending> entry : pendingByOwner.entrySet()) {
                Pending candidate = entry.getValue();
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
        return Result.selected(pending.targetUserId, pending.options.get(selection - 1));
    }

    synchronized void invalidate(String groupId, String userId) {
        if (blank(groupId) || blank(userId)) return;
        pendingByOwner.remove(ownerKey(groupId, userId));
    }

    private void cleanup(long now) {
        Iterator<Map.Entry<String, Pending>> pending = pendingByOwner.entrySet().iterator();
        while (pending.hasNext()) if (pending.next().getValue().expiresAt <= now) pending.remove();
        Iterator<Map.Entry<String, Consumed>> consumed = consumedByNonce.entrySet().iterator();
        while (consumed.hasNext()) if (consumed.next().getValue().expiresAt <= now) consumed.remove();
    }

    private static String ownerKey(String groupId, String userId) { return groupId + "\n" + userId; }

    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }

    private static void requireNonBlank(String value, String name) {
        if (blank(value)) throw new IllegalArgumentException(name + " must not be blank");
    }

    static final class Pending {
        final long expiresAt;
        final String nonce;
        final String targetUserId;
        final List<Option> options;

        Pending(long expiresAt, String nonce, String targetUserId, List<Option> options) {
            this.expiresAt = expiresAt;
            this.nonce = nonce;
            this.targetUserId = targetUserId;
            this.options = Collections.unmodifiableList(new ArrayList<Option>(options));
        }
    }

    static final class Option {
        final String bindingId;
        final int slot;
        final boolean primary;
        final String playerName;

        Option(PlayerBinding binding) {
            this.bindingId = binding.getBindingId().orElse(null);
            this.slot = binding.getSlot();
            this.primary = binding.isPrimary();
            this.playerName = binding.getPlayerName();
        }

        boolean matches(PlayerBinding binding) {
            if (bindingId != null) return bindingId.equals(binding.getBindingId().orElse(null));
            return playerName.equalsIgnoreCase(binding.getPlayerName());
        }
    }

    enum Status { SELECTED, INVALID, EXPIRED, DUPLICATE, FORBIDDEN }

    static final class Result {
        final Status status;
        final String targetUserId;
        final Option option;

        private Result(Status status, String targetUserId, Option option) {
            this.status = status;
            this.targetUserId = targetUserId;
            this.option = option;
        }

        static Result selected(String targetUserId, Option option) {
            return new Result(Status.SELECTED, targetUserId, option);
        }
        static Result invalid() { return new Result(Status.INVALID, null, null); }
        static Result expired() { return new Result(Status.EXPIRED, null, null); }
        static Result duplicate() { return new Result(Status.DUPLICATE, null, null); }
        static Result forbidden() { return new Result(Status.FORBIDDEN, null, null); }
    }

    private static final class Consumed {
        final String groupId;
        final String userId;
        final long expiresAt;

        Consumed(String groupId, String userId, long expiresAt) {
            this.groupId = groupId;
            this.userId = userId;
            this.expiresAt = expiresAt;
        }

        boolean matches(String candidateGroupId, String candidateUserId) {
            return groupId.equals(candidateGroupId) && userId.equals(candidateUserId);
        }
    }
}
