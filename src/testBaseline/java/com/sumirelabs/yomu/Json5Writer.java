// Frozen pre-sharing regression fixture; test-only, never included in published JARs.
package com.sumirelabs.yomu;

import java.io.IOException;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/** Immutable, reusable JSON5 writer configuration. Each write uses its own state. */
public final class Json5Writer {
    private final String indent;
    private final boolean quoteKeys;
    private final boolean expandArrays;
    private final boolean strictJson;

    public Json5Writer() { this("", true, true, false); }

    private Json5Writer(String indent, boolean quoteKeys, boolean expandArrays, boolean strictJson) {
        this.indent = indent;
        this.quoteKeys = quoteKeys;
        this.expandArrays = expandArrays;
        this.strictJson = strictJson;
    }

    /** Returns a writer with indentation (spaces/tabs only); empty means compact output. */
    public Json5Writer withIndent(String indent) {
        Objects.requireNonNull(indent, "indent");
        for (int i = 0; i < indent.length(); i++) {
            char c = indent.charAt(i);
            if (c != ' ' && c != '\t') throw new IllegalArgumentException("Indent must contain only spaces or tabs");
        }
        return new Json5Writer(indent, quoteKeys, expandArrays, strictJson);
    }

    /** Invalid identifier keys are always quoted, even when this option is false. */
    public Json5Writer withQuoteKeys(boolean value) { return new Json5Writer(indent, value, expandArrays, strictJson); }

    public Json5Writer withExpandArrays(boolean value) { return new Json5Writer(indent, quoteKeys, value, strictJson); }

    /** Emits JSON: keys are quoted, comment metadata is omitted, and NaN/Infinity are rejected. */
    public Json5Writer withStrictJson(boolean value) { return new Json5Writer(indent, quoteKeys, expandArrays, value); }
    public boolean isStrictJson() { return strictJson; }

    public String stringify(Object value) {
        State state = new State();
        state.value(value, 0);
        return state.out.toString();
    }

    public void write(Object value, Appendable output) throws IOException {
        output.append(stringify(value));
    }

    /** Replaces a file with UTF-8 JSON5. */
    public void write(Object value, Path path) throws IOException {
        Files.write(path, stringify(value).getBytes(StandardCharsets.UTF_8));
    }

    private final class State {
        final StringBuilder out = new StringBuilder(256);
        final IdentityHashMap<Object, Boolean> active = new IdentityHashMap<Object, Boolean>();

        Object comment(Object value, int depth) {
            if (strictJson) {
                while (value instanceof CommentedValue) value = ((CommentedValue)value).getValue();
                return value;
            }
            while (value instanceof CommentedValue) {
                CommentedValue cv = (CommentedValue) value;
                String text = cv.getComment();
                out.append("// ");
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (c == '\r' || c == '\n' || c == '\u2028' || c == '\u2029') {
                        if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                        out.append('\n');
                        pad(depth);
                        out.append("// ");
                    } else out.append(c);
                }
                out.append('\n');
                pad(depth);
                value = cv.getValue();
            }
            return value;
        }

        void value(Object input, int depth) {
            Object v = comment(input, depth);
            if (v == null) { out.append("null"); return; }
            if (v instanceof String) { string((String) v); return; }
            if (v instanceof Boolean) { out.append(v); return; }
            if (v instanceof Number) {
                if (!(v instanceof Byte || v instanceof Short || v instanceof Integer || v instanceof Long
                        || v instanceof Float || v instanceof Double || v instanceof BigInteger || v instanceof BigDecimal)) {
                    throw new IllegalArgumentException("Unsupported Number type: " + v.getClass().getName());
                }
                if (strictJson) {
                    if ((v instanceof Double || v instanceof Float) &&
                            (Double.isNaN(((Number)v).doubleValue()) || Double.isInfinite(((Number)v).doubleValue())))
                        throw new IllegalArgumentException("NaN and Infinity cannot be written as JSON numbers");
                    if (v instanceof BigDecimal && v.getClass() != BigDecimal.class
                            || v instanceof BigInteger && v.getClass() != BigInteger.class)
                        throw new IllegalArgumentException("Numeric subclasses are not supported in strict JSON: " + v.getClass().getName());
                }
                out.append(v);
                return;
            }
            if (depth >= Yomu.DEFAULT_MAX_DEPTH) throw new IllegalArgumentException("Maximum nesting depth exceeded");
            if (active.put(v, Boolean.TRUE) != null) throw new IllegalArgumentException("Cyclic value graph");
            try {
                if (v instanceof Map) object((Map<?, ?>) v, depth);
                else if (v instanceof Iterable) array(((Iterable<?>) v).iterator(), depth);
                else if (v.getClass().isArray()) {
                    final Object array = v;
                    array(new Iterator<Object>() {
                        int i;
                        final int size = Array.getLength(array);
                        public boolean hasNext() { return i < size; }
                        public Object next() { return Array.get(array, i++); }
                        public void remove() { throw new UnsupportedOperationException(); }
                    }, depth);
                } else throw new IllegalArgumentException("Unsupported JSON5 value: " + v.getClass().getName());
            } finally { active.remove(v); }
        }

        void object(Map<?, ?> map, int depth) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String)) throw new IllegalArgumentException("Object keys must be strings");
                if (!first) out.append(',');
                line(depth + 1);
                Object v = comment(entry.getValue(), depth + 1);
                String key = (String) entry.getKey();
                if (!strictJson && !quoteKeys && identifier(key)) out.append(key); else string(key);
                out.append(indent.isEmpty() ? ":" : ": ");
                value(v, depth + 1);
                first = false;
            }
            if (!first) line(depth);
            out.append('}');
        }

        void array(Iterator<?> items, int depth) {
            out.append('[');
            boolean first = true;
            while (items.hasNext()) {
                if (!first) out.append(',');
                if (expandArrays) line(depth + 1);
                else if (!first && !indent.isEmpty()) out.append(' ');
                value(items.next(), depth + 1);
                first = false;
            }
            if (!first && expandArrays) line(depth);
            out.append(']');
        }

        void string(String text) { StringEscapes.quoted(text,out,'"'); }

        void line(int depth) { if (!indent.isEmpty()) { out.append('\n'); pad(depth); } }
        void pad(int depth) { for (int i = 0; i < depth; i++) out.append(indent); }
    }

    static boolean identifier(String key) {
        if (key.isEmpty()) return false;
        int c = key.codePointAt(0);
        if (!Parser.identifierStart(c)) return false;
        for (int i = Character.charCount(c); i < key.length(); i += Character.charCount(c)) {
            c = key.codePointAt(i);
            if (!Parser.identifierPart(c)) return false;
        }
        return true;
    }
}
