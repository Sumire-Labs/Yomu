package com.sumirelabs.yomu.mapper;

import com.sumirelabs.yomu.Yomu;
import org.junit.Test;
import java.io.Serializable;
import java.lang.reflect.ParameterizedType;
import java.math.BigDecimal;
import java.nio.file.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class CodecTest {
    static final class ResourceId {
        final String value;
        ResourceId(String value) {
            if (!value.matches("[a-z0-9_]+:[a-z0-9_/]+")) throw new IllegalArgumentException("Invalid resource id");
            this.value=value;
        }
    }
    static final Codec<ResourceId> ID = Codecs.string(ResourceId::new,id -> id.value);
    public static class Config {
        public ResourceId selected;
        public List<ResourceId> dimensions;
        public Map<String,ResourceId> entries;
        public ResourceId[] array;
    }
    public static class Box<T> { public T value; public List<T> values; }
    public static class IdBox extends Box<ResourceId> { }
    static final class Point { final int x,y; Point(int x,int y) { this.x=x; this.y=y; } }
    public static class PointHolder { public Point point; }

    @Test public void customTypeWorksAtRootFieldsAndContainers() {
        Json5Mapper mapper=new Json5Mapper().withCodec(ResourceId.class,ID);
        Config config=mapper.read("{selected:'sumire:test',dimensions:['minecraft:overworld'],entries:{a:'sumire:a'},array:['sumire:b']}",Config.class);
        assertEquals("sumire:test",config.selected.value);
        assertEquals("minecraft:overworld",config.dimensions.get(0).value);
        assertEquals("sumire:a",config.entries.get("a").value);
        assertEquals("sumire:b",config.array[0].value);
        Config copy=mapper.read(mapper.write(config),Config.class);
        assertEquals(config.selected.value,copy.selected.value);
        assertEquals("sumire:b",mapper.read(mapper.write(config.array),ResourceId[].class)[0].value);
        assertEquals("sumire:test",mapper.read(mapper.write(config.selected),ResourceId.class).value);
        Box<ResourceId> box=mapper.read("{value:'sumire:x',values:['sumire:y']}",new TypeRef<Box<ResourceId>>(){});
        assertEquals("sumire:y",mapper.read(mapper.write(box),IdBox.class).values.get(0).value);
    }

    @Test public void parameterizedCodecUsesStructuralTypeKeys() {
        TypeRef<List<ResourceId>> type=new TypeRef<List<ResourceId>>(){};
        Codec<List<ResourceId>> list=Codecs.string(text -> {
            List<ResourceId> result=new ArrayList<ResourceId>();
            for(String part:text.split(";"))result.add(new ResourceId(part));
            return result;
        }, ids -> { StringBuilder result=new StringBuilder(); for(ResourceId id:ids){if(result.length()>0)result.append(';');result.append(id.value);}return result.toString(); });
        Json5Mapper mapper=new Json5Mapper().withCodec(ResourceId.class,ID).withCodec(type,list);
        List<ResourceId> ids=mapper.read("'sumire:a;sumire:b'",new TypeRef<List<ResourceId>>(){});
        assertEquals("sumire:b",ids.get(1).value);
        assertEquals("\"sumire:a;sumire:b\"",mapper.writeAs(ids,type));
        // Without a declared type, runtime erasure does not select List<ResourceId>'s codec.
        assertEquals(Arrays.asList("sumire:a","sumire:b"),Yomu.parse(mapper.write(ids)));
        Config config=new Config(); config.dimensions=ids;
        assertEquals("sumire:a;sumire:b",Yomu.parseObject(mapper.write(config)).get("dimensions"));
        assertEquals("sumire:a",mapper.read(mapper.write(config),Config.class).dimensions.get(0).value);
    }

    @Test public void contextSupportsNestedConversionsAndFieldPaths() {
        Codec<Point> point=new Codec<Point>() {
            public Point decode(Object value,CodecContext c) {
                assertEquals(Point.class,c.getTargetType());
                Map<?,?> map=c.read(value,Map.class);
                return new Point(c.atField("x").read(map.get("x"),int.class),c.atField("y").read(map.get("y"),int.class));
            }
            public Object encode(Point value,CodecContext c) {
                Map<String,Object> result=new LinkedHashMap<String,Object>();result.put("x",value.x);result.put("y",value.y);return result;
            }
        };
        Json5Mapper mapper=new Json5Mapper().withCodec(Point.class,point);
        assertEquals(2,mapper.read("{x:1,y:2}",Point.class).y);
        assertEquals(3,mapper.read(mapper.write(new Point(3,4)),Point.class).x);
        fails(() -> mapper.read("{point:{x:1,y:'bad'}}",PointHolder.class),"$[\"point\"][\"y\"]");
    }

    @Test public void contextsExposeResolvedGenericArguments() {
        TypeRef<Box<ResourceId>> type=new TypeRef<Box<ResourceId>>(){};
        Codec<Box<ResourceId>> codec=new Codec<Box<ResourceId>>() {
            public Box<ResourceId> decode(Object value,CodecContext c) {
                assertEquals(ResourceId.class,((ParameterizedType)c.getTargetType()).getActualTypeArguments()[0]);
                Box<ResourceId> result=new Box<ResourceId>();result.value=c.read(value,ResourceId.class);return result;
            }
            public Object encode(Box<ResourceId> value,CodecContext c) { return c.toValue(value.value); }
        };
        Json5Mapper mapper=new Json5Mapper().withCodec(ResourceId.class,ID).withCodec(type,codec);
        assertEquals("\"sumire:a\"",mapper.writeAs(mapper.read("'sumire:a'",type),type));
    }

    @Test public void registrationIsImmutableAndOptionsPreserveCodecs() {
        Json5Mapper original=new Json5Mapper();
        Json5Mapper registered=original.withCodec(ResourceId.class,ID);
        fails(() -> original.read("'sumire:a'",ResourceId.class),"$");
        assertEquals("sumire:a",registered.withFailOnUnknownFields(true)
                .withWriter(new com.sumirelabs.yomu.Json5Writer().withIndent(" "))
                .read("'sumire:a'",ResourceId.class).value);
        Json5Mapper override=registered.withCodec(ResourceId.class,Codecs.string(text -> new ResourceId("fixed:value"),id -> id.value));
        assertEquals("fixed:value",override.read("'anything'",ResourceId.class).value);
        assertEquals("sumire:a",registered.read("'sumire:a'",ResourceId.class).value);
    }

    @Test public void nullIsHandledWithoutCallingCodecs() {
        Codec<String> codec=new Codec<String>() {
            public String decode(Object v,CodecContext c){throw new AssertionError();}
            public Object encode(String v,CodecContext c){throw new AssertionError();}
        };
        Json5Mapper mapper=new Json5Mapper().withCodec(String.class,codec);
        assertNull(mapper.read("null",String.class));
        assertEquals("null",mapper.writeAs(null,String.class));
    }

    @Test public void codecFailuresRetainTheirCauseAndLocation() {
        Json5Mapper mapper=new Json5Mapper().withCodec(ResourceId.class,ID);
        try { mapper.read("{selected:'not valid'}",Config.class);fail(); }
        catch(MappingException e) { assertEquals("$[\"selected\"]",e.getPath());assertTrue(e.getCause() instanceof IllegalArgumentException); }
        fails(() -> mapper.read("{selected:1}",Config.class),"$[\"selected\"]");
    }

    @Test public void returnedCoreValuesAreValidatedWithoutApplyingCodecsAgain() {
        Codec<String> tagged=Codecs.string(text -> text.substring(1),text -> "!"+text);
        Json5Mapper mapper=new Json5Mapper().withCodec(String.class,tagged);
        assertEquals("\"!value\"",mapper.write("value"));
        assertEquals("value",mapper.read(mapper.write("value"),String.class));
        Codec<ResourceId> bad=new Codec<ResourceId>() {
            public ResourceId decode(Object v,CodecContext c){return new ResourceId("sumire:a");}
            public Object encode(ResourceId v,CodecContext c){return Collections.singletonMap(1,v);}
        };
        fails(() -> new Json5Mapper().withCodec(ResourceId.class,bad).write(new ResourceId("sumire:a")),"$");
    }

    @Test public void recursiveCodecsAndEncodedCyclesFailClearly() {
        Codec<ResourceId> recursion=new Codec<ResourceId>() {
            public ResourceId decode(Object v,CodecContext c){return c.read(v,ResourceId.class);}
            public Object encode(ResourceId v,CodecContext c){return c.toValue(v);}
        };
        Json5Mapper mapper=new Json5Mapper().withCodec(ResourceId.class,recursion);
        fails(() -> mapper.read("'sumire:a'",ResourceId.class),"$");
        fails(() -> mapper.write(new ResourceId("sumire:a")),"$");
        Codec<ResourceId> cycle=new Codec<ResourceId>() {
            public ResourceId decode(Object v,CodecContext c){return new ResourceId("sumire:a");}
            public Object encode(ResourceId v,CodecContext c){Map<String,Object> map=new HashMap<String,Object>();map.put("self",map);return map;}
        };
        fails(() -> new Json5Mapper().withCodec(ResourceId.class,cycle).write(new ResourceId("sumire:a")),"$[\"self\"]");
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    @Test public void invalidCodecResultAndAmbiguousInterfacesAreRejected() {
        Codec wrong=new Codec() {
            public Object decode(Object v,CodecContext c){return 1;}
            public Object encode(Object v,CodecContext c){return v;}
        };
        fails(() -> new Json5Mapper().withCodec((Class)ResourceId.class,wrong).read("'sumire:a'",ResourceId.class),"$");
        Codec<CharSequence> chars=Codecs.string(text -> new StringBuilder(text),CharSequence::toString);
        Codec<Serializable> serial=Codecs.string(text -> text,Object::toString);
        Json5Mapper mapper=new Json5Mapper().withCodec(CharSequence.class,chars).withCodec(Serializable.class,serial);
        fails(() -> mapper.write(new StringBuilder("a")),"$");
        assertEquals("\"a\"",mapper.writeAs(new StringBuilder("a"),CharSequence.class));
        assertEquals("a",mapper.read("'a'",CharSequence.class).toString());
    }

    @Test public void standardCodecsRoundTripWithoutPrivateJdkReflection() throws Exception {
        Json5Mapper mapper=new Json5Mapper().withStandardCodecs();
        Object[][] cases={
            {UUID.class,UUID.fromString("123e4567-e89b-12d3-a456-426614174000")},
            {URI.class,URI.create("https://example.test/path?q=x")},
            {Path.class,Paths.get("config","sumire.json5")},{java.io.File.class,new java.io.File("config/sumire.json5")},
            {Date.class,new Date(1234567890123L)},{Instant.class,Instant.parse("2026-10-22T01:02:03.123456789Z")},
            {LocalDate.class,LocalDate.of(2024,2,29)},{LocalTime.class,LocalTime.of(12,34,56,123456789)},
            {LocalDateTime.class,LocalDateTime.parse("2026-10-22T12:34:56")},
            {OffsetDateTime.class,OffsetDateTime.parse("2026-10-22T12:34:56+09:00")},{OffsetTime.class,OffsetTime.parse("12:34:56+09:00")},
            {ZonedDateTime.class,ZonedDateTime.parse("2026-10-22T12:34:56+09:00[Asia/Tokyo]")},
            {Duration.class,Duration.ofNanos(1234567890123L)},{Period.class,Period.of(1,2,3)},
            {Year.class,Year.of(2026)},{YearMonth.class,YearMonth.of(2026,10)},{MonthDay.class,MonthDay.of(2,29)},
            {ZoneId.class,ZoneId.of("Asia/Tokyo")},{ZoneOffset.class,ZoneOffset.ofHours(9)}
        };
        for(Object[] entry:cases) assertEquals(entry[1],mapper.read(mapper.write(entry[1]),(Class<?>)entry[0]));
        URL url=new URL("https://example.test/a");
        assertEquals(url.toExternalForm(),mapper.read(mapper.write(url),URL.class).toExternalForm());
        fails(() -> mapper.read("'1-1-1-1-1'",UUID.class),"$");
        fails(() -> mapper.read("'2023-02-29'",LocalDate.class),"$");
    }

    @Test public void userCodecsOverrideStandardsRegardlessOfRegistrationOrder() {
        UUID fixed=UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        Codec<UUID> custom=Codecs.string(text -> fixed,Object::toString);
        assertEquals(fixed,new Json5Mapper().withCodec(UUID.class,custom).withStandardCodecs().read("'custom'",UUID.class));
        assertEquals(fixed,new Json5Mapper().withStandardCodecs().withCodec(UUID.class,custom).read("'custom'",UUID.class));
    }

    @Test public void codecsWorkWithFilesAndConcurrentCalls() throws Exception {
        final Json5Mapper mapper=new Json5Mapper().withCodec(ResourceId.class,ID);
        Path file=Files.createTempFile("yomu-codec",".json5");
        try { mapper.write(file,new ResourceId("sumire:a"));assertEquals("sumire:a",mapper.read(file,ResourceId.class).value); }
        finally { Files.delete(file); }
        ExecutorService executor=Executors.newFixedThreadPool(4);
        try {
            List<Future<String>> results=new ArrayList<Future<String>>();
            for(int i=0;i<100;i++) { final int id=i;results.add(executor.submit(() -> mapper.read(mapper.write(new ResourceId("sumire:item"+id)),ResourceId.class).value)); }
            for(int i=0;i<100;i++) assertEquals("sumire:item"+i,results.get(i).get());
        } finally { executor.shutdownNow(); }
    }

    private static void fails(Runnable action,String path) {
        try { action.run();fail("Expected codec error"); }
        catch(MappingException e) { assertEquals(path,e.getPath()); }
    }
}
