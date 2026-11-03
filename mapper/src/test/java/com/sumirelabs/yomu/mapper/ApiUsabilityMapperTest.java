package com.sumirelabs.yomu.mapper;

import com.sumirelabs.yomu.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ApiUsabilityMapperTest {
    public static class Config { public String name; public BigDecimal amount; }
    public static class Pet { public String name; }
    public static class Dog extends Pet { public int barks; public Dog next; }
    public static class Pair { public Pet first,second; }
    public static class Bean {
        private int tickRate;
        public int getTickRate() { return tickRate; }
        public void setTickRate(int value) { tickRate=value; }
    }
    public static class Statics { public static String label="fixed"; }
    private static final class Input extends ByteArrayInputStream {
        boolean closed;
        Input(String text) { super(text.getBytes(StandardCharsets.UTF_8)); }
        public void close() { closed=true; }
    }
    private static final class Text extends StringReader {
        boolean closed;
        Text(String text) { super(text); }
        public void close() { closed=true; super.close(); }
    }
    private static String text(ByteArrayOutputStream bytes) { return new String(bytes.toByteArray(),StandardCharsets.UTF_8); }
    @Test public void ordinaryReadersAcceptClassAndGenericTypesAndBorrowInputs() throws Exception {
        for(boolean strict:new boolean[]{false,true}) {
            Json5Mapper mapper=Json5Mapper.builder().strictJson(strict).bigDecimals(true).build();
            String source=strict?"{\"name\":\"すみれ\",\"amount\":1.2300}":"/*x*/{name:'すみれ',amount:1.2300,}";
            Text chars=new Text(source);Input bytes=new Input(source);
            Config a=mapper.read(chars,Config.class),b=mapper.read(bytes,new TypeRef<Config>(){});
            assertEquals("すみれ",a.name);assertEquals(a.name,b.name);assertEquals(new BigDecimal("1.2300"),a.amount);assertEquals(a.amount,b.amount);
            assertFalse(chars.closed);assertFalse(bytes.closed);
            assertEquals("すみれ",mapper.read(new Input(source),Config.class).name);
            TypeRef<List<Config>> type=new TypeRef<List<Config>>(){};
            assertEquals(a.amount,mapper.read(new StringReader("["+source+"]"),type).get(0).amount);
            assertEquals(a.amount,mapper.read(new Input("["+source+"]"),type).get(0).amount);
        }
        Json5Mapper mapper=Json5Mapper.builder().build();
        try{mapper.read(new StringReader("{} {}"),Config.class);fail();}catch(Json5Exception expected){ }
        try{mapper.read(new ByteArrayInputStream(new byte[]{(byte)0xc0,(byte)0xaf}),Config.class);fail();}catch(IOException expected){ }
        Text invalid=new Text("[");try{mapper.read(invalid,Config.class);fail();}catch(Json5Exception expected){assertFalse(invalid.closed);}
    }
    @Test public void destinationFirstOutputsPreserveGraphPolymorphismAndStreaming() throws Exception {
        Path file=Files.createTempFile("yomu-mapper-api-", ".json5");
        try {
            for(boolean strict:new boolean[]{false,true})for(boolean graph:new boolean[]{false,true}) {
                Json5Mapper.Builder builder=Json5Mapper.builder();builder.strictJson(strict);builder.objectGraph(graph);builder.type("dog",Dog.class);
                Json5Mapper mapper=builder.build();Dog dog=new Dog();dog.name="すみれ";dog.barks=4;if(graph)dog.next=dog;
                Pair pair=new Pair();pair.first=pair.second=dog;
                StringBuilder chars=new StringBuilder();ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                mapper.writeTo(chars,pair);mapper.writeTo(bytes,pair);mapper.writeTo(file,pair);
                assertEquals(mapper.write(pair),chars.toString());assertEquals(chars.toString(),text(bytes));assertEquals(chars.toString(),new String(Files.readAllBytes(file),StandardCharsets.UTF_8));
                for(Pair copy:Arrays.asList(mapper.read(new StringReader(chars.toString()),Pair.class),mapper.read(new Input(text(bytes)),new TypeRef<Pair>(){}),mapper.read(file,Pair.class))) {
                    assertTrue(copy.first instanceof Dog);assertEquals(4,((Dog)copy.first).barks);
                    if(graph){assertSame(copy.first,copy.second);assertSame(copy.first,((Dog)copy.first).next);}
                }
                chars.setLength(0);bytes.reset();mapper.writeStreamingTo(chars,pair);mapper.writeStreamingTo(bytes,pair);mapper.writeStreamingTo(file,pair);
                Pair copy=mapper.readStreaming(new StringReader(chars.toString()),Pair.class);assertTrue(copy.first instanceof Dog);if(graph)assertSame(copy.first,copy.second);
                assertEquals(mapper.write(pair),text(bytes));assertEquals(text(bytes),new String(Files.readAllBytes(file),StandardCharsets.UTF_8));
            }
        } finally { Files.delete(file); }
    }
    @Test public void typedOutputsRetainDeclaredTypesAndNonStringMapKeys() throws Exception {
        Json5Mapper mapper=Json5Mapper.builder().strictJson(true).mapEncoding(MapEncoding.ENTRY_ARRAY).type("dog",new TypeRef<Dog>(){}).build();
        Dog dog=new Dog();dog.name="dog";
        Map<Integer,Pet> map=new LinkedHashMap<Integer,Pet>();map.put(17,dog);
        TypeRef<Map<Integer,Pet>> type=new TypeRef<Map<Integer,Pet>>(){};
        Path file=Files.createTempFile("yomu-mapper-api-", ".json");
        try {
            StringBuilder chars=new StringBuilder();ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            mapper.writeTo(chars,map,type);mapper.writeTo(bytes,map,type);mapper.writeTo(file,map,type);
            assertEquals(mapper.writeAs(map,type),chars.toString());assertEquals(chars.toString(),text(bytes));
            Map<Integer,Pet> copy=mapper.read(new Input(text(bytes)),type);assertTrue(copy.get(17) instanceof Dog);
            chars.setLength(0);bytes.reset();mapper.writeStreamingTo(chars,map,type);mapper.writeStreamingTo(bytes,map,type);mapper.writeStreamingTo(file,map,type);
            assertTrue(mapper.read(new StringReader(chars.toString()),type).get(17) instanceof Dog);
            assertEquals(mapper.writeAs(map,type),text(bytes));assertEquals(text(bytes),new String(Files.readAllBytes(file),StandardCharsets.UTF_8));
            chars.setLength(0);bytes.reset();mapper.writeTo(chars,dog,Pet.class);mapper.writeTo(bytes,dog,Pet.class);mapper.writeTo(file,dog,Pet.class);
            assertTrue(mapper.read(new StringReader(chars.toString()),Pet.class) instanceof Dog);assertEquals(chars.toString(),text(bytes));
            chars.setLength(0);bytes.reset();mapper.writeStreamingTo(chars,dog,Pet.class);mapper.writeStreamingTo(bytes,dog,Pet.class);mapper.writeStreamingTo(file,dog,Pet.class);
            assertTrue(mapper.read(new Input(text(bytes)),Pet.class) instanceof Dog);assertEquals(chars.toString(),text(bytes));
            mapper.writeTo(file,null,Pet.class);assertNull(mapper.read(file,Pet.class));
        } finally { Files.delete(file); }
    }
    @Test public void mapperBuildersKeepSnapshotsBeansNamingAndUnknownFieldPolicy() {
        Json5Mapper.Builder builder=Json5Mapper.builder();builder.beanAccessors(true);builder.namingStrategy(NamingPolicy.SNAKE_CASE);builder.failOnUnknownFields(true);
        Json5Writer pretty=Json5Writer.builder().indent("  ").quoteKeys(false).build();builder.writer(pretty);
        Json5Mapper first=builder.build();builder.strictJson(true);Json5Mapper strict=builder.build();
        Bean bean=first.read("{tick_rate:20}",Bean.class);assertEquals(20,bean.getTickRate());
        String output=first.write(bean);assertTrue(output.contains("\n  tick_rate:"));
        assertTrue(strict.write(bean).contains("\"tick_rate\""));
        assertEquals(output,strict.toBuilder().strictJson(false).build().write(bean));
        try{first.read("{tick_rate:1,unknown:2}",Bean.class);fail();}catch(MappingException expected){ }
        Json5Mapper flexible=first.toBuilder().failOnUnknownFields(false).build();assertEquals(1,flexible.read("{tick_rate:1,unknown:2}",Bean.class).getTickRate());
        try{strict.read("{tick_rate:1}",Bean.class);fail();}catch(Json5Exception expected){ }
        assertEquals(20,first.read("{tick_rate:20}",Bean.class).getTickRate());
    }
    @Test public void builderRegistrationsPreserveCodecsStaticsAndReadOptions() throws Exception {
        Codec<UUID> custom=new Codec<UUID>() {
            public UUID decode(Object value,CodecContext context) { return UUID.fromString(((String)value).substring(1)); }
            public Object encode(UUID value,CodecContext context) { return "!"+value; }
        };
        Json5Mapper.Builder builder=Json5Mapper.builder();builder.codec(UUID.class,custom);builder.standardCodecs();builder.staticField(Statics.class,"label");
        builder.readOptions(Json5ReadOptions.builder().bigDecimals(true).maxDepth(8).build());
        Json5Mapper mapper=builder.build();UUID id=UUID.randomUUID();assertEquals(id,mapper.read(mapper.write(id),UUID.class));assertTrue(mapper.write(id).startsWith("\"!"));
        assertEquals(new BigDecimal("1.2300"),mapper.read(new StringReader("1.2300"),Object.class));
        assertTrue(mapper.writeStatics(Statics.class).contains("fixed"));mapper.read("{label:'changed'}",Statics.class);assertEquals("fixed",Statics.label);
        Json5Mapper typed=mapper.toBuilder().codec(new TypeRef<UUID>(){},custom).build();assertEquals(id,typed.read(typed.write(id),UUID.class));
        assertTrue(typed.writeStatics(Statics.class).contains("fixed"));
    }
    @Test public void strictOutputAndLegacyImmutableConfigurationKeepTheirContracts() throws Exception {
        Json5Mapper original=new Json5Mapper();original.withStrictJson(true);assertEquals("NaN",original.write(Double.NaN));
        Json5Mapper strict=Json5Mapper.builder().strictJson(true).build();
        StringBuilder chars=new StringBuilder("unchanged");ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try{strict.writeTo(chars,Double.NaN);fail();}catch(IllegalArgumentException expected){assertEquals("unchanged",chars.toString());}
        try{strict.writeTo(bytes,Double.POSITIVE_INFINITY);fail();}catch(IllegalArgumentException expected){assertEquals(0,bytes.size());}
        try{strict.read(new StringReader("NaN"),Double.class);fail();}catch(Json5Exception expected){ }
        assertEquals("null",strict.write(null));
    }
}
