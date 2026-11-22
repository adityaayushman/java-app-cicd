package dev.adityaayushman.ledger;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Thread-safe in-memory store for deployments, bounded so a long-running process cannot grow
 * without limit. When the bound is reached the oldest record is dropped.
 */
public final class DeploymentStore {

    private final int capacity;
    private final Clock clock;
    private final Map<String, Deployment> byId = new LinkedHashMap<>();

    public DeploymentStore(int capacity, Clock clock) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        this.capacity = capacity;
        this.clock = clock;
    }

    /** Validates and stores a new deployment, returning the stored record. */
    public synchronized Deployment record(String service, String version, String environment, String status) {
        Deployment deployment = Deployment.create(
                UUID.randomUUID().toString(), service, version, environment, status, clock.instant());
        byId.put(deployment.id(), deployment);
        if (byId.size() > capacity) {
            String oldest = byId.keySet().iterator().next();
            byId.remove(oldest);
        }
        return deployment;
    }

    public synchronized Optional<Deployment> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /**
     * Returns up to {@code limit} deployments, newest first, optionally filtered. A {@code null}
     * filter matches everything.
     */
    public synchronized List<Deployment> list(String service, String environment, int limit) {
        List<Deployment> matches = new ArrayList<>();
        for (Deployment d : byId.values()) {
            if ((service == null || d.service().equals(service))
                    && (environment == null || d.environment().equals(environment))) {
                matches.add(d);
            }
        }
        List<Deployment> newestFirst = new ArrayList<>(matches.size());
        for (int i = matches.size() - 1; i >= 0 && newestFirst.size() < limit; i--) {
            newestFirst.add(matches.get(i));
        }
        return newestFirst;
    }

    public synchronized int size() {
        return byId.size();
    }
}
