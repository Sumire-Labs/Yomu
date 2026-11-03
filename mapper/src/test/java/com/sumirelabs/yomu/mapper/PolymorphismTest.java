package com.sumirelabs.yomu.mapper;

import com.sumirelabs.yomu.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class PolymorphismTest {
    public static abstract class Animal { public String name; }
    public static class Dog extends Animal { public int bark; public Animal friend; public Object other; }
    public static class Cat extends Animal { public boolean purr; }
    public static class Zoo { public Animal a; public Object b; public List<Animal> animals; public Animal[] array; }
    public interface Value<T> { }
    public static class Box<T> implements Value<T> { public T value; }
    public static class IntBox extends Box<Integer> { }
    public static class Immutable extends Animal {
        public final int age;
        @Json5Creator public Immutable(@Json5Field(name="name") String name,@Json5Field(name="age") int age){this.name=name;this.age=age;}
    }
    public static class Dangerous { public static int calls; public Dangerous(){calls++;} }
    public static class Statics { @Json5Field(staticOutput=true) public static Animal animal; }
    public static class MixedCodec { public Dog first; public Animal second; }
    public static class StaticMetadata {
        @Json5Field(staticOutput=true,name="$thermometer",comment="literal metadata") public static String marker="type/1";
        @Json5Field(staticOutput=true) public static String type="unknown";
        @Json5Field(staticOutput=true) public static Animal value=dog();
    }
    public static class FieldCodec {
        @Json5Field(codec=DogText.class) public Animal animal;
    }
    public static class DogText implements Codec<Animal> {
        public Object encode(Animal value,CodecContext c){return value.name;}
        public Animal decode(Object value,CodecContext c){Dog d=new Dog();d.name=(String)value;return d;}
    }
    private static Json5Mapper mapper(){return new Json5Mapper().withType("dog",Dog.class).withType("cat",Cat.class);}
    private static Dog dog(){Dog d=new Dog();d.name="sumire";d.bark=7;return d;}
    private static MappingException failure(Runnable run,String message){try{run.run();fail();return null;}catch(MappingException e){assertTrue(e.getMessage(),e.getMessage().contains(message));return e;}}
    private static void badRegistration(Runnable run){try{run.run();fail();}catch(IllegalArgumentException expected){}}

    @Test public void registeredRuntimeTypesRestoreAtRootBaseAndObject() {
        Json5Mapper m=mapper();Dog input=dog();String wire=m.write(input);
        assertEquals("type/1",Yomu.parseObject(wire).get("$thermometer"));
        assertEquals("dog",Yomu.parseObject(wire).get("type"));
        for(Class<?> type:Arrays.<Class<?>>asList(Object.class,Animal.class,Dog.class)) {
            Dog out=(Dog)m.read(wire,type);assertEquals(7,out.bark);assertEquals("sumire",out.name);
        }
        assertTrue(m.read(m.writeAs(input,Animal.class),Animal.class) instanceof Dog);
        assertFalse(wire.contains(Dog.class.getName()));
    }
    @Test public void nestedListsArraysAndObjectSlotsRestoreWithoutGraph() {
        Json5Mapper m=mapper();Zoo z=new Zoo();z.a=dog();z.b=new Cat();z.animals=Arrays.asList(z.a,(Animal)z.b);z.array=new Animal[]{z.a,(Animal)z.b};
        Zoo out=m.read(m.write(z),Zoo.class);assertTrue(out.a instanceof Dog);assertTrue(out.b instanceof Cat);
        assertTrue(out.animals.get(0) instanceof Dog);assertTrue(out.array[1] instanceof Cat);
        List<?> raw=m.read(m.write(Arrays.asList(dog(),Collections.singletonMap("pet",dog()))),List.class);
        assertTrue(raw.get(0) instanceof Dog);assertTrue(((Map<?,?>)raw.get(1)).get("pet") instanceof Dog);
    }
    @Test public void genericRegistrationChecksSupertypeArguments() {
        Json5Mapper m=new Json5Mapper().withType("box",new TypeRef<Box<Integer>>(){});
        Box<Integer> b=new Box<Integer>();b.value=12;
        Value<Integer> out=m.read(m.writeAs(b,new TypeRef<Value<Integer>>(){}),new TypeRef<Value<Integer>>(){});
        assertEquals(Integer.valueOf(12),((Box<?>)out).value);
        failure(()->m.read(m.write(b),new TypeRef<Value<String>>(){}),"generic arguments");
        Json5Mapper inherited=new Json5Mapper().withType("int",IntBox.class);IntBox ib=new IntBox();ib.value=3;
        assertEquals(Integer.valueOf(3),((Box<?>)inherited.read(inherited.write(ib),new TypeRef<Value<Integer>>(){})).value);
    }
    @Test public void registrationRejectsDuplicateIdsClassesAndNonconcreteTypes() {
        Json5Mapper m=mapper();m.withType("dog",Dog.class);
        badRegistration(()->m.withType("dog",Cat.class));badRegistration(()->m.withType("other",Dog.class));
        badRegistration(()->m.withType("",Dog.class));badRegistration(()->m.withType(" dog",Dog.class));
        badRegistration(()->m.withType("base",Animal.class));badRegistration(()->m.withType("object",Object.class));
        badRegistration(()->m.withType("int",int.class));badRegistration(()->m.withType("box",Box.class));
        badRegistration(()->m.withType("arrays",Box[].class));
        badRegistration(()->new Json5Mapper().withType("i",new TypeRef<Box<Integer>>(){}).withType("s",new TypeRef<Box<String>>(){}));
    }
    @Test public void unknownOrIncompatibleIdsNeverInvokeConstructors() {
        Dangerous.calls=0;Json5Mapper m=mapper().withType("danger",Dangerous.class);
        failure(()->m.read("{$thermometer:'type/1',type:'java.lang.Runtime',value:{}}",Object.class),"Unknown registered");
        failure(()->m.read("{$thermometer:'type/1',type:'danger',value:{}}",Animal.class),"incompatible");
        assertEquals(0,Dangerous.calls);
        failure(()->m.read("{$thermometer:'type/1',type:'dog',value:{}}",Cat.class),"incompatible");
    }
    @Test public void malformedEnvelopesAndMissingSubtypeRegistrationsFail() {
        Json5Mapper m=mapper();
        for(String wire:Arrays.asList("{$thermometer:'type/2',type:'dog',value:{}}","{$thermometer:'type/1',type:3,value:{}}","{$thermometer:'type/1',type:'dog'}","{$thermometer:'type/1',type:'dog',value:{},extra:0}"))
            failure(()->m.read(wire,Animal.class),"type");
        Json5Mapper onlyDog=new Json5Mapper().withType("dog",Dog.class);
        failure(()->onlyDog.writeAs(new Cat(),Animal.class),"Unregistered runtime subtype");
    }
    @Test public void literalMetadataPropertiesAreEscapedWithoutCollisions() {
        Json5Mapper m=mapper();
        for(String marker:Arrays.asList("type/1","literal/1","type/999")) {
            Map<String,Object> data=new LinkedHashMap<String,Object>();data.put("$thermometer",marker);data.put("type","unknown");data.put("value",dog());
            Map<?,?> out=m.read(m.write(data),Map.class);assertEquals(marker,out.get("$thermometer"));assertEquals("unknown",out.get("type"));assertTrue(out.get("value") instanceof Dog);
        }
    }
    @Test public void graphRestoresObjectFirstSharedAndCyclicSubtypes() {
        Json5Mapper m=mapper().withObjectGraph(true);Dog d=dog();d.friend=d;d.other=d;
        Dog out=(Dog)m.read(m.write(d),Object.class);assertSame(out,out.friend);assertSame(out,out.other);
        Zoo z=new Zoo();z.a=d;z.b=d;z.animals=Arrays.<Animal>asList(d,d);z.array=new Animal[]{d};
        Zoo copy=m.read(m.write(z),Zoo.class);assertSame(copy.a,copy.b);assertSame(copy.a,copy.animals.get(1));assertSame(copy.a,copy.array[0]);
        List<Object> values=Arrays.<Object>asList(d,d);List<?> list=m.read(m.write(values),List.class);assertTrue(list.get(0) instanceof Dog);assertSame(list.get(0),list.get(1));
    }
    @Test public void graphTypedForwardReferencesAndBadIdsAreValidated() {
        Json5Mapper m=mapper().withObjectGraph(true);
        String wire="{$thermometer:'graph/1',root:{$ref:1},nodes:[{kind:'object',type:'dog',value:{friend:{$ref:1}}},{kind:'object',type:'dog',value:{friend:{$ref:0}}}]}";
        Dog out=(Dog)m.read(wire,Object.class);assertSame(out,((Dog)out.friend).friend);
        failure(()->m.read(wire.replace("type:'dog'","type:'evil'"),Object.class),"Unknown registered");
        failure(()->m.read(wire,Cat.class),"incompatible");
        failure(()->new Json5Mapper().withObjectGraph(true).read(wire,Object.class),"Unknown registered");
    }
    @Test public void genericTypesWorkInGraphAndConflictBeforeConstruction() {
        Json5Mapper m=new Json5Mapper().withObjectGraph(true).withType("int-box",new TypeRef<Box<Integer>>(){});
        Box<Integer> box=new Box<Integer>();box.value=17;Object out=m.read(m.write(box),Object.class);
        assertEquals(Integer.valueOf(17),((Box<?>)out).value);
        failure(()->m.read(m.write(box),new TypeRef<Value<String>>(){}),"generic arguments");
    }
    @Test public void creatorsFieldCodecsAndGlobalCodecsKeepTheirBehavior() {
        Json5Mapper m=mapper().withType("immutable",Immutable.class);
        Immutable a=(Immutable)m.read(m.write(new Immutable("x",4)),Animal.class);assertEquals(4,a.age);
        FieldCodec field=new FieldCodec();field.animal=dog();assertEquals("sumire",m.read(m.write(field),FieldCodec.class).animal.name);
        Json5Mapper coded=m.withCodec(Dog.class,new Codec<Dog>() {
            public Object encode(Dog value,CodecContext c){return value.name;}
            public Dog decode(Object value,CodecContext c){Dog d=dog();d.name=(String)value;return d;}
        });
        String tagged=coded.writeAs(dog(),Animal.class);assertEquals("sumire",coded.read(tagged,Animal.class).name);
        assertEquals("sumire",coded.read(coded.write(dog()),Dog.class).name);
        Json5Mapper g=coded.withObjectGraph(true);List<?> out=g.read(g.write(Arrays.<Object>asList(dog())),List.class);assertTrue(out.get(0) instanceof Dog);
    }
    @Test public void scalarAndContainerRegistrationsWorkInBothFormats() {
        for(boolean graph:Arrays.asList(false,true)) {
            Json5Mapper m=mapper().withObjectGraph(graph).withType("long",Long.class).withType("strings",new TypeRef<ArrayList<String>>(){}).withType("ints",int[].class);
            Object number=m.read(m.write(5L),Object.class);assertEquals(Long.class,number.getClass());
            Object list=m.read(m.write(new ArrayList<String>(Arrays.asList("x"))),Object.class);assertEquals(ArrayList.class,list.getClass());
            assertArrayEquals(new int[]{3},(int[])m.read(m.write(new int[]{3}),Object.class));
            Json5Mapper dates=m.withType("date",LocalDate.class).withStandardCodecs();
            LocalDate date=LocalDate.of(2026,10,22);assertEquals(date,dates.read(dates.write(date),Object.class));
        }
    }
    @Test public void strictJsonBigDecimalsEntryMapsAndPathsCompose() throws Exception {
        TypeRef<Map<Animal,Animal>> type=new TypeRef<Map<Animal,Animal>>(){};
        for(boolean graph:Arrays.asList(false,true)) {
            Json5Mapper m=mapper().withStrictJson(true).withBigDecimals(true).withMapEncoding(MapEncoding.ENTRY_ARRAY).withObjectGraph(graph);
            Map<Animal,Animal> map=new LinkedHashMap<Animal,Animal>();map.put(dog(),new Cat());
            Path path=Files.createTempFile("yomu-types-",".json");
            try{m.writeAs(path,map,type);Map<Animal,Animal> out=m.read(path,type);assertTrue(out.keySet().iterator().next() instanceof Dog);assertTrue(out.values().iterator().next() instanceof Cat);}finally{Files.delete(path);}
            Yomu.parse(m.writeAs(map,type),Json5ReadOptions.DEFAULT.withStrictJson(true));
        }
    }
    @Test public void staticsAndConvertedValuesRetainRegisteredTypes() {
        Statics.animal=dog();Json5Mapper m=mapper();assertTrue(m.read(m.writeStatics(Statics.class),Map.class).get("animal") instanceof Dog);
        Zoo zoo=new Zoo();zoo.a=dog();assertTrue(m.convert(m.toValue(zoo),Zoo.class).a instanceof Dog);
        Json5Mapper graph=m.withObjectGraph(true);Map<?,?> out=graph.read(graph.writeStatics(Statics.class),Map.class);assertTrue(out.get("animal") instanceof Dog);
        Animal original=Statics.animal;graph.read(graph.writeStatics(Statics.class),Statics.class);assertSame(original,Statics.animal);
    }
    @Test public void defaultMapperAndGraphWithoutRegistrationRemainUnchanged() {
        Json5Mapper plain=new Json5Mapper();Map<?,?> map=plain.read(mapper().write(dog()),Map.class);assertEquals("type/1",map.get("$thermometer"));
        assertFalse(plain.write(dog()).contains("type/1"));
        Dog d=dog();d.other=d;Dog copy=new Json5Mapper().withObjectGraph(true).read(new Json5Mapper().withObjectGraph(true).write(d),Dog.class);assertSame(copy,copy.other);
    }
    @Test public void configurationIsImmutableAndAllWithMethodsPreserveRegistry() {
        Json5Mapper m=mapper().withObjectGraph(true).withFailOnUnknownFields(true).withWriter(new Json5Writer())
                .withNamingStrategy(NamingPolicy.SNAKE_CASE).withBeanAccessors(true).withReadOptions(Json5ReadOptions.DEFAULT)
                .withStrictJson(true).withBigDecimals(true).withMapEncoding(MapEncoding.OBJECT).withStaticField(Statics.class,"animal").withStandardCodecs();
        assertTrue(m.read(m.write(dog()),Object.class) instanceof Dog);
        assertTrue(m.withObjectGraph(false).read(m.withObjectGraph(false).write(dog()),Object.class) instanceof Dog);
        assertFalse(new Json5Mapper().write(dog()).contains("type/1"));
    }
    @Test public void concurrentReadsUsePerOperationGraphState() throws Exception {
        final Json5Mapper m=mapper().withObjectGraph(true);ExecutorService pool=Executors.newFixedThreadPool(4);
        try{List<Future<?>> jobs=new ArrayList<Future<?>>();for(int i=0;i<24;i++)jobs.add(pool.submit(()->{Dog d=dog();d.friend=d;Dog out=(Dog)m.read(m.write(d),Object.class);assertSame(out,out.friend);}));for(Future<?> job:jobs)job.get();}
        finally{pool.shutdownNow();}
    }
    @Test public void inheritedCodecsAndOpaqueMetadataOutputsAreHandledConsistently() {
        Json5Mapper m=mapper().withCodec(Animal.class,new DogText());
        for(boolean graph:Arrays.asList(false,true)) {
            Json5Mapper current=m.withObjectGraph(graph);
            assertTrue(current.read(current.write(dog()),Object.class) instanceof Dog);
            assertEquals("sumire",current.read(current.writeAs(dog(),Animal.class),Animal.class).name);
        }
        Codec<Dog> opaque=new Codec<Dog>() {
            public Object encode(Dog d,CodecContext c){return Collections.singletonMap("$thermometer","type/1");}
            public Dog decode(Object v,CodecContext c){assertEquals("type/1",((Map<?,?>)v).get("$thermometer"));return dog();}
        };
        Json5Mapper coded=mapper().withCodec(Dog.class,opaque);
        assertEquals("sumire",coded.read(coded.write(dog()),Dog.class).name);
        assertEquals("sumire",coded.read(coded.writeAs(dog(),Animal.class),Animal.class).name);
        Json5Mapper graph=coded.withObjectGraph(true);MixedCodec source=new MixedCodec();source.first=dog();source.second=source.first;
        MixedCodec copy=graph.read(graph.write(source),MixedCodec.class);assertSame(copy.first,copy.second);
    }
    @Test public void staticLiteralMetadataAndGenericArrayTargetsAreValidated() {
        Json5Mapper m=mapper();Map<?,?> literal=m.read(m.writeStatics(StaticMetadata.class),Map.class);
        assertEquals("type/1",literal.get("$thermometer"));assertEquals("unknown",literal.get("type"));assertTrue(literal.get("value") instanceof Dog);
        Json5Mapper arrays=m.withType("int-box-array",IntBox[].class);IntBox box=new IntBox();box.value=3;
        String wire=arrays.write(new IntBox[]{box});
        assertEquals(Integer.valueOf(3),arrays.read(wire,new TypeRef<Box<Integer>[]>(){})[0].value);
        failure(()->arrays.read(wire,new TypeRef<Box<String>[]>(){}),"generic arguments");
    }
}
