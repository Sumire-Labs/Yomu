package com.sumirelabs.yomu.mapper;

import com.sumirelabs.yomu.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class MapEncodingTest {
    private static final TypeRef<Map<Integer,String>> INTS=new TypeRef<Map<Integer,String>>(){};
    private static Json5Mapper entries(){return new Json5Mapper().withMapEncoding(MapEncoding.ENTRY_ARRAY);}
    enum Mode { FAST, SAFE }
    public static class Key {
        public final int id;
        @Json5Creator public Key(@Json5Field(name="id") int id){this.id=id;}
        @Override public boolean equals(Object other){return other instanceof Key && ((Key)other).id==id;}
        @Override public int hashCode(){return id;}
    }
    public static class GenericKey<T> {
        public T value;
        @Override public boolean equals(Object other){return other instanceof GenericKey && Objects.equals(value,((GenericKey<?>)other).value);}
        @Override public int hashCode(){return Objects.hashCode(value);}
    }
    public static class Config { @Json5Field(aliases={"old"}) public Map<Integer,List<String>> values; }
    public static class CreatorMap {
        public final Map<Integer,String> values;
        @Json5Creator public CreatorMap(@Json5Field(name="values") Map<Integer,String> values){this.values=values;}
    }
    public static class Generic<T> { public Map<T,List<T>> values; }
    public static class IntegerConfig extends Generic<Integer> { }
    public static class Bean {
        private Map<Integer,String> values;
        public Map<Integer,String> getValues(){return values;}
        public void setValues(Map<Integer,String> values){this.values=values;}
    }
    public static class StaticMaps {
        public static Map<Integer,String> registered;
        @Json5Field(staticOutput=true) public static Map<Integer,String> annotated;
    }
    public static class NestedMap extends LinkedHashMap<String,NestedMap> {private static final long serialVersionUID=1L;}
    public static class LowerKey {
        final String value;LowerKey(String value){this.value=value;}
        @Override public boolean equals(Object other){return other instanceof LowerKey && value.equals(((LowerKey)other).value);}
        @Override public int hashCode(){return value.hashCode();}
    }
    public static class MapCodec implements Codec<Map<Integer,String>> {
        public Map<Integer,String> decode(Object value,CodecContext context){return Collections.singletonMap(1,(String)value);}
        public Object encode(Map<Integer,String> value,CodecContext context){return Collections.singletonMap("encoded",value.get(1));}
    }
    public static class CodecField { @Json5Field(codec=MapCodec.class) public Map<Integer,String> values; }

    @Test public void defaultObjectEncodingAndCoreGrammarAreUnchanged() {
        Json5Mapper plain=new Json5Mapper();
        assertEquals("{\"a\":1}",plain.write(Collections.singletonMap("a",1)));
        failure(() -> plain.write(Collections.singletonMap(1,"one")),"$",true);
        failure(() -> plain.read("[[1,'one']]",INTS),"$",true);
        failure(() -> plain.read("{'1':'one'}",INTS),"$",true);
        assertTrue(Yomu.parse("[[1,'one']]") instanceof List);
        assertEquals(1L,Yomu.parseObject("{a:1,}").get("a"));
    }

    @Test public void integerKeysUsePairArraysAndRestoreTheirDeclaredType() {
        Map<Integer,String> source=new LinkedHashMap<Integer,String>();source.put(1,"one");source.put(2,"two");
        Json5Mapper mapper=entries();
        assertEquals("[[1,\"one\"],[2,\"two\"]]",mapper.writeAs(source,INTS));
        Map<Integer,String> result=mapper.read(mapper.write(source),INTS);
        assertEquals(source,result);assertEquals(Integer.class,result.keySet().iterator().next().getClass());
        assertEquals(Arrays.asList(1,2),new ArrayList<Integer>(result.keySet()));
        assertEquals("[]",mapper.writeAs(Collections.<Integer,String>emptyMap(),INTS));
        assertTrue(mapper.read("[]",INTS).isEmpty());
    }

    @Test public void numericKeyClassesAndSignedZeroArePreserved() {
        Json5Mapper mapper=entries();
        TypeRef<Map<Byte,String>> bytes=new TypeRef<Map<Byte,String>>(){};
        assertEquals(Byte.class,mapper.read(mapper.write(Collections.singletonMap((byte)127,"x")),bytes).keySet().iterator().next().getClass());
        TypeRef<Map<Short,String>> shorts=new TypeRef<Map<Short,String>>(){};
        assertEquals(Short.class,mapper.read("[[32767,'x']]",shorts).keySet().iterator().next().getClass());
        TypeRef<Map<Long,String>> longs=new TypeRef<Map<Long,String>>(){};
        assertEquals("x",mapper.read("[[9223372036854775807,'x']]",longs).get(Long.MAX_VALUE));
        TypeRef<Map<BigInteger,String>> integers=new TypeRef<Map<BigInteger,String>>(){};
        BigInteger big=new BigInteger("123456789012345678901234567890");
        assertEquals("x",mapper.read(mapper.write(Collections.singletonMap(big,"x")),integers).get(big));
        TypeRef<Map<Float,String>> floats=new TypeRef<Map<Float,String>>(){};
        assertEquals(Float.class,mapper.read("[[1.25,'x']]",floats).keySet().iterator().next().getClass());
        TypeRef<Map<Boolean,String>> booleans=new TypeRef<Map<Boolean,String>>(){};
        assertEquals("x",mapper.read("[[true,'x']]",booleans).get(Boolean.TRUE));
        TypeRef<Map<Character,String>> characters=new TypeRef<Map<Character,String>>(){};
        assertEquals(Character.class,mapper.read("[['a','x']]",characters).keySet().iterator().next().getClass());
        TypeRef<Map<Double,String>> doubles=new TypeRef<Map<Double,String>>(){};
        Map<Double,String> zeroes=new LinkedHashMap<Double,String>();zeroes.put(-0.0,"negative");zeroes.put(0.0,"positive");
        assertEquals(zeroes,mapper.read(mapper.writeAs(zeroes,doubles),doubles));
        failure(() -> mapper.read("[[128,'x']]",bytes),"$[0][0]",true);
    }

    @Test public void enumKeysAndEnumMapAreSupportedWithConcreteTypes() {
        Json5Mapper mapper=entries().withStrictJson(true);
        EnumMap<Mode,Integer> source=new EnumMap<Mode,Integer>(Mode.class);source.put(Mode.FAST,1);source.put(Mode.SAFE,2);
        TypeRef<EnumMap<Mode,Integer>> type=new TypeRef<EnumMap<Mode,Integer>>(){};
        EnumMap<Mode,Integer> result=mapper.read(mapper.writeAs(source,type),type);
        assertEquals(source,result);assertSame(Mode.FAST,result.keySet().iterator().next());
        failure(() -> mapper.read("[]",EnumMap.class),"$",true);
    }

    @Test public void standardKeyCodecsRestoreUuidAndDateTypes() {
        Json5Mapper mapper=entries().withStandardCodecs();
        UUID id=UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        TypeRef<Map<UUID,String>> ids=new TypeRef<Map<UUID,String>>(){};
        assertEquals("x",mapper.read(mapper.writeAs(Collections.singletonMap(id,"x"),ids),ids).get(id));
        LocalDate date=LocalDate.of(2024,2,29);
        TypeRef<Map<LocalDate,Integer>> dates=new TypeRef<Map<LocalDate,Integer>>(){};
        assertEquals(Integer.valueOf(3),mapper.read(mapper.writeAs(Collections.singletonMap(date,3),dates),dates).get(date));
        java.nio.file.Path path=Paths.get("config","mod.json5");
        TypeRef<Map<java.nio.file.Path,Integer>> paths=new TypeRef<Map<java.nio.file.Path,Integer>>(){};
        assertEquals(Integer.valueOf(4),mapper.read(mapper.writeAs(Collections.singletonMap(path,4),paths),paths).get(path));
    }

    @Test public void pojoAndGenericContainerKeysUseTheirDeclaredModel() {
        Json5Mapper mapper=entries();
        Map<Key,List<Integer>> source=new LinkedHashMap<Key,List<Integer>>();source.put(new Key(3),Arrays.asList(1,2));
        TypeRef<Map<Key,List<Integer>>> type=new TypeRef<Map<Key,List<Integer>>>(){};
        assertEquals(source,mapper.read(mapper.writeAs(source,type),type));
        GenericKey<List<Integer>> key=new GenericKey<List<Integer>>();key.value=Arrays.asList(1,2);
        TypeRef<Map<GenericKey<List<Integer>>,String>> generic=new TypeRef<Map<GenericKey<List<Integer>>,String>>(){};
        Map<GenericKey<List<Integer>>,String> original=Collections.singletonMap(key,"x");
        assertEquals(original,mapper.read(mapper.writeAs(original,generic),generic));
    }

    @Test public void nestedMapsCanAppearAsKeysAndValues() {
        Json5Mapper mapper=entries();
        TypeRef<Map<Map<String,Integer>,Map<Integer,String>>> type=new TypeRef<Map<Map<String,Integer>,Map<Integer,String>>>(){};
        Map<Map<String,Integer>,Map<Integer,String>> source=Collections.singletonMap(Collections.singletonMap("id",1),Collections.singletonMap(2,"two"));
        assertEquals(source,mapper.read(mapper.writeAs(source,type),type));
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    @Test public void fieldCreatorAndAccessorTypeInformationFlowsIntoMapEntries() {
        Json5Mapper mapper=entries();
        Config value=mapper.read("{old:[[1,['a','b']]]}",Config.class);
        assertEquals(Arrays.asList("a","b"),value.values.get(1));
        assertEquals(value.values,mapper.read(mapper.write(value),Config.class).values);
        CreatorMap created=mapper.read("{values:[[1,'one']]}",CreatorMap.class);
        assertEquals("one",created.values.get(1));assertEquals(created.values,mapper.read(mapper.write(created),CreatorMap.class).values);
        IntegerConfig inherited=mapper.read("{values:[[1,[2,3]]]}",IntegerConfig.class);
        assertEquals(Arrays.asList(2,3),inherited.values.get(1));
        assertEquals(inherited.values,mapper.read(mapper.write(inherited),IntegerConfig.class).values);
        Bean bean=mapper.withBeanAccessors(true).read("{values:[[1,'one']]}",Bean.class);
        assertEquals("one",bean.getValues().get(1));
        assertEquals(bean.getValues(),mapper.withBeanAccessors(true).read(mapper.write(bean.getValues()),INTS));
        // Unsafe generic contents are rejected against the declared key type during typed output.
        Map wrong=Collections.singletonMap("wrong","x");
        failure(() -> mapper.writeAs((Map<Integer,String>)wrong,INTS),"$[0][0]",true);
    }

    @Test public void objectFormStringsRemainReadableAndNoMetadataNamesAreReserved() {
        Json5Mapper mapper=entries();
        TypeRef<Map<String,Integer>> type=new TypeRef<Map<String,Integer>>(){};
        Map<String,Integer> source=mapper.read("{$keys:1,$type:2,__proto__:3}",type);
        source.put("RenderDistance",4);
        assertEquals(source,mapper.read(mapper.writeAs(source,type),type));
        assertEquals(source,mapper.read(mapper.withNamingStrategy(NamingPolicy.SNAKE_CASE).writeAs(source,type),type));
        assertTrue(mapper.writeAs(source,type).startsWith("[["));
        assertTrue(mapper.read("[[1,'x']]",Object.class) instanceof List);
        Map<?,?> raw=mapper.read("[[1,'x']]",Map.class);
        assertEquals(Long.class,raw.keySet().iterator().next().getClass());
        failure(() -> mapper.read("{'1':'x'}",INTS),"$",true);
    }

    @Test public void malformedRowsAndWrongTypesReportPhysicalKeyAndValuePaths() {
        Json5Mapper mapper=entries();
        for(String input:new String[]{"[null]","[1]","[[1]]","[[1,2,3]]"})failure(() -> mapper.read(input,INTS),"$[0]",true);
        failure(() -> mapper.read("'bad'",INTS),"$",true);
        failure(() -> mapper.read("[['one','x']]",INTS),"$[0][0]",true);
        TypeRef<Map<Integer,Integer>> numbers=new TypeRef<Map<Integer,Integer>>(){};
        failure(() -> mapper.read("[[1,'bad']]",numbers),"$[0][1]",true);
        failure(() -> mapper.read("[[1.0,'x'],[1e0,'y']]",INTS),"$[1][0]",true);
        Json5Mapper lower=mapper.withCodec(LowerKey.class,Codecs.string(s -> new LowerKey(s.toLowerCase(Locale.ROOT)),key -> key.value));
        failure(() -> lower.read("[['A',1],['a',2]]",new TypeRef<Map<LowerKey,Integer>>(){}),"$[1][0]",true);
    }

    @Test public void nullKeysAndValuesFollowTargetMapRules() {
        Json5Mapper mapper=entries();
        TypeRef<Map<String,String>> type=new TypeRef<Map<String,String>>(){};
        Map<String,String> source=new LinkedHashMap<String,String>();source.put(null,null);source.put("null","text");
        assertEquals(source,mapper.read(mapper.writeAs(source,type),type));
        failure(() -> mapper.read("[[null,1]]",new TypeRef<SortedMap<Integer,Integer>>(){}),"$[0][0]",true);
        failure(() -> mapper.read("[[1,null]]",new TypeRef<ConcurrentHashMap<Integer,Integer>>(){}),"$[0]",true);
    }

    @Test public void decimalKeyScaleAndComparatorEqualityAreCheckedWithoutSilentOverwrite() {
        Json5Mapper mapper=entries().withBigDecimals(true);
        TypeRef<Map<BigDecimal,String>> type=new TypeRef<Map<BigDecimal,String>>(){};
        Map<BigDecimal,String> source=new LinkedHashMap<BigDecimal,String>();source.put(new BigDecimal("1.2300"),"scale4");source.put(new BigDecimal("1.23"),"scale2");
        String encoded=mapper.writeAs(source,type);assertEquals(source,mapper.read(encoded,type));
        failure(() -> mapper.withBigDecimals(false).read(encoded,type),"$[1][0]",true);
        failure(() -> mapper.read("[[1.0,'a'],[1.00,'b']]",new TypeRef<SortedMap<BigDecimal,String>>(){}),"$[1][0]",true);
        IdentityHashMap<String,Integer> identity=new IdentityHashMap<String,Integer>();identity.put("x",1);
        failure(() -> mapper.write(identity),"$",true);
        failure(() -> mapper.read("[]",IdentityHashMap.class),"$",true);
    }

    @Test public void strictJsonAndJson5NumericRulesAlsoApplyToKeys() {
        Json5Mapper mapper=entries();TypeRef<Map<Double,String>> type=new TypeRef<Map<Double,String>>(){};
        Map<Double,String> source=Collections.singletonMap(Double.NaN,"not a number");
        assertEquals(source,mapper.read(mapper.writeAs(source,type),type));
        try{mapper.withStrictJson(true).writeAs(source,type);fail();}catch(IllegalArgumentException expected) { }
        try{mapper.withStrictJson(true).read("[[NaN,\"x\"]]",type);fail();}catch(Json5Exception expected) { }
        assertEquals("hex",mapper.read("[[0xff,'hex'],]",INTS).get(255));
        try{mapper.withStrictJson(true).read("[[0xff,\"hex\"]]",INTS);fail();}catch(Json5Exception expected) { }
        Yomu.parse(mapper.withStrictJson(true).writeAs(Collections.singletonMap(1,"x"),INTS),Json5ReadOptions.DEFAULT.withStrictJson(true));
    }

    @Test public void formatIsImmutableAndSurvivesAllOtherOptionChangesAndStatics() {
        Json5Mapper plain=new Json5Mapper();
        Json5Mapper mapper=plain.withMapEncoding(MapEncoding.ENTRY_ARRAY).withWriter(new Json5Writer().withIndent(" "))
                .withReadOptions(Json5ReadOptions.DEFAULT).withBigDecimals(true).withStrictJson(true).withBeanAccessors(true)
                .withNamingStrategy(NamingPolicy.SNAKE_CASE).withFailOnUnknownFields(true).withStandardCodecs()
                .withCodec(UUID.class,StandardCodecs.UUID).withStaticField(StaticMaps.class,"registered");
        Map<Integer,String> previous=StaticMaps.registered,old=StaticMaps.annotated;
        try {
            StaticMaps.registered=Collections.singletonMap(1,"x");StaticMaps.annotated=Collections.singletonMap(2,"y");
            String output=mapper.writeStatics(StaticMaps.class);
            Map<?,?> tree=(Map<?,?>)Yomu.parse(output,Json5ReadOptions.DEFAULT.withStrictJson(true));
            assertTrue(tree.get("registered") instanceof List);assertTrue(tree.get("annotated") instanceof List);
            mapper.read("{\"registered\":[[9,\"changed\"]],\"annotated\":[[9,\"changed\"]]}",StaticMaps.class);
            assertEquals("x",StaticMaps.registered.get(1));assertEquals("y",StaticMaps.annotated.get(2));
            assertEquals(Collections.singletonMap(1,"x"),mapper.read(mapper.writeAs(StaticMaps.registered,INTS),INTS));
        } finally {StaticMaps.registered=previous;StaticMaps.annotated=old;}
        assertEquals("{\"x\":1}",plain.write(Collections.singletonMap("x",1)));
        failure(() -> mapper.withMapEncoding(MapEncoding.OBJECT).write(Collections.singletonMap(1,"x")),"$",true);
        try{mapper.withMapEncoding(null);fail();}catch(NullPointerException expected) { }
    }

    @Test public void explicitlyRegisteredMapCodecsKeepPriorityAndFinishedRepresentationsAreNotReencoded() {
        Json5Mapper mapper=entries().withCodec(INTS,Codecs.string(s -> Collections.singletonMap(1,s),map -> map.get(1)));
        assertEquals("\"x\"",mapper.writeAs(Collections.singletonMap(1,"x"),INTS));
        assertEquals("x",mapper.read("'x'",INTS).get(1));
        CodecField field=new CodecField();field.values=Collections.singletonMap(1,"x");
        assertEquals("{\"values\":{\"encoded\":\"x\"}}",entries().write(field));
    }

    @Test public void cyclicValuesAndPhysicalRepresentationDepthAreStillBounded() {
        Json5Mapper mapper=entries();Map<String,Object> cycle=new LinkedHashMap<String,Object>();cycle.put("self",cycle);
        failure(() -> mapper.write(cycle),"$[0][1]",true);
        List<Object> row=new ArrayList<Object>();row.add(row);row.add("x");
        failure(() -> mapper.convert(Collections.singletonList(row),new TypeRef<Map<List<Integer>,String>>(){}),"$[0][0]",true);
        NestedMap valid=nested(128);String text=mapper.write(valid);assertEquals(valid,mapper.read(text,NestedMap.class));
        failure(() -> mapper.write(nested(129)),"$",false);
    }

    @Test public void typedPathReadWriteAndConcurrentReuseWork() throws Exception {
        Json5Mapper mapper=entries().withStrictJson(true);Map<Integer,String> source=Collections.singletonMap(1,"one");
        Path file=Files.createTempFile("yomu-map-keys-",".json");
        try{mapper.writeAs(file,source,INTS);assertEquals(source,mapper.read(file,INTS));
            assertEquals("[[1,\"one\"]]",new String(Files.readAllBytes(file),StandardCharsets.UTF_8));}
        finally{Files.delete(file);}
        ExecutorService executor=Executors.newFixedThreadPool(4);
        try {
            List<Future<Map<Integer,String>>> values=new ArrayList<Future<Map<Integer,String>>>();
            for(int i=0;i<100;i++)values.add(executor.submit(() -> mapper.read(mapper.writeAs(source,INTS),INTS)));
            for(Future<Map<Integer,String>> value:values)assertEquals(source,value.get());
        } finally {executor.shutdownNow();}
    }

    private static NestedMap nested(int count){NestedMap root=new NestedMap(),node=root;for(int i=1;i<count;i++){NestedMap next=new NestedMap();node.put("next",next);node=next;}return root;}
    private static void failure(Runnable action,String path,boolean exact) {
        try{action.run();fail("Expected MappingException");}catch(MappingException e){if(exact)assertEquals(e.getMessage(),path,e.getPath());else assertTrue(e.getPath().startsWith(path));}
    }
}
