package com.sumirelabs.yomu.benchmark;

import com.sumirelabs.yomu.Yomu;
import com.cedarsoftware.io.JsonIo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.util.BufferRecycler;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * Fresh-process cold measurement and batched steady-state measurements.
 * Source generation, normalization, sizing, and report serialization are outside the measured region.
 */
public final class LifecycleBenchmark {
    private static volatile Object sink;
    private static final int BATCH = 128;
    private static final int SAMPLES = Integer.getInteger("yomu.benchmark.samples", 6);
    private static final int OPERATIONS_PER_SAMPLE = Integer.getInteger("yomu.benchmark.operations", 4096);
    private static final long WARMUP_NANOS = Long.getLong("yomu.benchmark.warmupMillis", 1500L) * 1000000L;

    private LifecycleBenchmark() { }

    private static final class Adapter {
        final String name;
        ObjectMapper jackson;
        Adapter(String name) { this.name = name; }
        Object parse(String input) {
            if (name.equals("yomu")) return Yomu.parse(input);
            if (name.equals("jsonIo")) return JsonIo.toMaps(input).asClass(Map.class);
            if (name.equals("jackson")) {
                if (jackson == null) jackson = JsonMapper.builder()
                        .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS, JsonReadFeature.ALLOW_SINGLE_QUOTES,
                                JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES, JsonReadFeature.ALLOW_TRAILING_COMMA,
                                JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS, JsonReadFeature.ALLOW_LEADING_PLUS_SIGN_FOR_NUMBERS,
                                JsonReadFeature.ALLOW_LEADING_DECIMAL_POINT_FOR_NUMBERS, JsonReadFeature.ALLOW_TRAILING_DECIMAL_POINT_FOR_NUMBERS,
                                JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS, JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
                        .build();
                try { return jackson.readValue(input, Object.class); }
                catch (java.io.IOException e) { throw new IllegalArgumentException(e); }
            }
            throw new IllegalArgumentException(name);
        }
    }

    static String input(String dataset, long document) {
        String serial = Long.toHexString(document);
        serial = "0000000000000000".substring(serial.length()) + serial;
        StringBuilder result = new StringBuilder(12000).append("{/* settings */entries:[");
        for (int i = 0; i < 64; i++) {
            if (i != 0) result.append(',');
            String row = dataset.equals("distinct") ? "/" + (i < 10 ? "0" : "") + i : "";
            result.append("{text:'escaped\\nline\\tvalue\\u0041\\u0042/").append(serial).append(row)
                    .append("',path:'assets\\\\sumire/").append(serial).append(row)
                    .append("',label:'日本語とEnglish/").append(serial).append(row).append("'}");
        }
        return result.append("]}").toString();
    }

    private static Map<String,Object> expected(String dataset, long document) {
        String serial = Long.toHexString(document);
        serial = "0000000000000000".substring(serial.length()) + serial;
        List<Object> rows = new ArrayList<Object>();
        for (int i = 0; i < 64; i++) {
            String suffix = "/" + serial + (dataset.equals("distinct") ? "/" + (i < 10 ? "0" : "") + i : "");
            Map<String,Object> row = new LinkedHashMap<String,Object>();
            row.put("text", "escaped\nline\tvalueAB" + suffix);
            row.put("path", "assets\\sumire" + suffix);
            row.put("label", "日本語とEnglish" + suffix);
            rows.add(row);
        }
        return Collections.<String,Object>singletonMap("entries", rows);
    }

    private static Object normalize(Object value) {
        if (value instanceof Map) {
            Map<Object,Object> result = new LinkedHashMap<Object,Object>();
            for (Map.Entry<?,?> entry : ((Map<?,?>)value).entrySet()) result.put(entry.getKey(), normalize(entry.getValue()));
            return result;
        }
        if (value instanceof Iterable || value != null && value.getClass().isArray()) {
            List<Object> result = new ArrayList<Object>();
            if (value instanceof Iterable) for (Object item : (Iterable<?>)value) result.add(normalize(item));
            else for (int i=0; i<Array.getLength(value); i++) result.add(normalize(Array.get(value,i)));
            return result;
        }
        if (value instanceof Number) return new BigDecimal(value.toString()).stripTrailingZeros();
        return value;
    }

    private static void check(Object result, String dataset, long serial) {
        if (!expected(dataset, serial).equals(normalize(result))) throw new AssertionError("Value mismatch: " + dataset + "/" + serial);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--check")) {
            for (String parser : new String[] {"yomu", "jsonIo", "jackson"}) {
                for (String dataset : new String[] {"repeated", "distinct"}) {
                    Adapter adapter = new Adapter(parser);
                    for (long id : new long[] {0, 1, 255, 65536}) check(adapter.parse(input(dataset,id)), dataset, id);
                }
            }
            System.out.println("All three parsers match independently constructed expected values.");
            return;
        }
        if (args.length != 4) throw new IllegalArgumentException("parser cold|repeat|changing repeated|distinct output.json");
        String name = args[0], scenario = args[1], dataset = args[2];
        if (SAMPLES < 1 || OPERATIONS_PER_SAMPLE < BATCH || OPERATIONS_PER_SAMPLE % BATCH != 0 || WARMUP_NANOS < 0) {
            throw new IllegalArgumentException("Positive samples, batch-aligned operations and nonnegative warmup required");
        }
        if (!scenario.equals("cold") && !scenario.equals("repeat") && !scenario.equals("changing")) throw new IllegalArgumentException(scenario);
        if (!dataset.equals("repeated") && !dataset.equals("distinct")) throw new IllegalArgumentException(dataset);
        Adapter adapter = new Adapter(name);
        com.sun.management.ThreadMXBean allocations = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!allocations.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Thread allocation measurement unavailable");
        allocations.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().getId();
        allocations.getThreadAllocatedBytes(thread);
        String repeated = input(dataset,0);
        List<Object> measurements = new ArrayList<Object>();
        long nextDocument = 1;
        long lastDocument = 0;
        if (scenario.equals("cold")) {
            long startBytes = allocations.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            sink = adapter.parse(repeated);
            long elapsed = System.nanoTime() - start;
            long allocated = allocations.getThreadAllocatedBytes(thread) - startBytes;
            measurements.add(sample(elapsed, allocated, 1));
        } else {
            long warmupEnd = System.nanoTime() + WARMUP_NANOS;
            while (System.nanoTime() < warmupEnd) {
                lastDocument = scenario.equals("changing") ? nextDocument++ : 0;
                sink = adapter.parse(lastDocument == 0 ? repeated : input(dataset,lastDocument));
            }
            for (int sample = 0; sample < SAMPLES; sample++) {
                long elapsed = 0, allocated = 0;
                for (int done = 0; done < OPERATIONS_PER_SAMPLE; done += BATCH) {
                    String[] inputs = new String[BATCH];
                    for (int i = 0; i < BATCH; i++) {
                        lastDocument = scenario.equals("changing") ? nextDocument++ : 0;
                        inputs[i] = lastDocument == 0 ? repeated : input(dataset,lastDocument);
                    }
                    long startBytes = allocations.getThreadAllocatedBytes(thread);
                    long start = System.nanoTime();
                    for (String source : inputs) sink = adapter.parse(source);
                    elapsed += System.nanoTime() - start;
                    allocated += allocations.getThreadAllocatedBytes(thread) - startBytes;
                }
                measurements.add(sample(elapsed, allocated, OPERATIONS_PER_SAMPLE));
            }
        }
        // Measure the graph before iteration/normalization can create Map view objects.
        GraphMeter meter = new GraphMeter();
        long resultGraphBytes = meter.add(sink);
        long knownCacheAdditionalBytes = 0;
        String cacheScope = "No parse state is retained by Yomu after returning the result";
        if (name.equals("jsonIo")) {
            ThreadLocal<?> recycler = (ThreadLocal<?>) GraphMeter.field(JsonIo.class, "BUFFER_RECYCLER");
            knownCacheAdditionalBytes = meter.add(recycler.get());
            cacheScope = "Current thread BufferRecycler: character buffers and string cache; other library caches excluded";
        } else if (name.equals("jackson")) {
            BufferRecycler recycler = adapter.jackson.getFactory()._getBufferRecycler();
            try {
                knownCacheAdditionalBytes += meter.add(GraphMeter.field(recycler, "_byteBuffers"));
                knownCacheAdditionalBytes += meter.add(GraphMeter.field(recycler, "_charBuffers"));
            } finally { recycler.releaseToPool(); }
            knownCacheAdditionalBytes += meter.add(GraphMeter.field(adapter.jackson.getFactory(), "_rootCharSymbols"));
            cacheScope = "Current recycler byte/char buffers and root character-name symbols; mapper/type caches excluded";
        }
        check(sink, dataset, lastDocument);
        Map<String,Object> report = new LinkedHashMap<String,Object>();
        report.put("parser",name);
        report.put("scenario",scenario);
        report.put("dataset",dataset);
        report.put("javaVersion",System.getProperty("java.version"));
        report.put("vm",System.getProperty("java.vm.name"));
        report.put("samples",measurements);
        report.put("warmupNanos",scenario.equals("cold") ? 0 : WARMUP_NANOS);
        report.put("resultGraphBytes",resultGraphBytes);
        report.put("knownCacheAdditionalBytes",knownCacheAdditionalBytes);
        report.put("cacheScope",cacheScope);
        report.put("lastDocumentSerial",lastDocument);
        report.put("valueCheck","passed");
        java.nio.file.Path output = Paths.get(args[3]);
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        Files.write(output, Yomu.stringify(report).getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String,Object> sample(long nanos, long bytes, int operations) {
        Map<String,Object> value = new LinkedHashMap<String,Object>();
        value.put("operations", operations);
        value.put("nanos", nanos);
        value.put("allocatedBytes", bytes);
        return value;
    }
}
