package com.sumirelabs.yomu.mapper;

import com.sumirelabs.yomu.Json5Writer;
import com.sumirelabs.yomu.Yomu;
import org.junit.Test;
import java.math.BigInteger;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class Json5MapperTest {
    enum Mode { FAST, SAFE }
    public static class Base { public String id = "default"; }
    public static class Config extends Base {
        @Json5Field(name = "refresh_ticks", comment = "更新間隔") public int refreshTicks = 20;
        @Json5Field public String label;
        @Json5Field private boolean enabled = true;
        @Json5Field(ignore = true) public String ignored = "hidden";
        public transient String temporary = "temporary";
        public static String global = "global";
        public Mode mode = Mode.SAFE;
        public List<Child> children = new ArrayList<Child>();
        public Map<String, List<Integer>> groups;
        public Set<Mode> modes;
        public int[] counts;
        public Character marker;
    }
    public static class Child { public String name; public double value; }
    public static class Box<T> { public T value; public T[] array; }
    public static class ChildBox extends Box<Child> { }
    public static class Children extends ArrayList<Child> { }
    public static class ChildMap extends LinkedHashMap<String, Child> { }
    public static class Nest<T> { public T value; public Nest<List<T>> child; }
    public static class NoDefault { public int value; public NoDefault(int n) { value = n; } }
    public static class FinalField { public final int fixed = 4; }
    public static class Duplicate { @Json5Field(name="x") public int a; @Json5Field(name="x") public int b; }
    public static class Link { public Link next; }
    public static class OddName { @Json5Field(name="a\"\\\n") public int number; }

    @Test public void configurationRoundTripAndComments() {
        Json5Mapper mapper = new Json5Mapper().withWriter(new Json5Writer().withIndent("  "));
        Config config = mapper.read("{refresh_ticks:40, enabled:false, label:'すみれ',mode:'FAST',children:[{name:'one',value:.5}],"
                + "groups:{a:[1,2]},modes:['FAST','SAFE'],counts:[3,4],marker:'x',unknown:true}", Config.class);
        assertEquals(40, config.refreshTicks);
        assertFalse(config.enabled);
        assertEquals("default", config.id);
        assertEquals("one", config.children.get(0).name);
        assertEquals(Arrays.asList(1,2), config.groups.get("a"));
        assertEquals(EnumSet.allOf(Mode.class), config.modes);
        assertArrayEquals(new int[] {3,4}, config.counts);
        assertEquals(Character.valueOf('x'), config.marker);
        String encoded = mapper.write(config);
        assertTrue(encoded.contains("// 更新間隔"));
        assertFalse(encoded.contains("temporary"));
        assertFalse(encoded.contains("global"));
        assertFalse(encoded.contains("ignored"));
        Config copy = mapper.read(encoded, Config.class);
        assertEquals(config.refreshTicks, copy.refreshTicks);
        assertEquals(config.children.get(0).value, copy.children.get(0).value, 0);
        assertEquals("すみれ", copy.label);
    }

    @Test public void parameterizedRootsAndInheritedBindings() {
        Json5Mapper mapper = new Json5Mapper();
        List<Child> list = mapper.read("[{name:'a'}]", new TypeRef<List<Child>>() { });
        assertEquals("a", list.get(0).name);
        Box<Child> box = mapper.read("{value:{name:'b'},array:[{name:'c'}]}", new TypeRef<Box<Child>>() { });
        assertEquals("b", box.value.name);
        assertEquals(Child[].class, box.array.getClass());
        assertEquals("c", box.array[0].name);
        assertEquals("d", mapper.read("{value:{name:'d'}}", ChildBox.class).value.name);
        assertEquals("e", mapper.read("[{name:'e'}]", Children.class).get(0).name);
        assertEquals("f", mapper.read("{item:{name:'f'}}", ChildMap.class).get("item").name);
        Nest<Integer> nested = mapper.read("{value:1,child:{value:[2],child:{value:[[3]]}}}", new TypeRef<Nest<Integer>>() { });
        assertEquals(Integer.valueOf(3), nested.child.child.value.get(0).get(0));
    }

    @Test public void numericConversionsAreChecked() {
        Json5Mapper mapper = new Json5Mapper();
        assertEquals(Integer.valueOf(2), mapper.read("2.0", Integer.class));
        assertEquals(Long.valueOf(Long.MIN_VALUE), mapper.read("-9223372036854775808", Long.class));
        assertEquals(Long.valueOf(Long.MIN_VALUE), mapper.read("-9223372036854775808.0", Long.class));
        failure(() -> mapper.read("9223372036854775808.0", Long.class), "$");
        assertEquals(new BigInteger("999999999999999999999"), mapper.read("999999999999999999999", BigInteger.class));
        assertTrue(Double.isNaN(mapper.read("NaN", Double.class)));
        assertEquals(Float.valueOf(Float.POSITIVE_INFINITY), mapper.read("Infinity", Float.class));
        for (String source : new String[] {"2147483648", "1.5", "NaN", "null", "true", "'2'"}) {
            failure(() -> mapper.read(source, int.class), "$");
        }
        failure(() -> mapper.convert(new BigDecimal("1e1000"), float.class), "$");
        failure(() -> mapper.read("3.5e38", float.class), "$");
        failure(() -> mapper.read("{refresh_ticks:1.5}", Config.class), "$[\"refresh_ticks\"]");
        failure(() -> mapper.read("128", byte.class), "$");
        failure(() -> mapper.read("-32769", short.class), "$");
        assertEquals(Byte.valueOf((byte)-128), mapper.read("-128", byte.class));
        failure(() -> mapper.convert(Collections.singletonMap("a\"\\\n", "text"), OddName.class), "$[\"a\\\"\\\\\\u000a\"]");
    }

    @Test public void nullMissingUnknownAndInvalidEnums() {
        Json5Mapper mapper = new Json5Mapper();
        assertEquals(20, mapper.read("{}", Config.class).refreshTicks);
        assertNull(mapper.read("null", Config.class));
        failure(() -> mapper.read("{enabled:null}", Config.class), "$[\"enabled\"]");
        failure(() -> mapper.read("{mode:'slow'}", Config.class), "$[\"mode\"]");
        failure(() -> mapper.withFailOnUnknownFields(true).read("{typo:1}", Config.class), "$[\"typo\"]");
        assertEquals("hidden", mapper.withFailOnUnknownFields(true).read("{ignored:'changed'}", Config.class).ignored);
        failure(() -> mapper.read("[1]", new TypeRef<List<? extends Number>>() { }), "$");
        failure(() -> mapper.read("{a:1}", new TypeRef<Map<Integer,Integer>>() { }), "$");
    }

    @Test public void constructorsFinalFieldsAndAmbiguity() {
        Json5Mapper mapper = new Json5Mapper();
        assertEquals(3L, Yomu.parseObject(mapper.write(new NoDefault(3))).get("value"));
        failure(() -> mapper.read("{value:3}", NoDefault.class), "$");
        failure(() -> mapper.read("{fixed:3}", FinalField.class), "$[\"fixed\"]");
        failure(() -> mapper.write(new Duplicate()), "$");
        failure(() -> mapper.write(new Date()), "$");
    }

    @Test public void detectsCyclesButAllowsSharedReferences() {
        Json5Mapper mapper = new Json5Mapper();
        Link a = new Link(); a.next = a;
        failure(() -> mapper.write(a), "$[\"next\"]");
        Child child = new Child(); child.name = "shared";
        assertEquals(2, ((List<?>) Yomu.parse(mapper.write(Arrays.asList(child,child)))).size());
        Map<String,Object> recursive = new HashMap<String,Object>(); recursive.put("next",recursive);
        failure(() -> mapper.convert(recursive, Link.class), "$[\"next\"]");
    }

    @Test public void mapperIsReusableAcrossThreads() throws Exception {
        final Json5Mapper mapper = new Json5Mapper();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> results = new ArrayList<Future<Integer>>();
            for (int i=0; i<100; i++) {
                final int n = i;
                results.add(executor.submit(() -> mapper.read("{refresh_ticks:" + n + "}", Config.class).refreshTicks));
            }
            for (int i=0; i<results.size(); i++) assertEquals(Integer.valueOf(i), results.get(i).get());
        } finally { executor.shutdownNow(); }
    }

    @Test public void utf8FilesAndGenericRoots() throws Exception {
        Path path = Files.createTempFile("yomu-mapper", ".json5");
        try {
            Json5Mapper mapper = new Json5Mapper();
            Child child = new Child(); child.name = "すみれ";
            mapper.write(path, Collections.singletonList(child));
            List<Child> values = mapper.read(path, new TypeRef<List<Child>>() { });
            assertEquals("すみれ", values.get(0).name);
        } finally { Files.delete(path); }
    }

    private static void failure(Runnable action, String path) {
        try { action.run(); fail("Expected mapping failure at " + path); }
        catch (MappingException e) { assertEquals(path, e.getPath()); }
    }
}
