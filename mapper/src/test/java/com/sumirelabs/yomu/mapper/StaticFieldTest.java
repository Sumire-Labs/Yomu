package com.sumirelabs.yomu.mapper;

import com.sumirelabs.yomu.*;
import java.lang.reflect.ParameterizedType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class StaticFieldTest {
    public static class NoOptIn {
        public static int global=7;
        @Json5Field(comment="not selected",read=false) public static String annotated="hidden";
        public int local=1;
    }
    public static class Selected {
        @Json5Field(staticOutput=true,name="schema_version",aliases={"schema","old_schema"},comment="schema") public static int version=2;
        @Json5Field(staticOutput=true,codec=OutputOnlyCodec.class) private static String label="SUMIRE";
        @Json5Field(staticOutput=true) public static final long CONSTANT=42L;
        @Json5Field(staticOutput=true,write=false) public static String omitted="omitted";
        @Json5Field(staticOutput=true,ignore=true,format="invalid") public static String ignored="ignored";
        @Json5Field(staticOutput=true) public static List<String> values=new ArrayList<String>();
        public int local=1;
    }
    public static class OutputOnlyCodec implements Codec<String> {
        static int decodes;
        public String decode(Object value,CodecContext context){decodes++;throw new AssertionError("Static input must never reach a codec");}
        public Object encode(String value,CodecContext context){return value.toLowerCase(Locale.ROOT);}
    }
    public static class Programmatic {
        private static int limit=9;
        public static transient String ephemeral="selected explicitly";
        public static final String ID="id";
        public int local=1;
    }
    public static class Parent {
        @Json5Field(staticOutput=true,name="base_version") private static int version=3;
        public static String optional="parent";
    }
    public static class Child extends Parent {
        @Json5Field(staticOutput=true,name="child_version") private static int version=4;
        public int local=1;
    }
    public static class OnlyStatics {
        @Json5Field(staticOutput=true) private static int value=5;
        private OnlyStatics(){throw new AssertionError("Do not construct static-only owners");}
        @Json5Field public int getInstance(){throw new AssertionError("Do not call instance getters");}
    }
    public static class CreatorCollision {
        @Json5Field(staticOutput=true,name="value") public static int global=5;
        static int calls;
        @Json5Creator public CreatorCollision(@Json5Field(name="value") int value){calls++;global=value;}
    }
    public static class AliasCollision {
        @Json5Field(staticOutput=true,aliases={"local"}) public static int global;
        public int local;
    }
    public static class BeanCollision {
        @Json5Field(staticOutput=true,name="value") public static int global;
        static int calls;
        public int getValue(){return 1;}
        public void setValue(int value){calls++;global=value;}
    }
    public static class BadAnnotation { @Json5Field(staticOutput=true) public int local; }
    public static class BadAccessor { @Json5Field(staticOutput=true) public int getLocal(){return 1;} }
    public static class BadParameter {
        @Json5Creator public BadParameter(@Json5Field(name="local",staticOutput=true) int local) { }
    }
    public static class Formatted {
        @Json5Field(staticOutput=true,format="uuuu/MM/dd") public static LocalDate day=LocalDate.of(2024,2,29);
        @Json5Field(staticOutput=true,format="0.00") public static BigDecimal amount=new BigDecimal("1.25");
        @Json5Field(staticOutput=true) public static UUID id=UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        @Json5Field(staticOutput=true) public static List<String> labels=Arrays.asList("a","b");
    }
    public static class NonFinite {
        @Json5Field(staticOutput=true,comment="number") public static double number=Double.NaN;
    }
    public static class Cyclic {
        @Json5Field(staticOutput=true) public static Cyclic self;
    }
    public interface Constants { int VALUE=12; }

    @Before public void resetGlobals() {
        NoOptIn.global=7;NoOptIn.annotated="hidden";
        Selected.version=2;Selected.label="SUMIRE";Selected.values=new ArrayList<String>(Arrays.asList("original"));
        Programmatic.limit=9;OutputOnlyCodec.decodes=0;CreatorCollision.global=5;CreatorCollision.calls=0;
        BeanCollision.global=0;BeanCollision.calls=0;Cyclic.self=null;
    }

    @Test public void defaultStillExcludesAllUnselectedStaticFields() {
        Json5Mapper mapper=new Json5Mapper();
        assertEquals("{\"local\":1}",mapper.write(new NoOptIn()));assertEquals("{}",mapper.writeStatics(NoOptIn.class));
        mapper.read("{global:99,annotated:'changed',local:2}",NoOptIn.class);
        assertEquals(7,NoOptIn.global);assertEquals("hidden",NoOptIn.annotated);
        failure(() -> mapper.withFailOnUnknownFields(true).read("{global:99}",NoOptIn.class),"$[\"global\"]");
    }

    @Test public void annotationsAreOutputOnlyIncludingAliasesAndInvalidInputTypes() {
        Json5Mapper mapper=new Json5Mapper().withFailOnUnknownFields(true);
        Selected value=mapper.read("{schema_version:null,schema:[],old_schema:{x:99},label:{bad:true},CONSTANT:0,values:['changed'],omitted:3,ignored:4,local:6}",Selected.class);
        assertEquals(6,value.local);assertEquals(2,Selected.version);assertEquals("SUMIRE",Selected.label);
        assertEquals(42L,Selected.CONSTANT);assertEquals(Arrays.asList("original"),Selected.values);assertEquals(0,OutputOnlyCodec.decodes);
        Map<String,Object> out=Yomu.parseObject(mapper.write(value));
        assertEquals(2L,out.get("schema_version"));assertEquals("sumire",out.get("label"));assertEquals(42L,out.get("CONSTANT"));
        assertFalse(out.containsKey("ignored"));assertFalse(out.containsKey("omitted"));assertFalse(out.containsKey("schema"));
        assertTrue(mapper.write(value).contains("// schema"));
        Selected.version=8;assertEquals(8L,Yomu.parseObject(mapper.writeStatics(Selected.class)).get("schema_version"));
    }

    @Test public void explicitRegistrationIsImmutableAndSurvivesAllConfigurationMethods() {
        Json5Mapper plain=new Json5Mapper();
        Json5Mapper selected=plain.withStaticField(Programmatic.class,"limit").withStaticField(Programmatic.class,"ephemeral")
                .withStaticField(Programmatic.class,"ID").withStaticField(Programmatic.class,"limit")
                .withWriter(new Json5Writer().withIndent(" ")).withNamingStrategy(NamingPolicy.SNAKE_CASE)
                .withBeanAccessors(true).withReadOptions(Json5ReadOptions.DEFAULT.withBigDecimals(true)).withBigDecimals(true)
                .withStrictJson(true).withFailOnUnknownFields(true).withStandardCodecs().withCodec(UUID.class,StandardCodecs.UUID);
        Map<String,Object> out=Yomu.parseObject(selected.write(new Programmatic()));
        assertEquals(9L,out.get("limit"));assertEquals("selected explicitly",out.get("ephemeral"));assertEquals("id",out.get("id"));
        assertEquals("{\"local\":1}",plain.write(new Programmatic()));assertEquals("{}",plain.writeStatics(Programmatic.class));
        selected.read("{\"limit\":\"wrong type\",\"ephemeral\":null,\"id\":{},\"local\":2}",Programmatic.class);
        assertEquals(9,Programmatic.limit);assertEquals("selected explicitly",Programmatic.ephemeral);
        Programmatic.limit=10;assertEquals(10L,Yomu.parseObject(selected.writeStatics(Programmatic.class)).get("limit"));
    }

    @SuppressWarnings("unchecked")
    @Test public void staticOnlyWritesDoNotNeedConstructorsOrInstanceAccessors() {
        Json5Mapper mapper=new Json5Mapper();
        assertEquals("{\"value\":5}",mapper.writeStatics(OnlyStatics.class));
        // The instance creator is ambiguous with the static output name, but irrelevant to this API.
        assertEquals("{\"value\":5}",mapper.writeStatics(CreatorCollision.class));assertEquals(0,CreatorCollision.calls);
        assertEquals("{\"VALUE\":12}",mapper.withStaticField(Constants.class,"VALUE").writeStatics(Constants.class));
        Map<String,Object> tree=mapper.staticFieldsToValue(Selected.class);
        ((List<Object>)tree.get("values")).add("copy only");assertEquals(Arrays.asList("original"),Selected.values);
    }

    @Test public void classHierarchyUsesDeclaredSelectionsAndDoesNotLeakToOtherTypes() {
        Json5Mapper mapper=new Json5Mapper().withStaticField(Parent.class,"optional");
        Map<String,Object> out=Yomu.parseObject(mapper.write(new Child()));
        assertEquals(3L,out.get("base_version"));assertEquals(4L,out.get("child_version"));assertEquals("parent",out.get("optional"));
        assertEquals("{}",mapper.writeStatics(NoOptIn.class));
        rejected(() -> mapper.withStaticField(Child.class,"optional"));
    }

    @Test public void collisionsCannotRouteStaticInputIntoCreatorsOrSetters() {
        Json5Mapper mapper=new Json5Mapper();
        failure(() -> mapper.read("{value:99}",CreatorCollision.class),"$");
        assertEquals(5,CreatorCollision.global);assertEquals(0,CreatorCollision.calls);
        failure(() -> mapper.withBeanAccessors(true).read("{value:99}",BeanCollision.class),"$");
        assertEquals(0,BeanCollision.global);assertEquals(0,BeanCollision.calls);
        failure(() -> mapper.write(new AliasCollision()),"$");failure(() -> mapper.write(new BadAnnotation()),"$");
        failure(() -> mapper.write(new BadAccessor()),"$");failure(() -> mapper.read("{local:1}",BadParameter.class),"$");
        rejected(() -> mapper.withStaticField(Programmatic.class,"local"));rejected(() -> mapper.withStaticField(Programmatic.class,"missing"));
    }

    @Test public void codecsAndFormatsUseTheSameOutputPathForInstancesAndStatics() {
        Json5Mapper mapper=new Json5Mapper().withStandardCodecs().withCodec(new TypeRef<List<String>>() {},Codecs.string(s -> Arrays.asList(s.split(",")),list -> String.join(",",list)));
        Map<String,Object> out=Yomu.parseObject(mapper.writeStatics(Formatted.class));
        assertEquals("2024/02/29",out.get("day"));assertEquals("1.25",out.get("amount"));
        assertEquals(Formatted.id.toString(),out.get("id"));assertEquals("a,b",out.get("labels"));
        assertEquals(out,Yomu.parseObject(mapper.write(new Formatted())));
    }

    @Test public void strictModeRejectsNonFiniteValuesAndInputRemainsIgnored() throws Exception {
        Json5Mapper plain=new Json5Mapper(),strict=plain.withStrictJson(true).withFailOnUnknownFields(true);
        assertTrue(plain.writeStatics(NonFinite.class).contains("NaN"));
        rejected(() -> strict.writeStatics(NonFinite.class));
        strict.read("{\"number\":\"not a double\"}",NonFinite.class);assertTrue(Double.isNaN(NonFinite.number));
        String output=strict.writeStatics(Selected.class);assertFalse(output.contains("//"));
        Yomu.parse(output,Json5ReadOptions.DEFAULT.withStrictJson(true));
        Path file=Files.createTempFile("yomu-static-",".json");
        try {
            Files.write(file,"unchanged".getBytes(StandardCharsets.UTF_8));
            try {strict.writeStatics(file,NonFinite.class);fail();}catch(IllegalArgumentException expected) { }
            assertEquals("unchanged",new String(Files.readAllBytes(file),StandardCharsets.UTF_8));
            strict.writeStatics(file,Selected.class);
            assertEquals(2L,((Map<?,?>)Yomu.parse(file,Json5ReadOptions.DEFAULT.withStrictJson(true))).get("schema_version"));
        } finally {Files.delete(file);}
    }

    @Test public void recursiveStaticValuesAreRejectedWithTheirValuePath() {
        Cyclic.self=new Cyclic();
        failure(() -> new Json5Mapper().writeStatics(Cyclic.class),"$[\"self\"][\"self\"]");
        failure(() -> new Json5Mapper().write(Cyclic.self),"$[\"self\"]");
    }

    @Test public void configuredStaticMetadataCanBeReusedConcurrently() throws Exception {
        final Json5Mapper mapper=new Json5Mapper().withStaticField(Programmatic.class,"limit").withStrictJson(true);
        ExecutorService executor=Executors.newFixedThreadPool(4);
        try {
            List<Future<String>> values=new ArrayList<Future<String>>();
            for(int i=0;i<100;i++)values.add(executor.submit(() -> mapper.writeStatics(Programmatic.class)));
            for(Future<String> value:values)assertEquals("{\"limit\":9}",value.get());
        } finally {executor.shutdownNow();}
    }

    private static void rejected(Runnable action) {
        try {action.run();fail("Expected rejection");}catch(IllegalArgumentException expected) { }
    }
    private static void failure(Runnable action,String path) {
        try {action.run();fail("Expected mapping failure");}catch(MappingException e){assertEquals(e.getMessage(),path,e.getPath());}
    }
}
