package cn.huohuas001.virga.features.binding;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BindingAvailabilityTest {
    @Test void requiredAuthMeFailsClosedWhenMissing() {
        assertFalse(BindingAvailability.isAvailable(true, true, false));
        assertTrue(BindingAvailability.isAvailable(true, true, true));
    }

    @Test void explicitlyOptionalAuthMeAllowsPrivateOfflineMode() {
        assertTrue(BindingAvailability.isAvailable(true, false, false));
        assertFalse(BindingAvailability.isAvailable(false, false, true));
    }
}
