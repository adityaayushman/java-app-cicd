package dev.adityaayushman.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class DeploymentStoreTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void recordsAndFindsById() {
        DeploymentStore store = new DeploymentStore(10, clock);
        Deployment d = store.record("api", "1.0.0", "staging", "STARTED");

        assertEquals(d, store.find(d.id()).orElseThrow());
        assertEquals(clock.instant(), d.recordedAt());
        assertFalse(store.find("missing").isPresent());
    }

    @Test
    void listsNewestFirst() {
        DeploymentStore store = new DeploymentStore(10, clock);
        store.record("api", "1.0.0", "staging", "SUCCEEDED");
        store.record("api", "1.1.0", "staging", "SUCCEEDED");
        store.record("api", "1.2.0", "staging", "FAILED");

        List<String> versions = store.list(null, null, 10).stream().map(Deployment::version).toList();
        assertEquals(List.of("1.2.0", "1.1.0", "1.0.0"), versions);
    }

    @Test
    void filtersByServiceAndEnvironment() {
        DeploymentStore store = new DeploymentStore(10, clock);
        store.record("api", "1.0.0", "staging", "SUCCEEDED");
        store.record("api", "1.0.0", "production", "SUCCEEDED");
        store.record("web", "2.0.0", "production", "SUCCEEDED");

        assertEquals(2, store.list("api", null, 10).size());
        assertEquals(2, store.list(null, "production", 10).size());
        assertEquals(1, store.list("web", "production", 10).size());
        assertTrue(store.list("web", "staging", 10).isEmpty());
    }

    @Test
    void honoursTheLimit() {
        DeploymentStore store = new DeploymentStore(10, clock);
        for (int i = 0; i < 5; i++) {
            store.record("api", "1." + i, "dev", "SUCCEEDED");
        }
        List<Deployment> two = store.list(null, null, 2);
        assertEquals(2, two.size());
        assertEquals("1.4", two.get(0).version());
    }

    @Test
    void dropsTheOldestRecordWhenFull() {
        DeploymentStore store = new DeploymentStore(3, clock);
        Deployment first = store.record("api", "1", "dev", "SUCCEEDED");
        store.record("api", "2", "dev", "SUCCEEDED");
        store.record("api", "3", "dev", "SUCCEEDED");
        store.record("api", "4", "dev", "SUCCEEDED");

        assertEquals(3, store.size());
        assertFalse(store.find(first.id()).isPresent());
        assertEquals("4", store.list(null, null, 10).get(0).version());
    }

    @Test
    void rejectsInvalidInputAndStoresNothing() {
        DeploymentStore store = new DeploymentStore(3, clock);
        assertThrows(IllegalArgumentException.class, () -> store.record("bad name", "1", "dev", "STARTED"));
        assertEquals(0, store.size());
    }

    @Test
    void rejectsANonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new DeploymentStore(0, clock));
    }

    @Test
    void isSafeUnderConcurrentWrites() throws Exception {
        DeploymentStore store = new DeploymentStore(10_000, clock);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < 250; i++) {
                        store.record("api", "1." + i, "dev", "SUCCEEDED");
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(2000, store.size());
    }
}
