package com.sumirelabs.yomu;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
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

    /** Preferred value-reading entry point. Equivalent to parse; objects/lists are ordinary collections. */
    public static Object read(String source) { return parse(source); }
    public static Object read(String source, Json5ReadOptions options) { return parse(source, options); }
    public static Object read(Reader source) throws IOException { return parse(source); }
    public static Object read(Reader source, Json5ReadOptions options) throws IOException { return parse(source, options); }
    public static Object read(InputStream source) throws IOException { return parse(source); }
    public static Object read(InputStream source, Json5ReadOptions options) throws IOException { return parse(source, options); }
    public static Object read(Path source) throws IOException { return parse(source); }
    public static Object read(Path source, Json5ReadOptions options) throws IOException { return parse(source, options); }

    /** Reads an object with checked typed access, without copying its parsed map. Rejects other roots. */
    public static Json5Object readObject(String source) { return readObject(source, Json5ReadOptions.DEFAULT); }
    public static Json5Object readObject(String source, Json5ReadOptions options) { return Json5Values.object(read(source, options), "$"); }
    public static Json5Object readObject(Reader source) throws IOException { return readObject(source, Json5ReadOptions.DEFAULT); }
    public static Json5Object readObject(Reader source, Json5ReadOptions options) throws IOException { return Json5Values.object(read(source, options), "$"); }
    public static Json5Object readObject(InputStream source) throws IOException { return readObject(source, Json5ReadOptions.DEFAULT); }
    public static Json5Object readObject(InputStream source, Json5ReadOptions options) throws IOException { return Json5Values.object(read(source, options), "$"); }
    public static Json5Object readObject(Path source) throws IOException { return readObject(source, Json5ReadOptions.DEFAULT); }
    public static Json5Object readObject(Path source, Json5ReadOptions options) throws IOException { return Json5Values.object(read(source, options), "$"); }

    /** Reads an array with checked typed access, without copying its parsed list. Rejects other roots. */
    public static Json5Array readArray(String source) { return readArray(source, Json5ReadOptions.DEFAULT); }
    public static Json5Array readArray(String source, Json5ReadOptions options) { return Json5Values.array(read(source, options), "$"); }
    public static Json5Array readArray(Reader source) throws IOException { return readArray(source, Json5ReadOptions.DEFAULT); }
    public static Json5Array readArray(Reader source, Json5ReadOptions options) throws IOException { return Json5Values.array(read(source, options), "$"); }
    public static Json5Array readArray(InputStream source) throws IOException { return readArray(source, Json5ReadOptions.DEFAULT); }
    public static Json5Array readArray(InputStream source, Json5ReadOptions options) throws IOException { return Json5Values.array(read(source, options), "$"); }
    public static Json5Array readArray(Path source) throws IOException { return readArray(source, Json5ReadOptions.DEFAULT); }
    public static Json5Array readArray(Path source, Json5ReadOptions options) throws IOException { return Json5Values.array(read(source, options), "$"); }

    /** Destination-first compact output. Validates the complete value before emitting content. */
    public static void writeTo(Path output, Object value) throws IOException { write(output, value); }
    public static void writeTo(Appendable output, Object value) throws IOException { new Json5Writer().writeTo(output, value); }
    public static void writeTo(OutputStream output, Object value) throws IOException { new Json5Writer().writeTo(output, value); }

    /** Saves compact JSON5 by atomic file replacement. See Json5Writer.writeAtomicallyTo for guarantees and limits. */
    public static void writeAtomicallyTo(Path output,Object value) throws IOException {
        new Json5Writer().writeAtomicallyTo(output,value);
    }

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
        return new Parser(source, maxDepth, false, false).parse();
    }

    /** Parses with immutable options; decimal precision can be preserved without passing through Double. */
    public static Object parse(String source, Json5ReadOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return new Parser(source, options.getMaxDepth(), options.isBigDecimals(), options.isStrictJson()).parse();
    }

    /** Parses an object root, rejecting arrays and primitive roots. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String source) {
        return parseObject(source, Json5ReadOptions.DEFAULT);
    }

    /** Parses an object root using the supplied options. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String source, Json5ReadOptions options) {
        Object value = parse(source, options);
        if (!(value instanceof Map)) throw new IllegalArgumentException("Root value must be an object");
        return (Map<String, Object>) value;
    }

    /** Reads without retaining the source text or closing the reader. The returned value tree is materialized. */
    public static Object parse(Reader reader) throws IOException {
        return parse(reader, Json5ReadOptions.DEFAULT);
    }

    /** Reads to the end using the supplied options without closing the caller's reader. */
    public static Object parse(Reader reader, Json5ReadOptions options) throws IOException {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(options, "options");
        try(Json5StreamReader stream=stream(reader,options)) {
            Object value=stream.readValue();stream.requireEnd();return value;
        }
    }

    /** Opens a pull reader over a borrowed character source. */
    public static Json5StreamReader stream(Reader input) { return stream(input,Json5ReadOptions.DEFAULT); }
    public static Json5StreamReader stream(Reader input,Json5ReadOptions options) { return new Json5StreamReader(input,options); }
    public static Json5StreamReader stream(InputStream input) { return stream(input,Json5ReadOptions.DEFAULT); }
    public static Json5StreamReader stream(InputStream input,Json5ReadOptions options) {
        Objects.requireNonNull(input,"input");
        return stream(new InputStreamReader(input,StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)),options);
    }
    public static Json5StreamReader stream(Path path) throws IOException { return stream(path,Json5ReadOptions.DEFAULT); }
    /** Opens and owns a strictly decoded UTF-8 file reader. Close the returned reader. */
    public static Json5StreamReader stream(Path path,Json5ReadOptions options) throws IOException {
        Objects.requireNonNull(options,"options");
        return new Json5StreamReader(Files.newBufferedReader(path,StandardCharsets.UTF_8),options,true);
    }

    /** Reads strictly decoded UTF-8 without closing the caller's stream. */
    public static Object parse(InputStream stream) throws IOException {
        return parse(stream, Json5ReadOptions.DEFAULT);
    }

    /** Reads strictly decoded UTF-8 using options, without closing the caller's stream. */
    public static Object parse(InputStream stream, Json5ReadOptions options) throws IOException {
        Objects.requireNonNull(stream, "stream");
        return parse(new InputStreamReader(stream, StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)), options);
    }

    /** Reads a UTF-8 file and closes the stream opened by this method. */
    public static Object parse(Path path) throws IOException {
        return parse(path, Json5ReadOptions.DEFAULT);
    }

    /** Reads a UTF-8 file using options and closes the internally opened stream. */
    public static Object parse(Path path, Json5ReadOptions options) throws IOException {
        Objects.requireNonNull(options, "options");
        try (InputStream stream = Files.newInputStream(path)) { return parse(stream, options); }
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

    /** Creates an ordered object helper. Duplicate keys use the last value. */
    @SafeVarargs
    public static Json5Object object(Map.Entry<String,?>... entries) {
        Objects.requireNonNull(entries,"entries");
        Json5Object object=new Json5Object();
        for(Map.Entry<String,?> entry:entries)object.withEntry(entry);
        return object;
    }
    /** Creates an array helper. Cast a Java array to Object to store it as one nested value. */
    public static Json5Array array(Object... values) {
        Objects.requireNonNull(values,"values");
        Json5Array array=new Json5Array();
        for(Object value:values)array.add(value);
        return array;
    }
    public static Json5Entry entry(String key,Object value) { return new Json5Entry(key,value); }
    /** Escapes a double-quoted body without adding quotes. Valid in JSON and JSON5. */
    public static String escape(String text) { return Json5Strings.escape(text); }
    /** Decodes a double-quoted body using JSON5 escape rules. */
    public static String unescape(String body) { return Json5Strings.unescape(body); }
    public static String unescape(String body,Json5ReadOptions options) { return Json5Strings.unescape(body,options); }
}
