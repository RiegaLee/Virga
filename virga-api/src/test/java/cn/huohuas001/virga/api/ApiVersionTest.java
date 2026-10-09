package cn.huohuas001.virga.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiVersionTest {
    @Test
    void hostSupportsSameMajorUpToItsOwnVersion() {
        ApiVersion host = new ApiVersion(1, 2, 0);

        assertTrue(host.supports(new ApiVersion(1, 0, 0)));
        assertTrue(host.supports(new ApiVersion(1, 2, 0)));
        assertFalse(host.supports(new ApiVersion(1, 3, 0)));
        assertFalse(host.supports(new ApiVersion(2, 0, 0)));
    }

    @Test
    void currentMinorKeepsApiOneZeroAddonsCompatible() {
        assertTrue(ApiVersion.CURRENT.supports(ApiVersion.V1_0_0));
        assertTrue(ApiVersion.CURRENT.supports(ApiVersion.V1_1_0));
        assertTrue(ApiVersion.CURRENT.supports(ApiVersion.V1_2_0));
        assertTrue(ApiVersion.CURRENT.supports(ApiVersion.V1_3_0));
        assertFalse(ApiVersion.V1_0_0.supports(ApiVersion.V1_1_0));
    }
}
