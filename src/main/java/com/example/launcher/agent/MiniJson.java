package com.example.launcher.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal, dependency-free JSON parser — just enough to read the flat
 * response shapes the VPS agent returns (objects with string/number/boolean
 * values, and one array-of-arrays case for console lines).
 *
 * This exists so AgentClient doesn't need to depend on the launcher's Gson
 * usage elsewhere — the agent's wire format is tiny and stable (see
 * AGENT_API.md), so a self-contained parser keeps this package copy-paste
 * portable and independent of the rest of the launcher.
 *
 * Not a general-purpose JSON parser: no unicode escape decoding beyond
 * u+XXXX, no comments, no trailing commas. Sufficient for this agent's own
 * output, which is always produced by JSON.stringify on the Node side.
 */
final class MiniJson {

    private final String src;
    private int pos;

    private MiniJson(String src) {
        this.src = src;
        this.pos = 0;
    }

    /** Parses a JSON document into Map / List / String / Double / Boolean / null. */
    static Object parse(String json) {
        MiniJson parser = new MiniJson(json);
        parser.skipWhitespace();
        Object result = parser.parseValue();
        parser.skipWhitespace();
        return result;
    }

    private Object parseValue() {
        skipWhitespace();
        if (pos >= src.length()) {
            throw new IllegalArgumentException("Unexpected end of JSON input");
        }
        char c = src.charAt(pos);
        switch (c) {
            case '{': return parseObject();
            case '[': return parseArray();
            case '"': return parseString();
            case 't':
            case 'f': return parseBoolean();
            case 'n': return parseNull();
            default: return parseNumber();
        }
    }

    private Map<String, Object> parseObject() {
        Map<String, Object> result = new LinkedHashMap<>();
        expect('{');
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return result;
        }
        while (true) {
            skipWhitespace();
            String key = parseString();
            skipWhitespace();
            expect(':');
            Object value = parseValue();
            result.put(key, value);
            skipWhitespace();
            char next = peek();
            if (next == ',') {
                pos++;
            } else if (next == '}') {
                pos++;
                break;
            } else {
                throw new IllegalArgumentException("Expected ',' or '}' at position " + pos);
            }
        }
        return result;
    }

    private List<Object> parseArray() {
        List<Object> result = new ArrayList<>();
        expect('[');
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return result;
        }
        while (true) {
            Object value = parseValue();
            result.add(value);
            skipWhitespace();
            char next = peek();
            if (next == ',') {
                pos++;
            } else if (next == ']') {
                pos++;
                break;
            } else {
                throw new IllegalArgumentException("Expected ',' or ']' at position " + pos);
            }
        }
        return result;
    }

    private String parseString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw new IllegalArgumentException("Unterminated string");
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                break;
            }
            if (c == '\\') {
                char escaped = src.charAt(pos++);
                switch (escaped) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        String hex = src.substring(pos, pos + 4);
                        sb.append((char) Integer.parseInt(hex, 16));
                        pos += 4;
                        break;
                    default:
                        throw new IllegalArgumentException("Invalid escape \\" + escaped);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Double parseNumber() {
        int start = pos;
        if (peek() == '-') pos++;
        while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
        if (pos < src.length() && src.charAt(pos) == '.') {
            pos++;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
        }
        if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
            pos++;
            if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
        }
        return Double.parseDouble(src.substring(start, pos));
    }

    private Boolean parseBoolean() {
        if (src.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (src.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("Invalid literal at position " + pos);
    }

    private Object parseNull() {
        if (src.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        throw new IllegalArgumentException("Invalid literal at position " + pos);
    }

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
    }

    private char peek() {
        if (pos >= src.length()) {
            throw new IllegalArgumentException("Unexpected end of JSON input");
        }
        return src.charAt(pos);
    }

    private void expect(char c) {
        skipWhitespace();
        if (pos >= src.length() || src.charAt(pos) != c) {
            throw new IllegalArgumentException("Expected '" + c + "' at position " + pos);
        }
        pos++;
    }

    // --- small helpers for callers pulling typed values out of a parsed Map ---

    /**
     * Типизированные обращения, терпимые к тому, в каком виде значение пришло.
     *
     * <p>Все эти методы были написаны в расчёте на один конкретный тип, и смена типа на стороне
     * агента превращалась в исключение вместо «не распознал»:
     * <ul>
     *   <li>{@code asObject}/{@code asArray} — непроверенный каст, то есть
     *       {@code ClassCastException} на любом другом типе и NPE в вызывающем, который сразу
     *       делает {@code .size()}. Теперь при неподходящем значении возвращают null;</li>
     *   <li>{@code asString} возвращал {@code v.toString()}, а числа парсер складывает в
     *       {@code Double} — поэтому порт приезжал как {@code "25565.0"}, а метка времени как
     *       {@code "1.78E12"}. Теперь целые числа печатаются целыми;</li>
     *   <li>{@code asBoolean} был голым кастом {@code (Boolean) v} и выполнялся на КАЖДОМ ответе
     *       (requestObject проверяет поле ok), так что одно {@code "ok": 1} делало все вызовы
     *       «invalid JSON»;</li>
     *   <li>{@code asInt} был {@code ((Double) v).intValue()} — ClassCastException на
     *       {@code "pct": "42"} и молчаливое обрезание до {@code Integer.MAX_VALUE} выше 2³¹.</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> asObject(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> asArray(Object value) {
        return value instanceof List ? (List<Object>) value : null;
    }

    static String asString(Map<String, Object> obj, String key) {
        Object v = obj == null ? null : obj.get(key);
        if (v == null) return null;
        if (v instanceof String str) return str;
        if (v instanceof Double d) {
            // Целое печатаем целым: иначе порт 25565 превращался в "25565.0".
            if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e18) {
                return String.valueOf((long) (double) d);
            }
            return String.valueOf((double) d);
        }
        return v.toString();
    }

    static boolean asBoolean(Map<String, Object> obj, String key, boolean defaultValue) {
        Object v = obj == null ? null : obj.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Boolean b) return b;
        if (v instanceof Double d) return d != 0;            // 1 / 0 от скрипта на сервере
        if (v instanceof String str) {
            String t = str.trim().toLowerCase();
            if (t.equals("true") || t.equals("1") || t.equals("yes")) return true;
            if (t.equals("false") || t.equals("0") || t.equals("no")) return false;
        }
        return defaultValue;
    }

    static int asInt(Map<String, Object> obj, String key, int defaultValue) {
        Object v = obj == null ? null : obj.get(key);
        if (v == null) return defaultValue;
        double d;
        if (v instanceof Double dbl) d = dbl;
        else if (v instanceof Number num) d = num.doubleValue();
        else if (v instanceof String str) {
            try { d = Double.parseDouble(str.trim()); } catch (NumberFormatException e) { return defaultValue; }
        } else {
            return defaultValue;
        }
        // Не обрезаем молча до MAX_VALUE: значение вне диапазона int — это «не распознал».
        if (d > Integer.MAX_VALUE || d < Integer.MIN_VALUE || Double.isNaN(d)) return defaultValue;
        return (int) d;
    }

    /** Minimal JSON string escaping for building request bodies. */
    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
