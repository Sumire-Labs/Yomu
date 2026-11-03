// Frozen pre-sharing regression fixture; test-only, never included in published JARs.
package com.sumirelabs.yomu;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Dependency-free JSON5 reading and writing. Static methods are thread-safe. */
public final class Yomu {
    public static final int DEFAULT_MAX_DEPTH = 256;

    private Yomu() { }

    /**
     * Parses any JSON5 root value. Objects are insertion-ordered mutable maps;
     * arrays are mutable lists. Integers use Long or BigInteger; other numbers use Double.
     * Duplicate keys use the last value. Comments and source formatting are discarded.
     */
    public static Object parse(String source) { return parse(source, DEFAULT_MAX_DEPTH); }

    /** Parses with a positive limit on nested objects and arrays. */
    public static Object parse(String source, int maxDepth) {
        Objects.requireNonNull(source, "source");
        if (maxDepth < 1 || maxDepth > 512) throw new IllegalArgumentException("maxDepth must be between 1 and 512");
        return new Parser(source, maxDepth).parse();
    }

    /** Parses an object root, rejecting arrays and primitive roots. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String source) {
        Object value = parse(source);
        if (!(value instanceof Map)) throw new IllegalArgumentException("Root value must be an object");
        return (Map<String, Object>) value;
    }

    /** Reads to the end without closing the caller's reader. Input is buffered in memory. */
    public static Object parse(Reader reader) throws IOException {
        Objects.requireNonNull(reader, "reader");
        char[] buffer = new char[8192];
        StringBuilder source = new StringBuilder();
        int count;
        while ((count = reader.read(buffer)) != -1) source.append(buffer, 0, count);
        return parse(source.toString());
    }

    /** Reads strictly decoded UTF-8 without closing the caller's stream. */
    public static Object parse(InputStream stream) throws IOException {
        Objects.requireNonNull(stream, "stream");
        return parse(new InputStreamReader(stream, StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)));
    }

    /** Reads a UTF-8 file and closes the stream opened by this method. */
    public static Object parse(Path path) throws IOException {
        try (InputStream stream = Files.newInputStream(path)) { return parse(stream); }
    }

    /** Serializes a JSON5 value in compact form. Supports maps, iterables and Java arrays. */
    public static String stringify(Object value) { return new Json5Writer().stringify(value); }

    /** Writes compact UTF-8 JSON5 to a file, replacing its existing contents. */
    public static void write(Path path, Object value) throws IOException {
        Files.write(path, stringify(value).getBytes(StandardCharsets.UTF_8));
    }

    /** Adds an output comment. Input comments are not retained by the parser. */
    public static CommentedValue commented(String comment, Object value) {
        return new CommentedValue(comment, value);
    }
}
