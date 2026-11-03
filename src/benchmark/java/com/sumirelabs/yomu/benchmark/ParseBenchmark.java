package com.sumirelabs.yomu.benchmark;

import com.cedarsoftware.io.JsonIo;
import com.sumirelabs.yomu.Yomu;
import org.openjdk.jmh.annotations.*;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.math.BigDecimal;
import java.lang.reflect.Array;
import java.util.concurrent.TimeUnit;

/** Same JSON5 input, both libraries materialize complete map trees. No I/O in timed methods. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class ParseBenchmark {
    @Param({"modSmall", "modLarge", "numbers", "escaped"})
    public String workload;
    private String source;

    @Setup public void setup() {
        String small = "{/* mod settings */ enabled:true, rate:20, name:'すみれ', colors:[0xff,0x123abc,],"
                + "world:{dimension:'minecraft:overworld',height:256,scale:.5,},}";
        if ("modSmall".equals(workload)) source = small;
        else {
            String item;
            int size;
            if ("numbers".equals(workload)) {
                item = "[0,1,-1,9223372036854775807,-9223372036854775808,0xabcdef,.125,1.,2e-10,]";
                size = 64;
            } else if ("escaped".equals(workload)) {
                item = "{text:'escaped\\nline\\tvalue\\u0041\\u0042',path:'assets\\\\sumire',label:'日本語とEnglish',}";
                size = 64;
            } else {
                item = small;
                size = 128;
            }
            StringBuilder text = new StringBuilder("{entries:[");
            for (int i = 0; i < size; i++) text.append(item).append(',');
            source = text.append("],}").toString();
        }
        Map<String, Object> a = Yomu.parseObject(source);
        Map<?, ?> b = JsonIo.toMaps(source).asClass(Map.class);
        if (!normalize(a).equals(normalize(b))) throw new IllegalStateException("Parsed value mismatch");
    }

    // Untimed semantic check: normalize container and number representations, then compare complete trees.
    private static Object normalize(Object value) {
        if (value instanceof Number) return new BigDecimal(value.toString()).stripTrailingZeros();
        if (value instanceof Map) {
            Map<Object, Object> result = new LinkedHashMap<Object, Object>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) result.put(e.getKey(), normalize(e.getValue()));
            return result;
        }
        if (value instanceof Iterable) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (Iterable<?>) value) result.add(normalize(item));
            return result;
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> result = new ArrayList<Object>();
            for (int i = 0; i < Array.getLength(value); i++) result.add(normalize(Array.get(value, i)));
            return result;
        }
        return value;
    }

    @Benchmark public Object yomu() { return Yomu.parse(source); }
    @Benchmark public Object jsonIo() { return JsonIo.toMaps(source).asClass(Map.class); }
}
