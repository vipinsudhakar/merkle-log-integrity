package com.merklelog.benchmark;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A minimal JSON serialiser for the benchmark results.
 *
 * <p>The benchmark package is deliberately free of Spring and of third-party libraries, like
 * {@code core} and {@code chunking}, so it carries its own tiny writer. It handles exactly what
 * the results contain: maps (insertion-ordered), lists, strings, numbers, booleans and null.
 */
final class Json {

    private Json() {
    }

    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out, 0);
        return out.append('\n').toString();
    }

    private static void write(Object value, StringBuilder out, int indent) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            out.append('"').append(escape(s)).append('"');
        } else if (value instanceof Double d) {
            out.append(Double.isFinite(d) ? String.format(Locale.ROOT, "%.4f", d) : "null");
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            writeMap(map, out, indent);
        } else if (value instanceof List<?> list) {
            writeList(list, out, indent);
        } else {
            throw new IllegalArgumentException("Cannot write " + value.getClass().getSimpleName() + " as JSON");
        }
    }

    private static void writeMap(Map<?, ?> map, StringBuilder out, int indent) {
        if (map.isEmpty()) {
            out.append("{}");
            return;
        }
        out.append("{\n");
        Iterator<? extends Map.Entry<?, ?>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<?, ?> entry = it.next();
            pad(out, indent + 1).append('"').append(escape(String.valueOf(entry.getKey()))).append("\": ");
            write(entry.getValue(), out, indent + 1);
            out.append(it.hasNext() ? ",\n" : "\n");
        }
        pad(out, indent).append('}');
    }

    private static void writeList(List<?> list, StringBuilder out, int indent) {
        if (list.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append("[\n");
        for (int i = 0; i < list.size(); i++) {
            pad(out, indent + 1);
            write(list.get(i), out, indent + 1);
            out.append(i < list.size() - 1 ? ",\n" : "\n");
        }
        pad(out, indent).append(']');
    }

    private static StringBuilder pad(StringBuilder out, int indent) {
        return out.append("  ".repeat(indent));
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
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
        return out.toString();
    }
}
