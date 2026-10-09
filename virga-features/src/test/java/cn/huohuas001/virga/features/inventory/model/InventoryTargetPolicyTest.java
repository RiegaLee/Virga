package cn.huohuas001.virga.features.inventory.model;

import cn.huohuas001.virga.api.BindingVerificationState;
import cn.huohuas001.virga.api.PlayerBinding;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;

class InventoryTargetPolicyTest {
    @Test void memberCanOnlyReadOwnVerifiedBinding() {
        PlayerBinding own = binding("id-1", 1, "Steve", BindingVerificationState.VERIFIED);
        assertEquals(InventoryTargetPolicy.Status.OWN_VERIFIED,
            InventoryTargetPolicy.resolve(Collections.singletonList(own), "", false).getStatus());
        assertEquals(InventoryTargetPolicy.Status.DENIED,
            InventoryTargetPolicy.resolve(Collections.singletonList(own), "Alex", false).getStatus());
    }

    @Test void rootAndDynamicAdministratorMayRequestAnExactOnlineName() {
        for (boolean authorized : Arrays.asList(true, true)) {
            assertEquals(InventoryTargetPolicy.Status.ADMIN_ONLINE_LOOKUP,
                InventoryTargetPolicy.resolve(Collections.emptyList(), "Admin_Lee", authorized).getStatus());
        }
    }

    @Test void unverifiedAndInvalidTargetsAreRejectedBeforePaperAccess() {
        PlayerBinding pending = binding("id-2", 2, "Alex", BindingVerificationState.IDENTITY_CHANGED);
        assertEquals(InventoryTargetPolicy.Status.UNVERIFIED,
            InventoryTargetPolicy.resolve(Collections.singletonList(pending), "2", true).getStatus());
        assertEquals(InventoryTargetPolicy.Status.INVALID_TARGET,
            InventoryTargetPolicy.resolve(Collections.emptyList(), "../world", true).getStatus());
    }

    private static PlayerBinding binding(String id, int slot, String name, BindingVerificationState state) {
        return new PlayerBinding(id, slot, slot == 1, name.toLowerCase(), name, UUID.randomUUID(), state, null, null, null);
    }
}
