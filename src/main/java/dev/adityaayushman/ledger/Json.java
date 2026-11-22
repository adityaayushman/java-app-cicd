package dev.adityaayushman.ledger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A deliberately small JSON codec, enough for this service and nothing more.
 *
 * <p>Reading accepts exactly one JSON object whose values are all strings. Anything else (nesting,
 * numbers, duplicate keys, trailing text) is rejected with a clear message. Writing escapes every
 * character JSON requires. Keeping this in-house means the service has no runtime dependencies.
 */
final class Json {

    private Json() {}

    /** Thrown for any input that is not a flat JSON object of strings. */
    static final class ParseException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        ParseException(String message) {
            super(message);
        }
    }

    /** Parses a flat JSON object whose values are strings. */
    static Map<String, String> parseFlatObject(String text) {
        return new Parser(text).parse();
    }

    /** Quotes and escapes a string as a JSON string literal. */
    static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** Serialises a deployment as a JSON object. */
    static String toJson(Deployment d) {
        return "{\"id\":" + quote(d.id())
                + ",\"service\":" + quote(d.service())
                + ",\"version\":" + quote(d.version())
                + ",\"environment\":" + quote(d.environment())
                + ",\"status\":" + quote(d.status())
                + ",\"recordedAt\":" + quote(d.recordedAt().toString())
                + "}";
    }

    /** Serialises a list of deployments as a JSON array. */
    static String toJson(List<Deployment> deployments) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < deployments.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(toJson(deployments.get(i)));
        }
        return out.append(']').toString();
    }

    /** Builds {@code {"error":"..."}}. */
    static String error(String message) {
        return "{\"error\":" + quote(message) + "}";
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        Map<String, String> parse() {
            skipWhitespace();
            expect('{', "a JSON object");
            Map<String, String> result = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
            } else {
                while (true) {
                    skipWhitespace();
                    String key = readString("an object key");
                    skipWhitespace();
                    expect(':', "':' after the key");
                    skipWhitespace();
                    if (peek() != '"') {
                        throw fail("only string values are supported");
                    }
                    String value = readString("a string value");
                    if (result.put(key, value) != null) {
                        throw fail("duplicate key \"" + key + "\"");
                    }
                    skipWhitespace();
                    char next = peek();
                    pos++;
                    if (next == '}') {
                        break;
                    }
                    if (next != ',') {
                        pos--;
                        throw fail("expected ',' or '}'");
                    }
                }
            }
            skipWhitespace();
            if (pos != text.length()) {
                throw fail("unexpected text after the object");
            }
            return result;
        }

        private String readString(String what) {
            if (peek() != '"') {
                throw fail("expected " + what);
            }
            pos++;
            StringBuilder out = new StringBuilder();
            while (true) {
                if (pos >= text.length()) {
                    throw fail("unterminated string");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c < 0x20) {
                    throw fail("control character in string");
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (pos >= text.length()) {
                    throw fail("unterminated escape");
                }
                char e = text.charAt(pos++);
                switch (e) {
                    case '"', '\\', '/' -> out.append(e);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> out.append(readUnicode());
                    default -> throw fail("invalid escape \\" + e);
                }
            }
        }

        private char readUnicode() {
            if (pos + 4 > text.length()) {
                throw fail("incomplete \\u escape");
            }
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(text.charAt(pos++), 16);
                if (digit < 0) {
                    throw fail("invalid \\u escape");
                }
                value = value * 16 + digit;
            }
            return (char) value;
        }

        private void expect(char expected, String what) {
            if (peek() != expected) {
                throw fail("expected " + what);
            }
            pos++;
        }

        private char peek() {
            return pos < text.length() ? text.charAt(pos) : '\0';
        }

        private void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private ParseException fail(String message) {
            return new ParseException("invalid JSON at position " + pos + ": " + message);
        }
    }
}
