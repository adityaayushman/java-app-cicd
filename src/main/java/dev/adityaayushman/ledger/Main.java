package dev.adityaayushman.ledger;

import java.io.IOException;
import java.time.Clock;

/** Entry point. Configuration comes from the environment: {@code PORT} and {@code LEDGER_CAPACITY}. */
public final class Main {

    private Main() {}

    public static void main(String[] args) throws IOException {
        int port = intFromEnv("PORT", 8080, 1, 65535);
        int capacity = intFromEnv("LEDGER_CAPACITY", 10_000, 1, 1_000_000);

        LedgerServer server = new LedgerServer(port, new DeploymentStore(capacity, Clock.systemUTC()), version());
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "shutdown"));
        server.start();
        System.out.println("deployment-ledger " + version() + " listening on port " + server.port());
    }

    /** The version from the jar manifest, or {@code dev} when running from classes. */
    static String version() {
        String v = Main.class.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
    }

    static int intFromEnv(String name, int fallback, int min, int max) {
        return parseSetting(name, System.getenv(name), fallback, min, max);
    }

    /** Parses a numeric setting. A missing or blank value gives the fallback; an invalid one is an error. */
    static int parseSetting(String name, String raw, int fallback, int min, int max) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value >= min && value <= max) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // fall through to the error below
        }
        throw new IllegalArgumentException(name + " must be an integer between " + min + " and " + max);
    }
}
