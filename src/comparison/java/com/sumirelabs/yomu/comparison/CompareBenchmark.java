package com.sumirelabs.yomu.comparison;

import com.sumirelabs.yomu.Yomu;
import com.cedarsoftware.io.JsonIo;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;
import com.google.gson.ToNumberPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import dev.nolij.zson.Zson;
import dev.nolij.zson.ZsonValue;
import com.github.cao.awa.cason.serialize.parser.JSONParser;
import com.github.cao.awa.cason.obj.JSONObject;
import com.github.cao.awa.cason.array.JSONArray;
import com.github.cao.awa.cason.primary.*;
import kotlin.Unit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.results.format.ResultFormatFactory;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Parse to each library's native complete tree. Normalization is outside the timed region. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class CompareBenchmark {
    static final String[] PARSERS = {"yomu", "jsonIo", "zson", "cason", "gson", "jackson"};
    static final String[] WORKLOADS = {"modSmall", "modLarge", "numbers", "escaped", "escapedUnique", "longEscaped", "commonSmall", "commonEscaped"};
    @Param({"yomu", "jsonIo", "zson", "cason", "gson", "jackson"}) public String parser;
    @Param({"modSmall", "modLarge", "numbers", "escaped", "escapedUnique", "longEscaped", "commonSmall", "commonEscaped"}) public String workload;
    private Function<String, Object> parse;
    private String input;

    @Setup public void setup() {
        input = input(workload);
        parse = parser(parser);
        if (!normalize(Yomu.parse(input)).equals(normalize(parse.apply(input)))) {
            throw new IllegalStateException("Parsed value mismatch for " + parser + "/" + workload);
        }
    }

    @Benchmark public Object parseTree() { return parse.apply(input); }

    static Function<String, Object> parser(String name) {
        switch (name) {
            case "yomu": return Yomu::parse;
            case "jsonIo": return s -> JsonIo.toMaps(s).asClass(Map.class);
            case "zson": return Zson::parseString;
            case "cason": return s -> JSONParser.Companion.parseObject(s);
            case "gson": {
                Gson gson = new GsonBuilder().setStrictness(Strictness.LENIENT)
                        .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();
                return s -> gson.fromJson(s, Object.class);
            }
            case "jackson": {
                ObjectMapper mapper = JsonMapper.builder()
                        .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS, JsonReadFeature.ALLOW_SINGLE_QUOTES,
                                JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES, JsonReadFeature.ALLOW_TRAILING_COMMA,
                                JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS, JsonReadFeature.ALLOW_LEADING_PLUS_SIGN_FOR_NUMBERS,
                                JsonReadFeature.ALLOW_LEADING_DECIMAL_POINT_FOR_NUMBERS, JsonReadFeature.ALLOW_TRAILING_DECIMAL_POINT_FOR_NUMBERS,
                                JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS, JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
                        .build();
                return s -> {
                    try { return mapper.readValue(s, Object.class); }
                    catch (java.io.IOException e) { throw new IllegalArgumentException(e.getMessage(), e); }
                };
            }
            default: throw new IllegalArgumentException(name);
        }
    }

    static String input(String name) {
        if (name.equals("commonSmall")) return "{/* mod settings */ enabled:true,rate:20,name:'すみれ',colors:[255,1194684],"
                + "world:{dimension:'minecraft:overworld',height:256,scale:0.5}}";
        String small = "{/* mod settings */ enabled:true, rate:20, name:'すみれ', colors:[0xff,0x123abc,],"
                + "world:{dimension:'minecraft:overworld',height:256,scale:.5,},}";
        if (name.equals("modSmall")) return small;
        StringBuilder text = new StringBuilder("{entries:[");
        int count = name.equals("modLarge") ? 128 : name.equals("longEscaped") ? 4 : 64;
        for (int i = 0; i < count; i++) {
            if (name.equals("numbers")) text.append("[0,1,-1,9223372036854775807,-9223372036854775808,0xabcdef,.125,1.,2e-10,]");
            else if (name.equals("modLarge")) text.append(small);
            else if (name.equals("longEscaped")) {
                text.append("{text:'");
                for (int j = 0; j < 1024; j++) text.append("abc\\n\\t\\u3042");
                text.append(i).append("'}");
            } else {
                text.append("{text:'escaped\\nline\\tvalue\\u0041\\u0042");
                if (name.equals("escapedUnique")) text.append(i);
                text.append("',path:'assets\\\\sumire");
                if (name.equals("escapedUnique")) text.append(i);
                text.append("',label:'日本語とEnglish");
                if (name.equals("escapedUnique")) text.append(i);
                text.append("',}");
            }
            text.append(',');
        }
        String result = text.append("],}").toString();
        // Generated strings contain no comma/bracket sequences; this produces a shared JSON5 subset.
        return name.equals("commonEscaped") ? result.replace(",}", "}").replace(",]", "]") : result;
    }

    static Object normalize(Object value) {
        if (value instanceof ZsonValue) return normalize(((ZsonValue) value).value);
        if (value instanceof JSONObject) {
            Map<String,Object> result = new LinkedHashMap<>();
            ((JSONObject) value).forEach(e -> { result.put(e.getKey(), normalize(e.getValue())); return Unit.INSTANCE; });
            return result;
        }
        if (value instanceof JSONArray) {
            List<Object> result = new ArrayList<>();
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.size(); i++) result.add(normalize(array.get(i)));
            return result;
        }
        if (value instanceof JSONString) return ((JSONString) value).asString();
        if (value instanceof JSONBoolean) return ((JSONBoolean) value).getValue();
        if (value instanceof JSONNull) return null;
        if (value instanceof JSONNumber) {
            JSONNumber n = (JSONNumber) value;
            if (n.isNaN()) return "#NaN";
            if (n.isInfinity()) return n.isNegativeInfinity() ? "#-Infinity" : "#Infinity";
            return n.asBigDecimal().stripTrailingZeros();
        }
        if (value instanceof Number) {
            if ((value instanceof Double || value instanceof Float) && Double.doubleToRawLongBits(((Number) value).doubleValue()) == Long.MIN_VALUE) return "#negative-zero";
            if ((value instanceof Double || value instanceof Float) && !Double.isFinite(((Number) value).doubleValue())) return "#" + value;
            return new BigDecimal(value.toString()).stripTrailingZeros();
        }
        if (value instanceof Map) {
            Map<Object,Object> result = new LinkedHashMap<>();
            for (Map.Entry<?,?> e : ((Map<?,?>) value).entrySet()) result.put(e.getKey(), normalize(e.getValue()));
            return result;
        }
        if (value instanceof Iterable || value != null && value.getClass().isArray()) {
            List<Object> result = new ArrayList<>();
            if (value instanceof Iterable) for (Object item : (Iterable<?>) value) result.add(normalize(item));
            else for (int i = 0; i < Array.getLength(value); i++) result.add(normalize(Array.get(value, i)));
            return result;
        }
        return value;
    }

    /** --check [report.json], or --run results.json [parser filter]. Unsupported pairs are reported, never timed. */
    public static void main(String[] args) throws Exception {
        boolean run = args.length > 0 && args[0].equals("--run");
        Map<String,Object> report = new LinkedHashMap<>();
        Map<String,List<String>> supported = new LinkedHashMap<>();
        for (String parser : PARSERS) {
            Map<String,Object> cases = new LinkedHashMap<>();
            List<String> accepted = new ArrayList<>();
            for (String workload : WORKLOADS) {
                CompareBenchmark bench = new CompareBenchmark();
                bench.parser = parser; bench.workload = workload;
                try { bench.setup(); cases.put(workload, "equivalent"); accepted.add(workload); }
                catch (RuntimeException e) { cases.put(workload, e.getClass().getSimpleName() + ": " + e.getMessage().replace('\n', ' ')); }
            }
            report.put(parser, cases);
            supported.put(parser, accepted);
        }
        Map<String,Object> features = new LinkedHashMap<>();
        Map<String,String> probes = new LinkedHashMap<>();
        probes.put("comments", "{/* block */x:1// line\n}");
        probes.put("singleQuotes", "{x:'text'}");
        probes.put("unquotedKeys", "{x:1}");
        probes.put("objectTrailingComma", "{x:1,}");
        probes.put("arrayTrailingComma", "{x:[1,]}");
        probes.put("hexNumber", "{x:0xff}");
        probes.put("leadingPlus", "{x:+1}");
        probes.put("leadingDecimalPoint", "{x:.5}");
        probes.put("trailingDecimalPoint", "{x:1.}");
        probes.put("nonFinite", "{x:[Infinity,-Infinity,NaN]}");
        probes.put("hexEscape", "{x:'\\x41'}");
        probes.put("verticalTabEscape", "{x:'\\v'}");
        probes.put("lineContinuation", "{x:'a\\\nb'}");
        probes.put("escapedIdentifier", "{\\u0061:1}");
        probes.put("negativeZero", "{x:-0.0}");
        for (String name : PARSERS) {
            Function<String,Object> parse = parser(name);
            Map<String,Object> cases = new LinkedHashMap<>();
            for (Map.Entry<String,String> probe : probes.entrySet()) {
                try {
                    cases.put(probe.getKey(), normalize(Yomu.parse(probe.getValue())).equals(normalize(parse.apply(probe.getValue()))) ? "equivalent" : "different value");
                } catch (RuntimeException e) { cases.put(probe.getKey(), "rejected"); }
            }
            features.put(name, cases);
        }
        report.put("featureProbes", features);
        String json = Yomu.stringify(report);
        System.out.println(json);
        if (!run) {
            if (args.length > 1) Files.write(Paths.get(args[1]), json.getBytes(StandardCharsets.UTF_8));
            return;
        }
        String output = args.length > 1 ? args[1] : "build/comparison-results.json";
        Files.write(Paths.get(output + ".compatibility.json"), json.getBytes(StandardCharsets.UTF_8));
        Collection<RunResult> results = new ArrayList<>();
        for (String parser : PARSERS) {
            if (args.length > 2 && !parser.equals(args[2])) continue;
            if (supported.get(parser).isEmpty()) continue;
            results.addAll(new Runner(new OptionsBuilder().include(CompareBenchmark.class.getName() + ".parseTree")
                    .param("parser", parser).param("workload", supported.get(parser).toArray(new String[0]))
                    .forks(2).warmupIterations(3).measurementIterations(3)
                    .warmupTime(TimeValue.seconds(1)).measurementTime(TimeValue.seconds(1))
                    .addProfiler("gc").shouldFailOnError(true).build()).run());
        }
        ResultFormatFactory.getInstance(ResultFormatType.JSON, output).writeOut(results);
    }
}
