package cn.huohuas001.virga.server.binding;

import cn.huohuas001.virga.api.BindingChallenge;
import cn.huohuas001.virga.api.BindingChallengeRequest;
import cn.huohuas001.virga.api.BindingChallengeResult;
import cn.huohuas001.virga.api.BindingChallengeState;
import cn.huohuas001.virga.api.BindingConfirmation;
import cn.huohuas001.virga.api.BindingService;
import cn.huohuas001.virga.api.BindingVerificationResult;
import cn.huohuas001.virga.api.BindingVerificationService;
import cn.huohuas001.virga.api.BindingVerificationSource;
import cn.huohuas001.virga.api.BindingVerificationState;
import cn.huohuas001.virga.api.PlayerBinding;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Independent binding authority owned by the binding feature, not by Core or Inventory. */
final class StandaloneBindingAuthority implements BindingService, BindingVerificationService {
    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Comparator<StoredBinding> DISPLAY_ORDER =
        Comparator.comparingInt((StoredBinding value) -> value.primary ? 0 : 1)
            .thenComparingInt(value -> value.slot)
            .thenComparing(value -> value.playerName.toLowerCase(Locale.ROOT));

    private final File file;
    private final Duration challengeTtl;
    private final Duration createCooldown;
    private final int maxAttempts;
    private final int maxAccounts;
    private final Logger logger;
    private java.util.function.BiFunction<String, String, String> unionLookup = (group, user) -> null;
    private final SecureRandom random = new SecureRandom();
    private final Map<BindingKey, List<StoredBinding>> bindings =
        new HashMap<BindingKey, List<StoredBinding>>();
    private Map<BindingKey, List<StoredBinding>> lastPersistedBindings =
        new HashMap<BindingKey, List<StoredBinding>>();
    private final Map<String, PendingChallenge> challenges = new HashMap<String, PendingChallenge>();
    private final Map<String, PendingGameChallenge> gameChallenges =
        new HashMap<String, PendingGameChallenge>();
    private final Map<String, PendingGameBinding> pendingGameBindings =
        new HashMap<String, PendingGameBinding>();
    private final Map<BindingKey, Instant> lastChallengeAt = new HashMap<BindingKey, Instant>();
    private final Map<BindingKey, Integer> failedGameClaims = new HashMap<BindingKey, Integer>();
    private final Map<BindingKey, Instant> gameClaimBlockedUntil = new HashMap<BindingKey, Instant>();

    StandaloneBindingAuthority(
        File file,
        Duration challengeTtl,
        Duration createCooldown,
        int maxAttempts,
        Logger logger
    ) {
        this(file, challengeTtl, createCooldown, maxAttempts, 2, logger);
    }

    StandaloneBindingAuthority(
        File file,
        Duration challengeTtl,
        Duration createCooldown,
        int maxAttempts,
        int maxAccounts,
        Logger logger
    ) {
        this.file = Objects.requireNonNull(file, "file");
        this.challengeTtl = Objects.requireNonNull(challengeTtl, "challengeTtl");
        this.createCooldown = Objects.requireNonNull(createCooldown, "createCooldown");
        this.maxAttempts = Math.max(1, maxAttempts);
        this.maxAccounts = Math.max(1, maxAccounts);
        this.logger = Objects.requireNonNull(logger, "logger");
        load();
    }

    /** API 1.2 compatibility: the primary account remains the single lookup result. */
    synchronized void configureUnionLookup(java.util.function.BiFunction<String, String, String> lookup) {
        unionLookup = Objects.requireNonNull(lookup, "lookup");
    }

    @Override
    public synchronized Optional<PlayerBinding> findBinding(String groupId, String userId) {
        List<PlayerBinding> values = findBindings(groupId, userId);
        return values.isEmpty() ? Optional.<PlayerBinding>empty() : Optional.of(values.get(0));
    }

    @Override
    public synchronized List<PlayerBinding> findBindings(String groupId, String userId) {
        if (blank(groupId) || blank(userId)) return Collections.emptyList();
        List<StoredBinding> stored = bindings.get(new BindingKey(groupId, userId));
        if (stored == null || stored.isEmpty()) return Collections.emptyList();
        List<StoredBinding> ordered = new ArrayList<StoredBinding>();
        for (StoredBinding value : stored) {
            if (value.state != BindingVerificationState.REVOKED) ordered.add(value);
        }
        ordered.sort(DISPLAY_ORDER);
        List<PlayerBinding> result = new ArrayList<PlayerBinding>(ordered.size());
        for (StoredBinding value : ordered) result.add(value.toApi());
        return Collections.unmodifiableList(result);
    }

    /**
     * Reverse lookup used by ROOT diagnostics. Only verified records in the requested group are
     * returned; identity-changed or legacy unverified records must never be presented as the
     * current QQ owner.
     */
    synchronized List<BindingOwner> findVerifiedOwners(String groupId, String playerName) {
        if (blank(groupId) || blank(playerName)) return Collections.emptyList();
        String normalizedPlayer = playerName.trim();
        if (!PLAYER_NAME.matcher(normalizedPlayer).matches()) return Collections.emptyList();
        String account = accountKey(normalizedPlayer);
        List<BindingOwner> result = new ArrayList<BindingOwner>();
        for (Map.Entry<BindingKey, List<StoredBinding>> entry : bindings.entrySet()) {
            if (!entry.getKey().groupId.equals(groupId.trim())) continue;
            for (StoredBinding value : entry.getValue()) {
                if (value.state == BindingVerificationState.VERIFIED && value.accountKey.equals(account)) {
                    result.add(new BindingOwner(entry.getKey().userId, value.toApi()));
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    /** Issues or reuses a short-lived code only after the caller has authenticated the player. */
    synchronized GameCodeIssue issueGameCode(String playerName, UUID observedUuid) {
        if (blank(playerName) || !PLAYER_NAME.matcher(playerName).matches() || observedUuid == null) {
            return GameCodeIssue.of(GameCodeIssue.Status.INVALID_PLAYER);
        }
        cleanupExpired();
        if (hasVerifiedBinding(playerName)) {
            return GameCodeIssue.of(GameCodeIssue.Status.ALREADY_BOUND);
        }
        String account = accountKey(playerName);
        for (PendingGameBinding pending : pendingGameBindings.values()) {
            if (pending.accountKey.equals(account)) {
                return GameCodeIssue.of(GameCodeIssue.Status.CONFIRMATION_PENDING);
            }
        }
        for (PendingGameChallenge pending : gameChallenges.values()) {
            if (pending.accountKey.equals(account)) {
                pending.playerName = playerName;
                pending.observedUuid = observedUuid;
                return GameCodeIssue.code(pending.code, pending.expiresAt, true);
            }
        }

        return createGameCode(account, playerName, observedUuid, false);
    }

    /** Explicit /authcode requests always invalidate the previous bearer token and issue a new one. */
    synchronized GameCodeIssue rotateGameCode(String playerName, UUID observedUuid) {
        if (blank(playerName) || !PLAYER_NAME.matcher(playerName).matches() || observedUuid == null) {
            return GameCodeIssue.of(GameCodeIssue.Status.INVALID_PLAYER);
        }
        cleanupExpired();
        if (hasVerifiedBinding(playerName)) return GameCodeIssue.of(GameCodeIssue.Status.ALREADY_BOUND);
        String account = accountKey(playerName);
        removePendingGameBindingsForAccount(account);
        removeGameCodesForAccount(account);
        return createGameCode(account, playerName, observedUuid, false);
    }

    /** Issued only after game authentication. The disconnected player cannot click a game button. */
    synchronized GameCodeIssue issueForceGameCode(String playerName, UUID observedUuid) {
        if (blank(playerName) || !PLAYER_NAME.matcher(playerName).matches() || observedUuid == null) {
            return GameCodeIssue.of(GameCodeIssue.Status.INVALID_PLAYER);
        }
        String account = accountKey(playerName);
        String oldCode = gameChallenges.values().stream().filter(value -> value.accountKey.equals(account))
            .map(value -> value.code).findFirst().orElse(null);
        cleanupExpired();
        if (hasVerifiedBinding(playerName)) return GameCodeIssue.of(GameCodeIssue.Status.ALREADY_BOUND);
        removePendingGameBindingsForAccount(account);
        removeGameCodesForAccount(account);
        GameCodeIssue result = createGameCode(account, playerName, observedUuid, false, oldCode);
        gameChallenges.get(result.code).forceOnly = true;
        return result;
    }

    synchronized boolean isForceGameCode(String code) {
        PendingGameChallenge value = gameChallenges.get(code);
        return value != null && value.forceOnly;
    }

    synchronized void revokeForceGameCode(String code) {
        PendingGameChallenge token = gameChallenges.get(code);
        if (token != null && token.forceOnly) gameChallenges.remove(code);
    }

    /** Same monitor and quota gates as ordinary claims; never upgrades a normal token to bearer-only. */
    synchronized GameCodeClaim claimForceGameCode(String group, String user, String code) {
        PendingGameChallenge token = gameChallenges.get(code);
        if (token == null || !token.forceOnly) return GameCodeClaim.of(GameCodeClaim.Status.INVALID_CODE);
        try {
            if (hasVerifiedBinding(token.playerName)) return GameCodeClaim.of(GameCodeClaim.Status.ALREADY_BOUND);
            GameCodeReservation reservation = reserveGameCode(group, user, code, challengeTtl, true);
            if (reservation.status != GameCodeReservation.Status.CONFIRMATION_REQUIRED) {
                if (reservation.status == GameCodeReservation.Status.INVALID_CODE) return GameCodeClaim.invalid(reservation.remainingAttempts);
                if (reservation.status == GameCodeReservation.Status.RATE_LIMITED) return GameCodeClaim.rateLimited(reservation.retryAfterSeconds);
                return GameCodeClaim.of(GameCodeClaim.Status.valueOf(reservation.status.name()));
            }
            return confirmReservedGameCode(reservation.confirmationId, token.observedUuid);
        } finally {
            // A submitted bearer token is public, even when quota/conflict/storage checks fail.
            gameChallenges.remove(code);
            pendingGameBindings.values().removeIf(pending -> pending.sourceCode.equals(code));
        }
    }

    /**
     * Atomically consumes a valid QQ claim without creating a binding. The opaque confirmation
     * id can only be finalized by the exact in-game UUID that received the code.
     */
    synchronized GameCodeReservation reserveGameCode(
        String groupId,
        String userId,
        String code,
        Duration confirmationTtl
    ) {
        return reserveGameCode(groupId, userId, code, confirmationTtl, false);
    }

    private GameCodeReservation reserveGameCode(
        String groupId, String userId, String code, Duration confirmationTtl, boolean forceClaim
    ) {
        if (blank(groupId) || blank(userId) || code == null || !code.matches("[0-9]{6}")) {
            return GameCodeReservation.of(GameCodeReservation.Status.INVALID_CODE);
        }
        BindingKey key = new BindingKey(groupId, userId);
        Instant now = Instant.now();
        PendingGameChallenge pending = gameChallenges.get(code);
        Instant blockedUntil = gameClaimBlockedUntil.get(key);
        if (blockedUntil != null && blockedUntil.isAfter(now)) {
            return GameCodeReservation.rateLimited(
                BindingTimeDisplay.remainingSeconds(now, blockedUntil)
            );
        }
        if (pending == null || pending.forceOnly != forceClaim) {
            return GameCodeReservation.fromClaim(recordInvalidGameClaim(key));
        }
        if (now.isAfter(pending.expiresAt)) {
            gameChallenges.remove(code);
            return GameCodeReservation.of(GameCodeReservation.Status.EXPIRED);
        }

        List<StoredBinding> own = activeBindings(key);
        StoredBinding existingOwn = findByName(own, pending.playerName);
        if (existingOwn != null && existingOwn.state == BindingVerificationState.VERIFIED) {
            gameChallenges.remove(code);
            clearGameClaimFailures(key);
            return GameCodeReservation.of(GameCodeReservation.Status.ALREADY_BOUND);
        }
        if (existingOwn == null && quotaReached(key, pending.playerName, own)) {
            return GameCodeReservation.of(GameCodeReservation.Status.ACCOUNT_LIMIT_REACHED);
        }
        for (Map.Entry<BindingKey, List<StoredBinding>> entry : bindings.entrySet()) {
            if (entry.getKey().equals(key) || !entry.getKey().groupId.equals(key.groupId)) continue;
            if (findByName(active(entry.getValue()), pending.playerName) != null) {
                return GameCodeReservation.of(GameCodeReservation.Status.PLAYER_BOUND_TO_ANOTHER_USER);
            }
        }

        removePendingGameBindingsForAccount(pending.accountKey);
        String confirmationId = UUID.randomUUID().toString().replace("-", "");
        Duration safeTtl = confirmationTtl == null || confirmationTtl.isNegative() || confirmationTtl.isZero()
            ? Duration.ofSeconds(120L) : confirmationTtl;
        Instant confirmationExpiresAt = now.plus(safeTtl);
        if (confirmationExpiresAt.isAfter(pending.expiresAt)) confirmationExpiresAt = pending.expiresAt;
        PendingGameBinding reservation = new PendingGameBinding(
            confirmationId, code, key.groupId, key.userId, pending.accountKey,
            pending.playerName, pending.observedUuid, now, confirmationExpiresAt
        );
        gameChallenges.remove(code);
        pendingGameBindings.put(confirmationId, reservation);
        clearGameClaimFailures(key);
        return GameCodeReservation.pending(reservation);
    }

    /** Finalizes one reserved QQ claim after the matching player clicks the in-game button. */
    synchronized GameCodeClaim confirmReservedGameCode(String confirmationId, UUID observedUuid) {
        if (blank(confirmationId) || observedUuid == null) {
            return GameCodeClaim.of(GameCodeClaim.Status.INVALID_CODE);
        }
        PendingGameBinding pending = pendingGameBindings.get(confirmationId);
        if (pending == null || !pending.observedUuid.equals(observedUuid)) {
            return GameCodeClaim.of(GameCodeClaim.Status.INVALID_CODE);
        }
        Instant now = Instant.now();
        if (now.isAfter(pending.expiresAt)) {
            pendingGameBindings.remove(confirmationId);
            return GameCodeClaim.of(GameCodeClaim.Status.EXPIRED);
        }

        BindingKey key = new BindingKey(pending.groupId, pending.userId);
        List<StoredBinding> own = activeBindings(key);
        StoredBinding existingOwn = findByName(own, pending.playerName);
        if (existingOwn == null && quotaReached(key, pending.playerName, own)) {
            return GameCodeClaim.of(GameCodeClaim.Status.ACCOUNT_LIMIT_REACHED);
        }
        for (Map.Entry<BindingKey, List<StoredBinding>> entry : bindings.entrySet()) {
            if (entry.getKey().equals(key) || !entry.getKey().groupId.equals(key.groupId)) continue;
            if (findByName(active(entry.getValue()), pending.playerName) != null) {
                return GameCodeClaim.of(GameCodeClaim.Status.PLAYER_BOUND_TO_ANOTHER_USER);
            }
        }

        List<StoredBinding> records = bindings.computeIfAbsent(key, ignored -> new ArrayList<StoredBinding>());
        StoredBinding verified;
        if (existingOwn == null) {
            verified = new StoredBinding(
                UUID.randomUUID().toString(), lowestFreeSlot(records), records.isEmpty(),
                pending.accountKey, pending.playerName, pending.observedUuid,
                BindingVerificationState.VERIFIED, now, now
            );
            records.add(verified);
        } else {
            verified = new StoredBinding(
                existingOwn.bindingId, existingOwn.slot, existingOwn.primary,
                pending.accountKey, pending.playerName, pending.observedUuid,
                BindingVerificationState.VERIFIED, now, now
            );
            replace(records, existingOwn, verified);
        }
        normalizePrimary(records);
        save();
        pendingGameBindings.remove(confirmationId);
        return GameCodeClaim.verified(verified.toApi());
    }

    /** Rejects or times out a reserved claim and gives the player a fresh code. */
    synchronized GameCodeRotation cancelReservedGameCode(String confirmationId, UUID observedUuid) {
        if (blank(confirmationId) || observedUuid == null) return GameCodeRotation.notFound();
        PendingGameBinding pending = pendingGameBindings.get(confirmationId);
        if (pending == null || !pending.observedUuid.equals(observedUuid)) return GameCodeRotation.notFound();
        pendingGameBindings.remove(confirmationId);
        if (hasVerifiedBinding(pending.playerName)) {
            return GameCodeRotation.revoked(pending.playerName, pending.observedUuid);
        }
        removeGameCodesForAccount(pending.accountKey);
        GameCodeIssue replacement = createGameCode(
            pending.accountKey, pending.playerName, pending.observedUuid, false, pending.sourceCode
        );
        return GameCodeRotation.rotated(pending.playerName, pending.observedUuid, replacement);
    }

    /** Read-only ingress probe; unknown six-digit chat must remain ordinary chat. */
    synchronized boolean isActiveGameCode(String code) {
        if (code == null || !code.matches("[0-9]{6}")) return false;
        PendingGameChallenge pending = gameChallenges.get(code);
        return pending != null && !Instant.now().isAfter(pending.expiresAt);
    }

    /** Replaces a publicly exposed active token without requiring another game command. */
    synchronized GameCodeRotation rotateExposedGameCode(String exposedCode) {
        if (exposedCode == null) return GameCodeRotation.notFound();
        PendingGameChallenge exposed = gameChallenges.remove(exposedCode);
        if (exposed == null) return GameCodeRotation.notFound();
        if (hasVerifiedBinding(exposed.playerName)) {
            return GameCodeRotation.revoked(exposed.playerName, exposed.observedUuid);
        }
        removeGameCodesForAccount(exposed.accountKey);
        GameCodeIssue replacement = createGameCode(
            exposed.accountKey, exposed.playerName, exposed.observedUuid, false, exposedCode
        );
        gameChallenges.get(replacement.code).forceOnly = exposed.forceOnly;
        return GameCodeRotation.rotated(exposed.playerName, exposed.observedUuid, replacement);
    }

    private GameCodeIssue createGameCode(
        String account,
        String playerName,
        UUID observedUuid,
        boolean reused
    ) {
        return createGameCode(account, playerName, observedUuid, reused, null);
    }

    private GameCodeIssue createGameCode(
        String account,
        String playerName,
        UUID observedUuid,
        boolean reused,
        String forbiddenCode
    ) {
        Instant now = Instant.now();
        String code = uniqueCode(forbiddenCode);
        PendingGameChallenge pending = new PendingGameChallenge(
            code, account, playerName, observedUuid, now, now.plus(challengeTtl)
        );
        gameChallenges.put(code, pending);
        return GameCodeIssue.code(code, pending.expiresAt, reused);
    }

    private void removeGameCodesForAccount(String account) {
        Iterator<Map.Entry<String, PendingGameChallenge>> iterator = gameChallenges.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().accountKey.equals(account)) iterator.remove();
        }
    }

    private void removePendingGameBindingsForAccount(String account) {
        Iterator<Map.Entry<String, PendingGameBinding>> iterator = pendingGameBindings.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().accountKey.equals(account)) iterator.remove();
        }
    }

    private GameCodeClaim recordInvalidGameClaim(BindingKey key) {
        int failures = failedGameClaims.getOrDefault(key, 0) + 1;
        if (failures >= maxAttempts) {
            failedGameClaims.remove(key);
            long lockSeconds = Math.max(10L, createCooldown.getSeconds());
            gameClaimBlockedUntil.put(key, Instant.now().plusSeconds(lockSeconds));
            return GameCodeClaim.rateLimited(lockSeconds);
        }
        failedGameClaims.put(key, failures);
        return GameCodeClaim.invalid(maxAttempts - failures);
    }

    private void clearGameClaimFailures(BindingKey key) {
        failedGameClaims.remove(key);
        gameClaimBlockedUntil.remove(key);
    }

    @Override
    public synchronized BindingChallengeResult createChallenge(BindingChallengeRequest request) {
        Objects.requireNonNull(request, "request");
        cleanupExpired();
        String playerName = request.getTargetPlayerName().trim();
        if (!PLAYER_NAME.matcher(playerName).matches()) {
            return BindingChallengeResult.of(BindingChallengeResult.Status.INVALID_REQUEST);
        }

        BindingKey key = new BindingKey(request.getGroupOpenId(), request.getQqOpenId());
        List<StoredBinding> own = activeBindings(key);
        StoredBinding existingOwn = findByName(own, playerName);
        if (existingOwn != null && existingOwn.state == BindingVerificationState.VERIFIED) {
            return BindingChallengeResult.of(BindingChallengeResult.Status.USER_ALREADY_VERIFIED);
        }
        if (existingOwn == null && quotaReached(key, playerName, own)) {
            return BindingChallengeResult.of(BindingChallengeResult.Status.ACCOUNT_LIMIT_REACHED);
        }
        for (Map.Entry<BindingKey, List<StoredBinding>> entry : bindings.entrySet()) {
            if (entry.getKey().equals(key) || !entry.getKey().groupId.equals(key.groupId)) continue;
            if (findByName(active(entry.getValue()), playerName) != null) {
                return BindingChallengeResult.of(BindingChallengeResult.Status.PLAYER_BOUND_TO_ANOTHER_USER);
            }
        }
        for (PendingChallenge active : challenges.values()) {
            if (active.groupId.equals(key.groupId) && active.playerName.equalsIgnoreCase(playerName)) {
                return BindingChallengeResult.of(BindingChallengeResult.Status.TARGET_CHALLENGE_ACTIVE);
            }
        }

        Instant now = Instant.now();
        Instant previous = lastChallengeAt.get(key);
        if (previous != null && previous.plus(createCooldown).isAfter(now)) {
            return BindingChallengeResult.rateLimited(
                BindingTimeDisplay.remainingSeconds(now, previous.plus(createCooldown))
            );
        }

        String code = uniqueCode();
        PendingChallenge pending = new PendingChallenge(
            UUID.randomUUID().toString(), code, key.groupId, key.userId, playerName,
            now, now.plus(challengeTtl), maxAttempts
        );
        challenges.put(code, pending);
        lastChallengeAt.put(key, now);
        return BindingChallengeResult.created(pending.toApi());
    }

    @Override
    public synchronized BindingVerificationResult confirmChallenge(BindingConfirmation confirmation) {
        Objects.requireNonNull(confirmation, "confirmation");
        PendingChallenge pending = challenges.get(confirmation.getCode());
        if (pending == null) {
            cleanupExpired();
            return BindingVerificationResult.rejected(BindingVerificationResult.Status.INVALID_CODE, 0);
        }
        if (Instant.now().isAfter(pending.expiresAt)) {
            challenges.remove(pending.code);
            return BindingVerificationResult.rejected(BindingVerificationResult.Status.EXPIRED, 0);
        }
        if (!pending.playerName.equalsIgnoreCase(confirmation.getObservedPlayerName())) {
            pending.remainingAttempts--;
            if (pending.remainingAttempts <= 0) {
                challenges.remove(pending.code);
                return BindingVerificationResult.rejected(BindingVerificationResult.Status.ATTEMPTS_EXHAUSTED, 0);
            }
            return BindingVerificationResult.rejected(
                BindingVerificationResult.Status.PLAYER_MISMATCH, pending.remainingAttempts
            );
        }

        BindingKey key = new BindingKey(pending.groupId, pending.userId);
        List<StoredBinding> own = activeBindings(key);
        StoredBinding existingOwn = findByName(own, pending.playerName);
        if (existingOwn == null && quotaReached(key, pending.playerName, own)) {
            return BindingVerificationResult.rejected(BindingVerificationResult.Status.CONFLICT, 0);
        }
        for (Map.Entry<BindingKey, List<StoredBinding>> entry : bindings.entrySet()) {
            if (entry.getKey().equals(key) || !entry.getKey().groupId.equals(key.groupId)) continue;
            if (findByName(active(entry.getValue()), pending.playerName) != null) {
                return BindingVerificationResult.rejected(BindingVerificationResult.Status.CONFLICT, 0);
            }
        }

        Instant now = Instant.now();
        List<StoredBinding> records = bindings.computeIfAbsent(key, ignored -> new ArrayList<StoredBinding>());
        StoredBinding verified;
        if (existingOwn == null) {
            verified = new StoredBinding(
                UUID.randomUUID().toString(), lowestFreeSlot(records), records.isEmpty(),
                accountKey(pending.playerName), pending.playerName, confirmation.getObservedUuid(),
                BindingVerificationState.VERIFIED, now, now
            );
            records.add(verified);
        } else {
            verified = new StoredBinding(
                existingOwn.bindingId, existingOwn.slot, existingOwn.primary,
                accountKey(pending.playerName), pending.playerName, confirmation.getObservedUuid(),
                BindingVerificationState.VERIFIED, now, now
            );
            replace(records, existingOwn, verified);
        }
        normalizePrimary(records);
        save();
        challenges.remove(pending.code);
        return BindingVerificationResult.verified(verified.toApi());
    }

    /** Compatibility operation used when callers intentionally remove every account. */
    synchronized boolean removeBinding(String groupId, String userId) {
        if (blank(groupId) || blank(userId)) return false;
        List<StoredBinding> removed = bindings.remove(new BindingKey(groupId, userId));
        if (removed == null || removed.isEmpty()) return false;
        save();
        return true;
    }

    synchronized boolean removeBinding(String groupId, String userId, String selector) {
        if (blank(groupId) || blank(userId) || blank(selector)) return false;
        BindingKey key = new BindingKey(groupId, userId);
        List<StoredBinding> records = bindings.get(key);
        if (records == null) return false;
        StoredBinding selected = select(records, selector);
        if (selected == null) return false;
        records.remove(selected);
        if (records.isEmpty()) bindings.remove(key);
        else {
            compactSlots(records);
            normalizePrimary(records);
        }
        save();
        return true;
    }

    synchronized boolean setPrimary(String groupId, String userId, String selector) {
        if (blank(groupId) || blank(userId) || blank(selector)) return false;
        List<StoredBinding> records = bindings.get(new BindingKey(groupId, userId));
        if (records == null) return false;
        StoredBinding selected = select(records, selector);
        if (selected == null || selected.state == BindingVerificationState.REVOKED) return false;
        for (StoredBinding record : records) record.primary = record == selected;
        save();
        return true;
    }

    synchronized boolean hasVerifiedBinding(String playerName) {
        if (blank(playerName)) return false;
        for (List<StoredBinding> records : bindings.values()) {
            StoredBinding value = findByName(records, playerName);
            if (value != null && value.state == BindingVerificationState.VERIFIED) return true;
        }
        return false;
    }

    synchronized boolean hasVerifiedBinding(String playerName, UUID uuid) {
        if (blank(playerName) || uuid == null) return false;
        for (List<StoredBinding> records : bindings.values()) {
            StoredBinding value = findByName(records, playerName);
            if (value != null && value.state == BindingVerificationState.VERIFIED && value.uuid.equals(uuid)) return true;
        }
        return false;
    }

    /** A fresh AuthMe registration must never inherit an older account owner's verified binding. */
    synchronized void requireReverification(String playerName) {
        if (blank(playerName)) return;
        boolean changed = false;
        String key = accountKey(playerName);
        for (List<StoredBinding> records : bindings.values()) {
            for (int index = 0; index < records.size(); index++) {
                StoredBinding binding = records.get(index);
                if (binding.state != BindingVerificationState.VERIFIED ||
                    !binding.accountKey.equals(key)) continue;
                records.set(index, new StoredBinding(
                    binding.bindingId, binding.slot, binding.primary, key, binding.playerName, binding.uuid,
                    BindingVerificationState.IDENTITY_CHANGED, binding.verifiedAt, binding.lastSeenAt
                ));
                changed = true;
            }
        }
        if (changed) save();
    }

    /** Called only after AuthMe reports a successful login for this account. */
    synchronized void observeAuthenticatedPlayer(String playerName, UUID observedUuid) {
        if (blank(playerName) || observedUuid == null) return;
        boolean changed = false;
        Instant now = Instant.now();
        String key = accountKey(playerName);
        for (List<StoredBinding> records : bindings.values()) {
            for (int index = 0; index < records.size(); index++) {
                StoredBinding binding = records.get(index);
                if (binding.state != BindingVerificationState.VERIFIED ||
                    !binding.accountKey.equals(key)) continue;
                records.set(index, new StoredBinding(
                    binding.bindingId, binding.slot, binding.primary, key, playerName, observedUuid,
                    BindingVerificationState.VERIFIED, binding.verifiedAt, now
                ));
                changed = true;
            }
        }
        if (changed) save();
    }

    /** Kept for source compatibility with the original tests and adapter. */
    synchronized void observePlayer(String playerName, UUID observedUuid) {
        observeAuthenticatedPlayer(playerName, observedUuid);
    }

    private void cleanupExpired() {
        Instant now = Instant.now();
        Iterator<Map.Entry<String, PendingChallenge>> iterator = challenges.entrySet().iterator();
        while (iterator.hasNext()) {
            if (now.isAfter(iterator.next().getValue().expiresAt)) iterator.remove();
        }
        Iterator<Map.Entry<String, PendingGameChallenge>> gameIterator = gameChallenges.entrySet().iterator();
        while (gameIterator.hasNext()) {
            if (now.isAfter(gameIterator.next().getValue().expiresAt)) gameIterator.remove();
        }
        Iterator<Map.Entry<String, PendingGameBinding>> pendingIterator = pendingGameBindings.entrySet().iterator();
        while (pendingIterator.hasNext()) {
            if (now.isAfter(pendingIterator.next().getValue().expiresAt)) pendingIterator.remove();
        }
    }

    private String uniqueCode() {
        return uniqueCode(null);
    }

    private String uniqueCode(String forbiddenCode) {
        String code;
        do {
            code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        } while (code.equals(forbiddenCode) || challenges.containsKey(code) || gameChallenges.containsKey(code));
        return code;
    }

    private void load() {
        bindings.clear();
        if (!file.isFile()) return;
        boolean migrated = false;
        Map<String, Object> yaml;
        try (java.io.InputStream input = Files.newInputStream(file.toPath())) {
            Object loaded = new Yaml().load(input);
            yaml = loaded instanceof Map ? castMap(loaded) : Collections.<String, Object>emptyMap();
        } catch (Exception error) {
            logger.warning("Ignored corrupt bindings file " + file + ": " + error.getMessage());
            rememberPersisted();
            return;
        }
        Map<String, Object> root = map(yaml.get("bindings"));
        if (root == null) return;
        for (Map.Entry<String, Object> rawEntry : root.entrySet()) {
            String encodedKey = rawEntry.getKey();
            Map<String, Object> section = map(rawEntry.getValue());
            if (section == null) continue;
            try {
                BindingKey key = BindingKey.decode(encodedKey);
                List<StoredBinding> records = new ArrayList<StoredBinding>();
                Map<String, Object> accounts = map(section.get("accounts"));
                if (accounts == null && section.containsKey("player-name")) {
                    records.add(readStored(section, UUID.randomUUID().toString(), 1, true));
                    migrated = true;
                } else if (accounts != null) {
                    for (Map.Entry<String, Object> accountEntry : accounts.entrySet()) {
                        String bindingId = accountEntry.getKey();
                        Map<String, Object> account = map(accountEntry.getValue());
                        if (account == null) continue;
                        records.add(readStored(
                            account, bindingId, integer(account.get("slot"), 0), bool(account.get("primary"), false)
                        ));
                    }
                }
                repairLoaded(records);
                if (!records.isEmpty()) bindings.put(key, records);
            } catch (RuntimeException error) {
                logger.warning("Skipped invalid binding record " + encodedKey + ": " + error.getMessage());
            }
        }
        if (migrated) {
            backupLegacyFile();
            save();
        } else {
            rememberPersisted();
        }
    }

    private void backupLegacyFile() {
        File backup = new File(file.getParentFile(), file.getName() + ".pre-1.2.0.bak");
        if (backup.isFile()) return;
        try {
            Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
        } catch (IOException error) {
            throw new IllegalStateException("Could not back up legacy bindings before migration", error);
        }
    }

    private StoredBinding readStored(
        Map<String, Object> section,
        String bindingId,
        int slot,
        boolean primary
    ) {
        String playerName = string(section.get("player-name"), "");
        if (!PLAYER_NAME.matcher(playerName).matches()) throw new IllegalArgumentException("bad player name");
        UUID uuid = UUID.fromString(string(section.get("player-uuid"), ""));
        BindingVerificationState state = BindingVerificationState.valueOf(
            string(section.get("state"), BindingVerificationState.VERIFIED.name())
        );
        Instant verifiedAt = Instant.ofEpochMilli(longValue(section.get("verified-at"), 0L));
        Instant lastSeenAt = Instant.ofEpochMilli(longValue(section.get("last-seen-at"), 0L));
        String storedAccountKey = string(section.get("account-key"), accountKey(playerName));
        return new StoredBinding(
            bindingId, slot, primary, storedAccountKey, playerName, uuid, state, verifiedAt, lastSeenAt
        );
    }

    private void repairLoaded(List<StoredBinding> records) {
        records.removeIf(value -> value.state == BindingVerificationState.REVOKED);
        records.sort(Comparator.comparingInt(value -> value.slot <= 0 ? Integer.MAX_VALUE : value.slot));
        List<StoredBinding> repaired = new ArrayList<StoredBinding>();
        for (StoredBinding value : records) {
            if (repaired.size() >= maxAccounts || findByName(repaired, value.playerName) != null) continue;
            repaired.add(new StoredBinding(
                value.bindingId, repaired.size() + 1, value.primary, value.accountKey, value.playerName, value.uuid,
                value.state, value.verifiedAt, value.lastSeenAt
            ));
        }
        records.clear();
        records.addAll(repaired);
        normalizePrimary(records);
    }

    private void save() {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        Map<String, Object> root = new java.util.LinkedHashMap<String, Object>();
        Map<String, Object> bindingRoot = new java.util.LinkedHashMap<String, Object>();
        root.put("bindings", bindingRoot);
        for (Map.Entry<BindingKey, List<StoredBinding>> entry : bindings.entrySet()) {
            Map<String, Object> section = new java.util.LinkedHashMap<String, Object>();
            Map<String, Object> accounts = new java.util.LinkedHashMap<String, Object>();
            section.put("accounts", accounts);
            bindingRoot.put(entry.getKey().encode(), section);
            for (StoredBinding binding : entry.getValue()) {
                Map<String, Object> account = new java.util.LinkedHashMap<String, Object>();
                account.put("slot", binding.slot);
                account.put("primary", binding.primary);
                account.put("account-key", binding.accountKey);
                account.put("player-name", binding.playerName);
                account.put("player-uuid", binding.uuid.toString());
                account.put("state", binding.state.name());
                account.put("verified-at", binding.verifiedAt.toEpochMilli());
                account.put("last-seen-at", binding.lastSeenAt.toEpochMilli());
                accounts.put(binding.bindingId, account);
            }
        }
        File temporary = null;
        try {
            temporary = File.createTempFile(file.getName() + "-", ".tmp", parent);
            Files.write(temporary.toPath(), new Yaml().dump(root).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
            rememberPersisted();
        } catch (IOException error) {
            restorePersisted();
            throw new IllegalStateException("Could not persist independent bindings", error);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary.toPath()); } catch (IOException ignored) {}
            }
        }
    }

    private void rememberPersisted() {
        lastPersistedBindings = copyBindings(bindings);
    }

    private void restorePersisted() {
        bindings.clear();
        bindings.putAll(copyBindings(lastPersistedBindings));
    }

    private static Map<BindingKey, List<StoredBinding>> copyBindings(
        Map<BindingKey, List<StoredBinding>> source
    ) {
        Map<BindingKey, List<StoredBinding>> copy = new HashMap<BindingKey, List<StoredBinding>>();
        for (Map.Entry<BindingKey, List<StoredBinding>> entry : source.entrySet()) {
            List<StoredBinding> records = new ArrayList<StoredBinding>();
            for (StoredBinding value : entry.getValue()) {
                records.add(new StoredBinding(
                    value.bindingId, value.slot, value.primary, value.accountKey, value.playerName,
                    value.uuid, value.state, value.verifiedAt, value.lastSeenAt
                ));
            }
            copy.put(entry.getKey(), records);
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) { return (Map<String, Object>) value; }
    private static Map<String, Object> map(Object value) { return value instanceof Map ? castMap(value) : null; }
    private static String string(Object value, String fallback) { return value == null ? fallback : value.toString(); }
    private static int integer(Object value, int fallback) { return value instanceof Number ? ((Number) value).intValue() : fallback; }
    private static long longValue(Object value, long fallback) { return value instanceof Number ? ((Number) value).longValue() : fallback; }
    private static boolean bool(Object value, boolean fallback) { return value instanceof Boolean ? ((Boolean) value).booleanValue() : fallback; }

    private List<StoredBinding> activeBindings(BindingKey key) {
        List<StoredBinding> values = bindings.get(key);
        return values == null ? Collections.<StoredBinding>emptyList() : active(values);
    }

    /** Same lock as reservation/confirmation; two groups cannot exceed one member/known-alias quota. */
    private boolean quotaReached(BindingKey key, String target, List<StoredBinding> own) {
        if (own.size() >= maxAccounts) return true;
        String union = unionLookup.apply(key.groupId, key.userId);
        java.util.Set<String> accounts = new java.util.HashSet<String>();
        for (Map.Entry<BindingKey, List<StoredBinding>> entry : bindings.entrySet()) {
            BindingKey alias = entry.getKey();
            // The same member ID in this bot is already one identity, even without the optional UnionOpenID.
            if (!key.userId.equals(alias.userId) &&
                (blank(union) || !union.equals(unionLookup.apply(alias.groupId, alias.userId)))) continue;
            for (StoredBinding value : active(entry.getValue())) accounts.add(value.accountKey);
        }
        return !accounts.contains(accountKey(target)) && accounts.size() >= maxAccounts;
    }

    private static List<StoredBinding> active(List<StoredBinding> values) {
        List<StoredBinding> result = new ArrayList<StoredBinding>();
        for (StoredBinding value : values) {
            if (value.state != BindingVerificationState.REVOKED) result.add(value);
        }
        return result;
    }

    private static StoredBinding findByName(List<StoredBinding> values, String playerName) {
        for (StoredBinding value : values) {
            if (value.state != BindingVerificationState.REVOKED &&
                value.playerName.equalsIgnoreCase(playerName)) return value;
        }
        return null;
    }

    private static StoredBinding select(List<StoredBinding> values, String selector) {
        String normalized = selector.trim();
        Integer slot = null;
        try {
            slot = Integer.valueOf(normalized);
        } catch (NumberFormatException ignored) {
            // A player name or binding id is also a valid selector.
        }
        for (StoredBinding value : values) {
            if (value.state == BindingVerificationState.REVOKED) continue;
            if ((slot != null && value.slot == slot.intValue()) ||
                value.playerName.equalsIgnoreCase(normalized) ||
                value.bindingId.equalsIgnoreCase(normalized)) return value;
        }
        return null;
    }

    private static int lowestFreeSlot(List<StoredBinding> values) {
        int candidate = 1;
        while (true) {
            boolean used = false;
            for (StoredBinding value : values) {
                if (value.state != BindingVerificationState.REVOKED && value.slot == candidate) {
                    used = true;
                    break;
                }
            }
            if (!used) return candidate;
            candidate++;
        }
    }

    /** Keeps the user-facing account numbers dense after one account is removed. */
    private static void compactSlots(List<StoredBinding> values) {
        values.sort(Comparator.comparingInt(value -> value.slot <= 0 ? Integer.MAX_VALUE : value.slot));
        for (int index = 0; index < values.size(); index++) {
            StoredBinding value = values.get(index);
            int slot = index + 1;
            if (value.slot == slot) continue;
            values.set(index, new StoredBinding(
                value.bindingId, slot, value.primary, value.accountKey, value.playerName, value.uuid,
                value.state, value.verifiedAt, value.lastSeenAt
            ));
        }
    }

    private static void normalizePrimary(List<StoredBinding> values) {
        StoredBinding selected = null;
        for (StoredBinding value : values) {
            if (value.state == BindingVerificationState.REVOKED) continue;
            if (selected == null || (value.primary && !selected.primary) ||
                (value.primary == selected.primary && value.slot < selected.slot)) selected = value;
        }
        for (StoredBinding value : values) value.primary = value == selected;
    }

    private static void replace(List<StoredBinding> values, StoredBinding oldValue, StoredBinding newValue) {
        int index = values.indexOf(oldValue);
        if (index < 0) values.add(newValue);
        else values.set(index, newValue);
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String accountKey(String playerName) {
        return playerName.trim().toLowerCase(Locale.ROOT);
    }

    private static final class BindingKey {
        final String groupId;
        final String userId;

        BindingKey(String groupId, String userId) {
            this.groupId = Objects.requireNonNull(groupId, "groupId").trim();
            this.userId = Objects.requireNonNull(userId, "userId").trim();
            if (this.groupId.isEmpty() || this.userId.isEmpty()) throw new IllegalArgumentException("blank key");
        }

        String encode() {
            String raw = groupId + "\n" + userId;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );
        }

        static BindingKey decode(String encoded) {
            String raw = new String(
                Base64.getUrlDecoder().decode(encoded), java.nio.charset.StandardCharsets.UTF_8
            );
            int split = raw.indexOf('\n');
            if (split <= 0 || split + 1 >= raw.length()) throw new IllegalArgumentException("bad key");
            return new BindingKey(raw.substring(0, split), raw.substring(split + 1));
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof BindingKey)) return false;
            BindingKey that = (BindingKey) other;
            return groupId.equals(that.groupId) && userId.equals(that.userId);
        }

        @Override public int hashCode() { return 31 * groupId.hashCode() + userId.hashCode(); }
    }

    private static final class StoredBinding {
        final String bindingId;
        final int slot;
        boolean primary;
        final String accountKey;
        final String playerName;
        final UUID uuid;
        final BindingVerificationState state;
        final Instant verifiedAt;
        final Instant lastSeenAt;

        StoredBinding(
            String bindingId,
            int slot,
            boolean primary,
            String accountKey,
            String playerName,
            UUID uuid,
            BindingVerificationState state,
            Instant verifiedAt,
            Instant lastSeenAt
        ) {
            this.bindingId = blank(bindingId) ? UUID.randomUUID().toString() : bindingId.trim();
            this.slot = slot;
            this.primary = primary;
            this.accountKey = blank(accountKey) ? accountKey(playerName) : accountKey.trim().toLowerCase(Locale.ROOT);
            this.playerName = playerName;
            this.uuid = Objects.requireNonNull(uuid, "uuid");
            this.state = Objects.requireNonNull(state, "state");
            this.verifiedAt = Objects.requireNonNull(verifiedAt, "verifiedAt");
            this.lastSeenAt = Objects.requireNonNull(lastSeenAt, "lastSeenAt");
        }

        PlayerBinding toApi() {
            return new PlayerBinding(
                bindingId, slot, primary, accountKey, playerName, uuid, state,
                BindingVerificationSource.IN_GAME_CHALLENGE, verifiedAt, lastSeenAt
            );
        }
    }

    static final class BindingOwner {
        final String userId;
        final PlayerBinding binding;

        BindingOwner(String userId, PlayerBinding binding) {
            this.userId = Objects.requireNonNull(userId, "userId");
            this.binding = Objects.requireNonNull(binding, "binding");
        }
    }

    private static final class PendingChallenge {
        final String id;
        final String code;
        final String groupId;
        final String userId;
        final String playerName;
        final Instant createdAt;
        final Instant expiresAt;
        int remainingAttempts;

        PendingChallenge(
            String id,
            String code,
            String groupId,
            String userId,
            String playerName,
            Instant createdAt,
            Instant expiresAt,
            int remainingAttempts
        ) {
            this.id = id;
            this.code = code;
            this.groupId = groupId;
            this.userId = userId;
            this.playerName = playerName;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
            this.remainingAttempts = remainingAttempts;
        }

        BindingChallenge toApi() {
            return new BindingChallenge(
                id, code, groupId, userId, playerName, createdAt, expiresAt,
                remainingAttempts, BindingChallengeState.ACTIVE
            );
        }
    }

    static final class GameCodeIssue {
        enum Status { CODE_AVAILABLE, CONFIRMATION_PENDING, ALREADY_BOUND, INVALID_PLAYER }

        final Status status;
        final String code;
        final Instant expiresAt;
        final boolean reused;

        private GameCodeIssue(Status status, String code, Instant expiresAt, boolean reused) {
            this.status = status;
            this.code = code;
            this.expiresAt = expiresAt;
            this.reused = reused;
        }

        static GameCodeIssue code(String code, Instant expiresAt, boolean reused) {
            return new GameCodeIssue(Status.CODE_AVAILABLE, code, expiresAt, reused);
        }

        static GameCodeIssue of(Status status) {
            return new GameCodeIssue(status, null, null, false);
        }
    }

    static final class GameCodeClaim {
        enum Status {
            VERIFIED,
            INVALID_CODE,
            EXPIRED,
            RATE_LIMITED,
            ALREADY_BOUND,
            ACCOUNT_LIMIT_REACHED,
            PLAYER_BOUND_TO_ANOTHER_USER
        }

        final Status status;
        final PlayerBinding binding;
        final int remainingAttempts;
        final long retryAfterSeconds;

        private GameCodeClaim(
            Status status,
            PlayerBinding binding,
            int remainingAttempts,
            long retryAfterSeconds
        ) {
            this.status = status;
            this.binding = binding;
            this.remainingAttempts = remainingAttempts;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        static GameCodeClaim verified(PlayerBinding binding) {
            return new GameCodeClaim(Status.VERIFIED, binding, 0, 0L);
        }

        static GameCodeClaim invalid(int remainingAttempts) {
            return new GameCodeClaim(Status.INVALID_CODE, null, Math.max(0, remainingAttempts), 0L);
        }

        static GameCodeClaim rateLimited(long retryAfterSeconds) {
            return new GameCodeClaim(Status.RATE_LIMITED, null, 0, Math.max(1L, retryAfterSeconds));
        }

        static GameCodeClaim of(Status status) {
            return new GameCodeClaim(status, null, 0, 0L);
        }
    }

    static final class GameCodeReservation {
        enum Status {
            CONFIRMATION_REQUIRED,
            INVALID_CODE,
            EXPIRED,
            RATE_LIMITED,
            ALREADY_BOUND,
            ACCOUNT_LIMIT_REACHED,
            PLAYER_BOUND_TO_ANOTHER_USER
        }

        final Status status;
        final String confirmationId;
        final String playerName;
        final UUID observedUuid;
        final Instant expiresAt;
        final int remainingAttempts;
        final long retryAfterSeconds;

        private GameCodeReservation(
            Status status,
            String confirmationId,
            String playerName,
            UUID observedUuid,
            Instant expiresAt,
            int remainingAttempts,
            long retryAfterSeconds
        ) {
            this.status = status;
            this.confirmationId = confirmationId;
            this.playerName = playerName;
            this.observedUuid = observedUuid;
            this.expiresAt = expiresAt;
            this.remainingAttempts = remainingAttempts;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        static GameCodeReservation pending(PendingGameBinding pending) {
            return new GameCodeReservation(
                Status.CONFIRMATION_REQUIRED, pending.id, pending.playerName, pending.observedUuid,
                pending.expiresAt, 0, 0L
            );
        }

        static GameCodeReservation rateLimited(long seconds) {
            return new GameCodeReservation(
                Status.RATE_LIMITED, null, null, null, null, 0, Math.max(1L, seconds)
            );
        }

        static GameCodeReservation of(Status status) {
            return new GameCodeReservation(status, null, null, null, null, 0, 0L);
        }

        static GameCodeReservation fromClaim(GameCodeClaim claim) {
            if (claim.status == GameCodeClaim.Status.RATE_LIMITED) return rateLimited(claim.retryAfterSeconds);
            if (claim.status == GameCodeClaim.Status.INVALID_CODE) {
                return new GameCodeReservation(
                    Status.INVALID_CODE, null, null, null, null, claim.remainingAttempts, 0L
                );
            }
            return of(Status.INVALID_CODE);
        }
    }

    static final class GameCodeRotation {
        enum Status { ROTATED, REVOKED, NOT_FOUND }

        final Status status;
        final String playerName;
        final UUID observedUuid;
        final GameCodeIssue replacement;

        private GameCodeRotation(
            Status status,
            String playerName,
            UUID observedUuid,
            GameCodeIssue replacement
        ) {
            this.status = status;
            this.playerName = playerName;
            this.observedUuid = observedUuid;
            this.replacement = replacement;
        }

        static GameCodeRotation rotated(String playerName, UUID observedUuid, GameCodeIssue replacement) {
            return new GameCodeRotation(Status.ROTATED, playerName, observedUuid, replacement);
        }

        static GameCodeRotation revoked(String playerName, UUID observedUuid) {
            return new GameCodeRotation(Status.REVOKED, playerName, observedUuid, null);
        }

        static GameCodeRotation notFound() {
            return new GameCodeRotation(Status.NOT_FOUND, null, null, null);
        }
    }

    private static final class PendingGameChallenge {
        final String code;
        final String accountKey;
        String playerName;
        UUID observedUuid;
        final Instant createdAt;
        final Instant expiresAt;
        boolean forceOnly;

        PendingGameChallenge(
            String code,
            String accountKey,
            String playerName,
            UUID observedUuid,
            Instant createdAt,
            Instant expiresAt
        ) {
            this.code = code;
            this.accountKey = accountKey;
            this.playerName = playerName;
            this.observedUuid = observedUuid;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }
    }

    private static final class PendingGameBinding {
        final String id;
        final String sourceCode;
        final String groupId;
        final String userId;
        final String accountKey;
        final String playerName;
        final UUID observedUuid;
        final Instant createdAt;
        final Instant expiresAt;

        PendingGameBinding(
            String id,
            String sourceCode,
            String groupId,
            String userId,
            String accountKey,
            String playerName,
            UUID observedUuid,
            Instant createdAt,
            Instant expiresAt
        ) {
            this.id = id;
            this.sourceCode = sourceCode;
            this.groupId = groupId;
            this.userId = userId;
            this.accountKey = accountKey;
            this.playerName = playerName;
            this.observedUuid = observedUuid;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }
    }
}
