package dev.adityaayushman.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MainTest {

    @Test
    void usesTheFallbackWhenUnset() {
        assertEquals(8080, Main.parseSetting("PORT", null, 8080, 1, 65535));
        assertEquals(8080, Main.parseSetting("PORT", "  ", 8080, 1, 65535));
    }

    @Test
    void parsesValidValuesWithSurroundingSpace() {
        assertEquals(9090, Main.parseSetting("PORT", " 9090 ", 8080, 1, 65535));
        assertEquals(1, Main.parseSetting("PORT", "1", 8080, 1, 65535));
        assertEquals(65535, Main.parseSetting("PORT", "65535", 8080, 1, 65535));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "65536", "-1", "abc", "80.5", "99999999999"})
    void rejectsInvalidValues(String raw) {
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> Main.parseSetting("PORT", raw, 8080, 1, 65535));
        assertTrue(e.getMessage().startsWith("PORT must be an integer between 1 and 65535"));
    }

    @Test
    void reportsADevVersionWhenNotRunningFromAJar() {
        assertEquals("dev", Main.version());
    }
}
