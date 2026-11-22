package dev.adityaayushman.ledger;

import java.time.Instant;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One recorded deployment of a service to an environment.
 *
 * <p>Instances are immutable. Use {@link #create} to build one from untrusted input: it validates
 * every field and throws {@link IllegalArgumentException} with a message that is safe to return to
 * the caller.
 */
public record Deployment(
        String id,
        String service,
        String version,
        String environment,
        String status,
        Instant recordedAt) {

    /** Statuses a deployment can report. */
    public static final Set<String> STATUSES = Set.of("STARTED", "SUCCEEDED", "FAILED", "ROLLED_BACK");

    private static final Pattern SERVICE = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9._+-]{1,64}");
    private static final Pattern ENVIRONMENT = Pattern.compile("[a-z0-9-]{1,32}");

    /** Validates the fields and returns a new deployment with the given id and timestamp. */
    public static Deployment create(
            String id, String service, String version, String environment, String status, Instant recordedAt) {
        require(service, SERVICE, "service", "1-64 characters from A-Z a-z 0-9 . _ -");
        require(version, VERSION, "version", "1-64 characters from A-Z a-z 0-9 . _ + -");
        require(environment, ENVIRONMENT, "environment", "1-32 characters from a-z 0-9 -");
        if (status == null || !STATUSES.contains(status)) {
            throw new IllegalArgumentException("status must be one of " + String.join(", ", sorted()));
        }
        return new Deployment(id, service, version, environment, status, recordedAt);
    }

    private static void require(String value, Pattern pattern, String field, String rule) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be " + rule);
        }
    }

    private static java.util.List<String> sorted() {
        return STATUSES.stream().sorted().toList();
    }
}
