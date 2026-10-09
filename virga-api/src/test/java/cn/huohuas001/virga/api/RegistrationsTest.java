package cn.huohuas001.virga.api;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistrationsTest {
    @Test
    void closeIsIdempotentAndRunsUnregisterOnce() {
        AtomicInteger unregisterCalls = new AtomicInteger();
        Registration registration = Registrations.create(unregisterCalls::incrementAndGet);

        assertFalse(registration.isClosed());
        registration.close();
        registration.close();

        assertTrue(registration.isClosed());
        assertEquals(1, unregisterCalls.get());
    }
}
