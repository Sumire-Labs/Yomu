package com.sumirelabs.yomu.mapper;

import com.sumirelabs.yomu.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class AtomicSaveMapperTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    public static class Config {
        @Json5Field(comment="設定") public String name="すみれ";
        public BigDecimal amount=new BigDecimal("0.123456789012345678900");
    }
    public interface Pet { }
    public static class Dog implements Pet {public String name;public Pet friend;}
    public static class Pair {public Pet first,second;}
    public static class BrokenGetter {
        @Json5Field public String getBroken(){throw new IllegalStateException("getter failed");}
    }
    private Path original() throws IOException {
        Path path=folder.getRoot().toPath().resolve("settings.json5");Files.write(path,"original".getBytes(StandardCharsets.UTF_8));return path;
    }
    private String text(Path path) throws IOException {return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);}
    private void noTemps() throws IOException {
        try(DirectoryStream<Path> files=Files.newDirectoryStream(folder.getRoot().toPath(),".yomu-*.tmp")){assertFalse(files.iterator().hasNext());}
    }

    @Test public void ordinaryClassAndGenericOverloadsMatchExistingOutput() throws Exception {
        Path path=original();Config config=new Config();TypeRef<List<Config>> type=new TypeRef<List<Config>>(){};
        for(boolean strict:new boolean[]{false,true}) {
            Json5Mapper mapper=Json5Mapper.builder().writer(new Json5Writer().withIndent("  ").withQuoteKeys(false))
                    .bigDecimals(true).strictJson(strict).build();
            mapper.writeAtomicallyTo(path,config);assertEquals(mapper.write(config),text(path));
            mapper.writeAtomicallyTo(path,config,Config.class);assertEquals(mapper.writeAs(config,Config.class),text(path));
            List<Config> values=Arrays.asList(config,new Config());
            mapper.writeAtomicallyTo(path,values,type);assertEquals(mapper.writeAs(values,type),text(path));
            assertEquals(config.amount,mapper.read(path,type).get(1).amount);noTemps();
        }
    }

    @Test public void graphsAndRegisteredTypesKeepIdentityInBothSyntaxModes() throws Exception {
        Path path=original();Dog dog=new Dog();dog.name="dog";dog.friend=dog;
        Pair pair=new Pair();pair.first=dog;pair.second=dog;
        for(boolean strict:new boolean[]{false,true}) {
            Json5Mapper mapper=new Json5Mapper().withType("dog",Dog.class).withObjectGraph(true).withStrictJson(strict);
            mapper.writeAtomicallyTo(path,pair,Pair.class);
            assertEquals(mapper.writeAs(pair,Pair.class),text(path));
            Pair read=mapper.read(path,Pair.class);assertTrue(read.first instanceof Dog);assertSame(read.first,read.second);
            assertSame(read.first,((Dog)read.first).friend);
            TypeRef<List<Pet>> pets=new TypeRef<List<Pet>>(){};
            mapper.writeAtomicallyTo(path,Arrays.<Pet>asList(dog,dog),pets);
            List<Pet> restored=mapper.read(path,pets);assertSame(restored.get(0),restored.get(1));
            assertSame(restored.get(0),((Dog)restored.get(0)).friend);noTemps();
        }
    }

    @Test public void polymorphicClassOutputWorksWithoutGraphMode() throws Exception {
        Path path=original();Dog dog=new Dog();dog.name="plain";
        Json5Mapper mapper=new Json5Mapper().withType("dog",Dog.class).withStrictJson(true);
        mapper.writeAtomicallyTo(path,dog,Pet.class);
        assertEquals(mapper.writeAs(dog,Pet.class),text(path));assertTrue(mapper.read(path,Pet.class) instanceof Dog);noTemps();
    }

    @Test public void getterCodecAndCycleFailuresPreserveExistingSettings() throws Exception {
        Path path=original();Json5Mapper mapper=new Json5Mapper();
        try {mapper.writeAtomicallyTo(path,new BrokenGetter());fail();}catch(MappingException expected){}
        assertEquals("original",text(path));noTemps();
        Json5Mapper broken=mapper.withCodec(Config.class,new Codec<Config>() {
            public Config decode(Object value,CodecContext context){return new Config();}
            public Object encode(Config value,CodecContext context){throw context.error("codec failed");}
        });
        try {broken.writeAtomicallyTo(path,new Config(),Config.class);fail();}catch(MappingException expected){}
        assertEquals("original",text(path));noTemps();
        Dog cycle=new Dog();cycle.friend=cycle;
        try {mapper.writeAtomicallyTo(path,cycle);fail();}catch(MappingException expected){}
        assertEquals("original",text(path));noTemps();
    }

    @Test public void strictNonFiniteFailureLeavesExistingAndMissingTargetsAlone() throws Exception {
        Path path=original();Json5Mapper mapper=new Json5Mapper().withStrictJson(true);
        TypeRef<List<Double>> type=new TypeRef<List<Double>>(){};
        try {mapper.writeAtomicallyTo(path,Arrays.asList(1.0,Double.NaN),type);fail();}catch(IllegalArgumentException expected){}
        assertEquals("original",text(path));noTemps();
        Path missing=folder.getRoot().toPath().resolve("absent.json");
        try {mapper.writeAtomicallyTo(missing,Double.POSITIVE_INFINITY);fail();}catch(IllegalArgumentException expected){}
        assertFalse(Files.exists(missing));noTemps();
    }
}
