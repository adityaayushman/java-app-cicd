package dev.adityaayushman.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Clock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LedgerServerTest {

    private static final String VALID =
            "{\"service\":\"payments-api\",\"version\":\"1.4.2\",\"environment\":\"production\",\"status\":\"SUCCEEDED\"}";

    private LedgerServer server;
    private HttpClient client;
    private String base;

    @BeforeEach
    void startServer() throws IOException {
        server = new LedgerServer(0, new DeploymentStore(100, Clock.systemUTC()), "9.9.9-test");
        server.start();
        client = HttpClient.newHttpClient();
        base = "http://localhost:" + server.port();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String contentType, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).POST(BodyPublishers.ofString(body));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        return client.send(request.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> create(String body) throws Exception {
        return post("/deployments", "application/json", body);
    }

    private static String idOf(String json) {
        Matcher m = Pattern.compile("\"id\":\"([^\"]+)\"").matcher(json);
        assertTrue(m.find(), json);
        return m.group(1);
    }

    @Test
    void healthReportsUp() throws Exception {
        HttpResponse<String> r = get("/health");
        assertEquals(200, r.statusCode());
        assertEquals("{\"status\":\"UP\"}", r.body());
    }

    @Test
    void versionReportsTheConfiguredVersion() throws Exception {
        assertEquals("{\"version\":\"9.9.9-test\"}", get("/version").body());
    }

    @Test
    void responsesCarrySafeHeaders() throws Exception {
        HttpResponse<String> r = get("/health");
        assertEquals("application/json; charset=utf-8", r.headers().firstValue("Content-Type").orElse(""));
        assertEquals("nosniff", r.headers().firstValue("X-Content-Type-Options").orElse(""));
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
    }

    @Test
    void createsAndFetchesADeployment() throws Exception {
        HttpResponse<String> created = create(VALID);
        assertEquals(201, created.statusCode());
        String id = idOf(created.body());
        assertEquals("/deployments/" + id, created.headers().firstValue("Location").orElse(""));
        assertTrue(created.body().contains("\"service\":\"payments-api\""));

        HttpResponse<String> fetched = get("/deployments/" + id);
        assertEquals(200, fetched.statusCode());
        assertEquals(created.body(), fetched.body());
    }

    @Test
    void listsNewestFirstAndFilters() throws Exception {
        create("{\"service\":\"api\",\"version\":\"1\",\"environment\":\"staging\",\"status\":\"SUCCEEDED\"}");
        create("{\"service\":\"api\",\"version\":\"2\",\"environment\":\"production\",\"status\":\"FAILED\"}");
        create("{\"service\":\"web\",\"version\":\"3\",\"environment\":\"production\",\"status\":\"SUCCEEDED\"}");

        String all = get("/deployments").body();
        assertTrue(all.indexOf("\"version\":\"3\"") < all.indexOf("\"version\":\"2\""));
        assertTrue(all.indexOf("\"version\":\"2\"") < all.indexOf("\"version\":\"1\""));

        String prodApi = get("/deployments?service=api&environment=production").body();
        assertTrue(prodApi.contains("\"version\":\"2\""));
        assertFalse(prodApi.contains("\"version\":\"1\""));
        assertFalse(prodApi.contains("\"version\":\"3\""));

        assertEquals("[]", get("/deployments?service=nothing").body());
    }

    @Test
    void honoursAndValidatesTheLimit() throws Exception {
        for (int i = 0; i < 3; i++) {
            create("{\"service\":\"api\",\"version\":\"" + i + "\",\"environment\":\"dev\",\"status\":\"SUCCEEDED\"}");
        }
        String one = get("/deployments?limit=1").body();
        assertEquals(1, one.split("\"id\"", -1).length - 1);

        assertEquals(400, get("/deployments?limit=0").statusCode());
        assertEquals(400, get("/deployments?limit=201").statusCode());
        assertEquals(400, get("/deployments?limit=abc").statusCode());
        assertEquals(200, get("/deployments?limit=200").statusCode());
    }

    @Test
    void decodesQueryParameters() throws Exception {
        create(VALID);
        assertTrue(get("/deployments?service=payments%2Dapi").body().contains("payments-api"));
    }

    @Test
    void rejectsInvalidDeployments() throws Exception {
        HttpResponse<String> badStatus = create(VALID.replace("SUCCEEDED", "DONE"));
        assertEquals(400, badStatus.statusCode());
        assertTrue(badStatus.body().contains("status must be one of"));

        assertEquals(400, create(VALID.replace("payments-api", "bad name")).statusCode());
        assertEquals(400, create("{\"service\":\"api\"}").statusCode());
    }

    @Test
    void rejectsMalformedAndUnexpectedJson() throws Exception {
        assertEquals(400, create("not json").statusCode());
        assertEquals(400, create("").statusCode());
        assertEquals(400, create("[]").statusCode());

        HttpResponse<String> unknown = create(VALID.replace("}", ",\"extra\":\"x\"}"));
        assertEquals(400, unknown.statusCode());
        assertTrue(unknown.body().contains("unknown field"));
    }

    @Test
    void requiresAJsonContentType() throws Exception {
        assertEquals(415, post("/deployments", "text/plain", VALID).statusCode());
        assertEquals(415, post("/deployments", null, VALID).statusCode());
        assertEquals(201, post("/deployments", "Application/JSON; charset=utf-8", VALID).statusCode());
    }

    @Test
    void rejectsOversizedBodies() throws Exception {
        String padding = "x".repeat(LedgerServer.MAX_BODY_BYTES);
        HttpResponse<String> r = create("{\"service\":\"" + padding + "\"}");
        assertEquals(413, r.statusCode());
    }

    @Test
    void returnsNotFoundForUnknownPathsAndIds() throws Exception {
        assertEquals(404, get("/nope").statusCode());
        assertEquals(404, get("/deployments/does-not-exist").statusCode());
        assertEquals(404, get("/deployments/a/b").statusCode());
    }

    @Test
    void returnsMethodNotAllowedWithAnAllowHeader() throws Exception {
        HttpResponse<String> put = client.send(
                HttpRequest.newBuilder(URI.create(base + "/deployments"))
                        .PUT(BodyPublishers.ofString("{}"))
                        .build(),
                BodyHandlers.ofString());
        assertEquals(405, put.statusCode());
        assertEquals("GET, POST", put.headers().firstValue("Allow").orElse(""));

        assertEquals(405, post("/health", "application/json", "{}").statusCode());
        assertEquals(405, post("/deployments/abc", "application/json", "{}").statusCode());
    }

    @Test
    void servesManyConcurrentRequests() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 40; i++) {
                futures.add(pool.submit(() -> create(VALID).statusCode()));
            }
            for (var f : futures) {
                assertEquals(201, f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(40, get("/deployments?limit=200").body().split("\"id\"", -1).length - 1);
    }

    @Test
    void exposesTheBoundPort() {
        assertTrue(server.port() > 0);
    }
}
