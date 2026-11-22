package dev.adityaayushman.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static Deployment valid() {
        return Deployment.create("id-1", "payments-api", "1.4.2", "production", "SUCCEEDED", NOW);
    }

    @Test
    void createsADeploymentFromValidInput() {
        Deployment d = valid();
        assertEquals("payments-api", d.service());
        assertEquals("1.4.2", d.version());
        assertEquals("production", d.environment());
        assertEquals("SUCCEEDED", d.status());
        assertEquals(NOW, d.recordedAt());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"has space", "semi;colon", "slash/inside", "<script>"})
    void rejectsInvalidServiceNames(String service) {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> Deployment.create("id", service, "1.0.0", "staging", "STARTED", NOW));
        assertTrue(e.getMessage().startsWith("service must be"));
    }

    @Test
    void rejectsOverlongFields() {
        String tooLong = "a".repeat(65);
        assertThrows(IllegalArgumentException.class, () -> Deployment.create("id", tooLong, "1", "dev", "STARTED", NOW));
        assertThrows(IllegalArgumentException.class, () -> Deployment.create("id", "svc", tooLong, "dev", "STARTED", NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> Deployment.create("id", "svc", "1", "a".repeat(33), "STARTED", NOW));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Production", "prod_1", "prod env", ""})
    void rejectsInvalidEnvironments(String environment) {
        assertThrows(
                IllegalArgumentException.class,
                () -> Deployment.create("id", "svc", "1.0.0", environment, "STARTED", NOW));
    }

    @ParameterizedTest
    @ValueSource(strings = {"succeeded", "DONE", ""})
    void rejectsUnknownStatuses(String status) {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> Deployment.create("id", "svc", "1.0.0", "dev", status, NOW));
        assertTrue(e.getMessage().contains("FAILED"), "message should list the allowed statuses");
    }

    @Test
    void acceptsEveryDocumentedStatus() {
        for (String status : Deployment.STATUSES) {
            assertEquals(status, Deployment.create("id", "svc", "1.0.0", "dev", status, NOW).status());
        }
    }
}
