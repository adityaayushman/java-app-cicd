package dev.adityaayushman.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonTest {

    @Test
    void parsesAFlatObjectOfStrings() {
        Map<String, String> m = Json.parseFlatObject(" { \"a\" : \"1\" ,\n\"b\":\"two\" } ");
        assertEquals(Map.of("a", "1", "b", "two"), m);
    }

    @Test
    void parsesAnEmptyObject() {
        assertTrue(Json.parseFlatObject("{}").isEmpty());
        assertTrue(Json.parseFlatObject("  {  }  ").isEmpty());
    }

    @Test
    void decodesEscapes() {
        Map<String, String> m = Json.parseFlatObject("{\"k\":\"a\\n\\t\\\"q\\\" \\\\ \\/ \\u00e9 \\u0041\"}");
        assertEquals("a\n\t\"q\" \\ / \u00e9 A", m.get("k"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "[]",
        "null",
        "\"just a string\"",
        "{",
        "{\"a\"}",
        "{\"a\":}",
        "{\"a\":1}",
        "{\"a\":true}",
        "{\"a\":{\"b\":\"c\"}}",
        "{\"a\":[\"b\"]}",
        "{\"a\":\"b\",}",
        "{\"a\":\"b\" \"c\":\"d\"}",
        "{a:\"b\"}",
        "{\"a\":\"b\"} trailing",
        "{\"a\":\"unterminated}",
        "{\"a\":\"bad \\x escape\"}",
        "{\"a\":\"bad \\u12 escape\"}",
        "{\"a\":\"bad \\uZZZZ escape\"}",
        "{\"a\":\"line\nbreak\"}",
    })
    void rejectsAnythingElse(String input) {
        assertThrows(Json.ParseException.class, () -> Json.parseFlatObject(input));
    }

    @Test
    void rejectsDuplicateKeys() {
        Json.ParseException e = assertThrows(
                Json.ParseException.class, () -> Json.parseFlatObject("{\"a\":\"1\",\"a\":\"2\"}"));
        assertTrue(e.getMessage().contains("duplicate key"));
    }

    @Test
    void errorMessagesStateThePosition() {
        Json.ParseException e = assertThrows(Json.ParseException.class, () -> Json.parseFlatObject("{\"a\":1}"));
        assertTrue(e.getMessage().startsWith("invalid JSON at position 5"), e.getMessage());
    }

    @Test
    void quotesAndEscapes() {
        assertEquals("\"plain\"", Json.quote("plain"));
        assertEquals("\"a\\\"b\\\\c\\n\\r\\t\\b\\f\"", Json.quote("a\"b\\c\n\r\t\b\f"));
        assertEquals("\"\\u0001\"", Json.quote("\u0001"));
        assertEquals("\"\u00e9\"", Json.quote("\u00e9"));
    }

    @Test
    void roundTripsThroughTheParser() {
        String tricky = "line1\nline2 \"quoted\" back\\slash \u0007 tab\t";
        Map<String, String> parsed = Json.parseFlatObject("{\"k\":" + Json.quote(tricky) + "}");
        assertEquals(tricky, parsed.get("k"));
    }

    @Test
    void serialisesDeploymentsAsObjectsAndArrays() {
        Deployment d = Deployment.create(
                "id-1", "api", "1.0.0", "staging", "SUCCEEDED", Instant.parse("2026-01-01T00:00:00Z"));
        String one = Json.toJson(d);
        assertEquals(
                "{\"id\":\"id-1\",\"service\":\"api\",\"version\":\"1.0.0\",\"environment\":\"staging\","
                        + "\"status\":\"SUCCEEDED\",\"recordedAt\":\"2026-01-01T00:00:00Z\"}",
                one);
        assertEquals("[]", Json.toJson(List.of()));
        assertEquals("[" + one + "," + one + "]", Json.toJson(List.of(d, d)));
    }

    @Test
    void buildsErrorBodies() {
        assertEquals("{\"error\":\"bad \\\"input\\\"\"}", Json.error("bad \"input\""));
    }
}
