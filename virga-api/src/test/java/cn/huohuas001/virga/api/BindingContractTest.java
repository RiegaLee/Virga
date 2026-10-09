package cn.huohuas001.virga.api;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BindingContractTest {
    @Test
    void exposesImmutableLegacyAndVerifiedSnapshots() {
        PlayerBinding legacy = new PlayerBinding("Steve", null, BindingVerificationState.LEGACY_UNVERIFIED);
        assertEquals("Steve", legacy.getPlayerName());
        assertFalse(legacy.getPlayerUuid().isPresent());
        assertEquals(BindingVerificationState.LEGACY_UNVERIFIED, legacy.getVerificationState());

        UUID uuid = UUID.randomUUID();
        PlayerBinding verified = new PlayerBinding("Alex", uuid, BindingVerificationState.VERIFIED);
        assertEquals(Optional.of(uuid), verified.getPlayerUuid());
        assertThrows(IllegalArgumentException.class, () ->
            new PlayerBinding(" ", null, BindingVerificationState.LEGACY_UNVERIFIED)
        );
    }

    @Test
    void unavailableLookupNeverInventsABinding() {
        assertFalse(BindingService.UNAVAILABLE.findBinding("group", "user").isPresent());
        assertTrue(BindingService.UNAVAILABLE.findBindings("group", "user").isEmpty());
    }

    @Test
    void legacyProvidersAutomaticallyExposeTheirSingleBindingAsAList() {
        PlayerBinding value = new PlayerBinding("Steve", null, BindingVerificationState.VERIFIED);
        BindingService legacy = (group, user) -> Optional.of(value);

        List<PlayerBinding> values = legacy.findBindings("group", "user");
        assertEquals(1, values.size());
        assertEquals("Steve", values.get(0).getPlayerName());
    }

    @Test
    void multiAccountMetadataDoesNotChangeLegacyFields() {
        PlayerBinding value = new PlayerBinding(
            "binding-1", 2, false, "steve", "Steve", UUID.randomUUID(),
            BindingVerificationState.VERIFIED, BindingVerificationSource.IN_GAME_CHALLENGE,
            null, null
        );

        assertEquals(Optional.of("binding-1"), value.getBindingId());
        assertEquals(2, value.getSlot());
        assertFalse(value.isPrimary());
        assertEquals(Optional.of("steve"), value.getAccountKey());
        assertEquals("Steve", value.getPlayerName());
    }
}
