package cn.huohuas001.virga.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BindingVerificationContractTest {
    @Test
    void challengeCarriesGroupUserTargetExpiryAndAttempts() {
        Instant created = Instant.parse("2026-08-24T00:00:00Z");
        BindingChallenge challenge = new BindingChallenge(
            "challenge-1", "ABC123", "group", "user", "Steve",
            created, created.plusSeconds(300), 5, BindingChallengeState.ACTIVE
        );

        BindingChallengeResult result = BindingChallengeResult.created(challenge);
        assertEquals(BindingChallengeResult.Status.CREATED, result.getStatus());
        assertEquals("group", result.getChallenge().get().getGroupOpenId());
        assertEquals("user", result.getChallenge().get().getQqOpenId());
        assertEquals("Steve", result.getChallenge().get().getTargetPlayerName());
        assertEquals(5, result.getChallenge().get().getRemainingAttempts());
    }

    @Test
    void verifiedSnapshotExposesOfflineServerObservationMetadata() {
        UUID uuid = UUID.randomUUID();
        Instant verifiedAt = Instant.parse("2026-08-24T00:00:00Z");
        PlayerBinding binding = new PlayerBinding(
            "Steve", uuid, BindingVerificationState.VERIFIED,
            BindingVerificationSource.IN_GAME_CHALLENGE, verifiedAt, verifiedAt
        );

        assertEquals("Steve", binding.getLastKnownName());
        assertEquals(uuid, binding.getObservedUuid().get());
        assertEquals(BindingVerificationSource.IN_GAME_CHALLENGE, binding.getVerificationSource().get());
        assertEquals(verifiedAt, binding.getVerifiedAt().get());
    }

    @Test
    void unavailableVerificationNeverCreatesOrConfirms() {
        BindingChallengeResult created = BindingVerificationService.UNAVAILABLE.createChallenge(
            new BindingChallengeRequest("group", "user", "Steve")
        );
        BindingVerificationResult confirmed = BindingVerificationService.UNAVAILABLE.confirmChallenge(
            new BindingConfirmation("ABC123", "Steve", UUID.randomUUID())
        );

        assertEquals(BindingChallengeResult.Status.UNAVAILABLE, created.getStatus());
        assertFalse(created.getChallenge().isPresent());
        assertEquals(BindingVerificationResult.Status.UNAVAILABLE, confirmed.getStatus());
        assertFalse(confirmed.getBinding().isPresent());
        assertTrue(BindingVerificationState.valueOf("IDENTITY_CHANGED") != null);
        assertTrue(BindingVerificationState.valueOf("REVOKED") != null);
    }
}
