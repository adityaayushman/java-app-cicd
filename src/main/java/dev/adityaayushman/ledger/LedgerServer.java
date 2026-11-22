package dev.adityaayushman.ledger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HTTP API for the deployment ledger, built on the JDK's own HTTP server.
 *
 * <pre>
 * GET  /health                 liveness and readiness probe
 * GET  /version                build version
 * POST /deployments            record a deployment
 * GET  /deployments            list, newest first (?service=&amp;environment=&amp;limit=)
 * GET  /deployments/{id}       fetch one
 * </pre>
 */
public final class LedgerServer implements AutoCloseable {

    static final int MAX_BODY_BYTES = 8 * 1024;
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private static final System.Logger LOG = System.getLogger(LedgerServer.class.getName());
    private static final Set<String> CREATE_FIELDS = Set.of("service", "version", "environment", "status");

    private final HttpServer server;
    private final ExecutorService executor;
    private final DeploymentStore store;
    private final String version;

    public LedgerServer(int port, DeploymentStore store, String version) throws IOException {
        this.store = store;
        this.version = version;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.server.createContext("/", this::route);
        this.server.setExecutor(executor);
    }

    public void start() {
        server.start();
    }

    /** The port the server is bound to (useful when constructed with port 0). */
    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(1);
        executor.shutdown();
    }

    private void route(HttpExchange exchange) throws IOException {
        long started = System.nanoTime();
        int status = 500;
        try {
            status = dispatch(exchange);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "unhandled error handling request", e);
            status = send(exchange, 500, Json.error("internal error"));
        } finally {
            exchange.close();
            LOG.log(
                    System.Logger.Level.INFO,
                    "{0} {1} -> {2} ({3} ms)",
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    status,
                    (System.nanoTime() - started) / 1_000_000);
        }
    }

    private int dispatch(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        if (path.equals("/health")) {
            return method.equals("GET") ? send(exchange, 200, "{\"status\":\"UP\"}") : methodNotAllowed(exchange, "GET");
        }
        if (path.equals("/version")) {
            return method.equals("GET")
                    ? send(exchange, 200, "{\"version\":" + Json.quote(version) + "}")
                    : methodNotAllowed(exchange, "GET");
        }
        if (path.equals("/deployments")) {
            return switch (method) {
                case "GET" -> list(exchange);
                case "POST" -> create(exchange);
                default -> methodNotAllowed(exchange, "GET, POST");
            };
        }
        if (path.startsWith("/deployments/") && path.indexOf('/', "/deployments/".length()) < 0) {
            if (!method.equals("GET")) {
                return methodNotAllowed(exchange, "GET");
            }
            String id = path.substring("/deployments/".length());
            Optional<Deployment> found = store.find(id);
            return found.isPresent()
                    ? send(exchange, 200, Json.toJson(found.get()))
                    : send(exchange, 404, Json.error("deployment not found"));
        }
        return send(exchange, 404, Json.error("not found"));
    }

    private int create(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            return send(exchange, 415, Json.error("Content-Type must be application/json"));
        }
        byte[] body = readBody(exchange.getRequestBody());
        if (body == null) {
            return send(exchange, 413, Json.error("request body must be at most " + MAX_BODY_BYTES + " bytes"));
        }
        try {
            Map<String, String> fields = Json.parseFlatObject(new String(body, StandardCharsets.UTF_8));
            for (String key : fields.keySet()) {
                if (!CREATE_FIELDS.contains(key)) {
                    return send(exchange, 400, Json.error("unknown field \"" + key + "\""));
                }
            }
            Deployment created = store.record(
                    fields.get("service"), fields.get("version"), fields.get("environment"), fields.get("status"));
            exchange.getResponseHeaders().set("Location", "/deployments/" + created.id());
            return send(exchange, 201, Json.toJson(created));
        } catch (IllegalArgumentException e) {
            // Both JSON parse errors and validation errors carry messages that are safe to return.
            return send(exchange, 400, Json.error(e.getMessage()));
        }
    }

    private int list(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        int limit = DEFAULT_LIMIT;
        String rawLimit = query.get("limit");
        if (rawLimit != null) {
            try {
                limit = Integer.parseInt(rawLimit);
            } catch (NumberFormatException e) {
                return send(exchange, 400, Json.error("limit must be an integer"));
            }
            if (limit < 1 || limit > MAX_LIMIT) {
                return send(exchange, 400, Json.error("limit must be between 1 and " + MAX_LIMIT));
            }
        }
        List<Deployment> deployments = store.list(query.get("service"), query.get("environment"), limit);
        return send(exchange, 200, Json.toJson(deployments));
    }

    /** Reads at most {@link #MAX_BODY_BYTES}; returns {@code null} if the body is larger. */
    private static byte[] readBody(InputStream in) throws IOException {
        byte[] data = in.readNBytes(MAX_BODY_BYTES + 1);
        return data.length > MAX_BODY_BYTES ? null : data;
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> result = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return result;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            result.putIfAbsent(key, value);
        }
        return result;
    }

    private static int methodNotAllowed(HttpExchange exchange, String allow) throws IOException {
        exchange.getResponseHeaders().set("Allow", allow);
        return send(exchange, 405, Json.error("method not allowed"));
    }

    private static int send(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        return status;
    }
}
