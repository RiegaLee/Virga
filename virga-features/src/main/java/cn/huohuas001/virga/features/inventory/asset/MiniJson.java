package cn.huohuas001.virga.features.inventory.asset;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small dependency-free JSON reader/writer for Minecraft asset metadata. */
public final class MiniJson {
    private MiniJson() {}

    public static Object parse(String input) {
        Parser parser = new Parser(input);
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) throw parser.error("Trailing JSON content");
        return value;
    }

    public static Map<String, Object> object(Object value, String label) {
        if (!(value instanceof Map)) throw new IllegalArgumentException(label + " must be a JSON object");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) value;
        return result;
    }

    public static List<Object> array(Object value, String label) {
        if (!(value instanceof List)) throw new IllegalArgumentException(label + " must be a JSON array");
        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) value;
        return result;
    }

    public static String string(Map<String, Object> object, String key, boolean required) {
        Object value = object.get(key);
        if (value == null && !required) return null;
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new IllegalArgumentException(key + " must be a non-empty JSON string");
        }
        return ((String) value).trim();
    }

    public static int integer(Map<String, Object> object, String key, int fallback) {
        Object value = object.get(key);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    static String stringify(Object value) {
        StringBuilder output = new StringBuilder();
        write(value, output, 0);
        output.append('\n');
        return output.toString();
    }

    private static void write(Object value, StringBuilder output, int depth) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String) {
            output.append('"');
            escape((String) value, output);
            output.append('"');
        } else if (value instanceof Number || value instanceof Boolean) {
            output.append(value);
        } else if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> object = (Map<String, Object>) value;
            output.append('{');
            if (!object.isEmpty()) output.append('\n');
            int index = 0;
            for (Map.Entry<String, Object> entry : object.entrySet()) {
                indent(output, depth + 1);
                output.append('"');
                escape(entry.getKey(), output);
                output.append("\": ");
                write(entry.getValue(), output, depth + 1);
                if (++index < object.size()) output.append(',');
                output.append('\n');
            }
            if (!object.isEmpty()) indent(output, depth);
            output.append('}');
        } else if (value instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> array = (List<Object>) value;
            output.append('[');
            if (!array.isEmpty()) output.append('\n');
            for (int index = 0; index < array.size(); index++) {
                indent(output, depth + 1);
                write(array.get(index), output, depth + 1);
                if (index + 1 < array.size()) output.append(',');
                output.append('\n');
            }
            if (!array.isEmpty()) indent(output, depth);
            output.append(']');
        } else {
            throw new IllegalArgumentException("Unsupported JSON value " + value.getClass().getName());
        }
    }

    private static void escape(String value, StringBuilder output) {
        for (int index = 0; index < value.length(); index++) {
            char part = value.charAt(index);
            switch (part) {
                case '"': output.append("\\\""); break;
                case '\\': output.append("\\\\"); break;
                case '\b': output.append("\\b"); break;
                case '\f': output.append("\\f"); break;
                case '\n': output.append("\\n"); break;
                case '\r': output.append("\\r"); break;
                case '\t': output.append("\\t"); break;
                default:
                    if (part < 0x20) output.append(String.format("\\u%04x", (int) part));
                    else output.append(part);
            }
        }
    }

    private static void indent(StringBuilder output, int depth) {
        for (int index = 0; index < depth; index++) output.append("  ");
    }

    private static final class Parser {
        private final String input;
        private int position;

        private Parser(String input) {
            if (input == null) throw new NullPointerException("input");
            this.input = input;
        }

        private Object readValue() {
            skipWhitespace();
            if (atEnd()) throw error("Expected JSON value");
            char next = input.charAt(position);
            if (next == '{') return readObject();
            if (next == '[') return readArray();
            if (next == '"') return readString();
            if (next == 't') return literal("true", Boolean.TRUE);
            if (next == 'f') return literal("false", Boolean.FALSE);
            if (next == 'n') return literal("null", null);
            if (next == '-' || Character.isDigit(next)) return readNumber();
            throw error("Unexpected JSON token");
        }

        private Map<String, Object> readObject() {
            position++;
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            skipWhitespace();
            if (consume('}')) return result;
            while (true) {
                skipWhitespace();
                if (atEnd() || input.charAt(position) != '"') throw error("Expected object key");
                String key = readString();
                skipWhitespace();
                require(':');
                result.put(key, readValue());
                skipWhitespace();
                if (consume('}')) return result;
                require(',');
            }
        }

        private List<Object> readArray() {
            position++;
            List<Object> result = new ArrayList<Object>();
            skipWhitespace();
            if (consume(']')) return result;
            while (true) {
                result.add(readValue());
                skipWhitespace();
                if (consume(']')) return result;
                require(',');
            }
        }

        private String readString() {
            require('"');
            StringBuilder result = new StringBuilder();
            while (!atEnd()) {
                char part = input.charAt(position++);
                if (part == '"') return result.toString();
                if (part != '\\') {
                    if (part < 0x20) throw error("Control character in string");
                    result.append(part);
                    continue;
                }
                if (atEnd()) throw error("Incomplete string escape");
                char escaped = input.charAt(position++);
                switch (escaped) {
                    case '"': result.append('"'); break;
                    case '\\': result.append('\\'); break;
                    case '/': result.append('/'); break;
                    case 'b': result.append('\b'); break;
                    case 'f': result.append('\f'); break;
                    case 'n': result.append('\n'); break;
                    case 'r': result.append('\r'); break;
                    case 't': result.append('\t'); break;
                    case 'u':
                        if (position + 4 > input.length()) throw error("Incomplete unicode escape");
                        try {
                            result.append((char) Integer.parseInt(input.substring(position, position + 4), 16));
                        } catch (NumberFormatException error) {
                            throw error("Invalid unicode escape");
                        }
                        position += 4;
                        break;
                    default: throw error("Unknown string escape");
                }
            }
            throw error("Unterminated string");
        }

        private Number readNumber() {
            int start = position;
            if (input.charAt(position) == '-') position++;
            while (!atEnd() && Character.isDigit(input.charAt(position))) position++;
            boolean decimal = false;
            if (!atEnd() && input.charAt(position) == '.') {
                decimal = true;
                position++;
                while (!atEnd() && Character.isDigit(input.charAt(position))) position++;
            }
            if (!atEnd() && (input.charAt(position) == 'e' || input.charAt(position) == 'E')) {
                decimal = true;
                position++;
                if (!atEnd() && (input.charAt(position) == '+' || input.charAt(position) == '-')) position++;
                while (!atEnd() && Character.isDigit(input.charAt(position))) position++;
            }
            String value = input.substring(start, position);
            try {
                return decimal ? Double.valueOf(value) : Long.valueOf(value);
            } catch (NumberFormatException error) {
                throw error("Invalid JSON number");
            }
        }

        private Object literal(String token, Object value) {
            if (!input.regionMatches(position, token, 0, token.length())) throw error("Invalid literal");
            position += token.length();
            return value;
        }

        private void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(input.charAt(position))) position++;
        }

        private boolean consume(char expected) {
            if (!atEnd() && input.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void require(char expected) {
            if (!consume(expected)) throw error("Expected '" + expected + "'");
        }

        private boolean atEnd() { return position >= input.length(); }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at JSON offset " + position);
        }
    }
}
