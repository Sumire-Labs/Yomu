package com.sumirelabs.yomu.mapper;

import com.sumirelabs.yomu.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class BuilderSyntaxTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    public static class Config {
        @Json5Field(comment="keep only in JSON5") public String name="すみれ";
        public List<Integer> numbers=Arrays.asList(1,2);
    }
    public interface Pet { }
    public static class Dog implements Pet {public String name;public Pet friend;}
    public static class Pair {public Pet first,second;}

    private static Json5Writer pretty(String indent,boolean expand) {
        return Json5Writer.builder().indent(indent).quoteKeys(false).expandArrays(expand).build();
    }
    private static void strictOutput(Json5Mapper mapper) {
        String output=mapper.write(new Config());
        Yomu.read(output,Json5ReadOptions.DEFAULT.withStrictJson(true));
        assertTrue(output.contains("\"name\""));assertFalse(output.contains("keep only in JSON5"));
        try {mapper.write(Double.NaN);fail("Strict output accepted NaN");}catch(IllegalArgumentException expected){}
    }
    private static void strictInput(Json5Mapper mapper) {
        try {mapper.read("{name:'value'}",Config.class);fail("Strict input accepted JSON5");}catch(Json5Exception expected){}
    }

    @Test public void strictAndFormattingCanBeConfiguredInEitherOrder() {
        Json5Writer format=pretty("  ",false);
        Json5Mapper first=Json5Mapper.builder().strictJson(true).writer(format).build();
        Json5Mapper last=Json5Mapper.builder().writer(format).strictJson(true).build();
        assertEquals(last.write(new Config()),first.write(new Config()));
        assertTrue(first.write(new Config()).contains("\n  \"name\""));
        assertEquals("すみれ",first.read(first.write(new Config()),Config.class).name);
        strictOutput(first);strictInput(first);
        assertFalse("Supplied writer was mutated",format.isStrictJson());
        assertEquals("NaN",format.stringify(Double.NaN));
    }

    @Test public void repeatedFormattingChangesAndBuiltSnapshotsKeepStrictOutput() {
        Json5Mapper.Builder builder=Json5Mapper.builder().strictJson(true).writer(pretty("  ",false));
        Json5Mapper first=builder.build();String original=first.write(new Config());
        builder.writer(pretty("\t",true));Json5Mapper second=builder.build();
        strictOutput(second);strictInput(second);
        assertTrue(second.write(new Config()).contains("\n\t\"name\""));
        assertFalse(original.equals(second.write(new Config())));assertEquals(original,first.write(new Config()));
        builder.strictJson(false);Json5Mapper json5=builder.build();
        assertEquals("NaN",json5.write(Double.NaN));
        assertTrue(json5.write(new Config()).contains("\n\tname:"));
        assertEquals("value",json5.read("{name:'value'}",Config.class).name);
        strictOutput(first);strictOutput(second);
    }

    @Test public void toBuilderInheritsStrictOutputAcrossOtherMutators() {
        Json5Mapper original=new Json5Mapper().withStrictJson(true);
        Json5Mapper edited=original.toBuilder().writer(pretty("  ",false))
                .bigDecimals(true).failOnUnknownFields(true).beanAccessors(true).namingStrategy(NamingPolicy.SNAKE_CASE).build();
        strictInput(edited);strictOutput(edited);
        assertEquals(new BigDecimal("1.2300"),edited.read("1.2300",Object.class));
        try {edited.read("{\"unknown\":1}",Config.class);fail();}catch(MappingException expected){}
        assertFalse(original.write(new Config()).contains("\n"));
    }

    @Test public void defaultsAndExplicitJson5SelectionRemainAvailable() {
        Json5Mapper defaults=Json5Mapper.builder().writer(pretty(" ",false)).build();
        assertEquals("NaN",defaults.write(Double.NaN));assertTrue(defaults.write(new Config()).contains("name:"));
        Json5Mapper reset=Json5Mapper.builder().strictJson(true).writer(pretty("  ",true))
                .strictJson(false).writer(pretty(" ",false)).build();
        assertEquals("value",reset.read("{name:'value'}",Config.class).name);assertEquals("Infinity",reset.write(Double.POSITIVE_INFINITY));
        // A subsequently supplied strict writer is still an explicit output-only choice.
        Json5Mapper outputOnly=Json5Mapper.builder().strictJson(false).writer(pretty(" ",false).withStrictJson(true)).build();
        assertEquals("value",outputOnly.read("{name:'value'}",Config.class).name);strictOutput(outputOnly);
    }

    @Test public void directionalModesAndLegacyReplacementContractsArePreserved() {
        Json5Mapper outputOnly=Json5Mapper.builder().writer(pretty(" ",false).withStrictJson(true))
                .writer(pretty("  ",true)).build();
        assertEquals("value",outputOnly.read("{name:'value'}",Config.class).name);strictOutput(outputOnly);
        Json5Mapper inherited=outputOnly.toBuilder().writer(pretty("\t",false)).build();
        assertEquals("value",inherited.read("{name:'value'}",Config.class).name);strictOutput(inherited);

        Json5Mapper inputOnly=new Json5Mapper().withStrictJson(true).withWriter(pretty(" ",false));
        strictInput(inputOnly);assertEquals("NaN",inputOnly.write(Double.NaN));
        Json5Mapper inputOnlyCopy=inputOnly.toBuilder().writer(pretty("  ",false)).build();
        strictInput(inputOnlyCopy);assertEquals("NaN",inputOnlyCopy.write(Double.NaN));

        Json5Mapper explicitReadOptions=Json5Mapper.builder().strictJson(true)
                .readOptions(Json5ReadOptions.DEFAULT.withBigDecimals(true).withMaxDepth(8)).writer(pretty(" ",false)).build();
        strictOutput(explicitReadOptions);
        assertEquals("value",explicitReadOptions.read("{name:'value'}",Config.class).name);
        assertEquals(new BigDecimal("1.2300"),explicitReadOptions.read("1.2300",Object.class));
        Json5Mapper legacy=outputOnly.withReadOptions(Json5ReadOptions.DEFAULT.withStrictJson(true));
        strictInput(legacy);strictOutput(legacy);
    }

    @Test public void nullWriterDoesNotChangeTheBuilder() {
        Json5Mapper.Builder builder=Json5Mapper.builder().strictJson(true);
        String before=builder.build().write(new Config());
        try {builder.writer(null);fail();}catch(NullPointerException expected){assertEquals("writer",expected.getMessage());}
        assertEquals(before,builder.build().write(new Config()));
        strictOutput(builder.writer(pretty(" ",false)).build());
    }

    @Test public void graphPolymorphismAndAtomicSavingKeepStrictSyntaxAfterFormatting() throws Exception {
        Json5Mapper mapper=Json5Mapper.builder().strictJson(true).objectGraph(true).type("dog",Dog.class)
                .writer(pretty("  ",false)).build();
        Dog dog=new Dog();dog.name="犬";dog.friend=dog;Pair pair=new Pair();pair.first=dog;pair.second=dog;
        Path file=folder.getRoot().toPath().resolve("config.json");
        mapper.writeAtomicallyTo(file,pair,Pair.class);
        Yomu.read(file,Json5ReadOptions.DEFAULT.withStrictJson(true));
        Pair read=mapper.read(file,Pair.class);assertTrue(read.first instanceof Dog);assertSame(read.first,read.second);
        assertSame(read.first,((Dog)read.first).friend);
        Json5Mapper edited=mapper.toBuilder().writer(pretty("\t",true)).build();
        TypeRef<List<Pet>> type=new TypeRef<List<Pet>>(){};
        edited.writeAtomicallyTo(file,Arrays.<Pet>asList(dog,dog),type);
        Yomu.read(file,Json5ReadOptions.DEFAULT.withStrictJson(true));
        List<Pet> restored=edited.read(file,type);assertSame(restored.get(0),restored.get(1));
        assertSame(restored.get(0),((Dog)restored.get(0)).friend);
    }

    @Test public void strictFailureStillProtectsAtomicAndValidatedOutputs() throws Exception {
        Json5Mapper mapper=Json5Mapper.builder().strictJson(true).writer(pretty("  ",false)).build();
        Path file=folder.getRoot().toPath().resolve("settings.json");Files.write(file,"original".getBytes(StandardCharsets.UTF_8));
        try {mapper.writeAtomicallyTo(file,Double.NaN);fail();}catch(IllegalArgumentException expected){}
        assertEquals("original",new String(Files.readAllBytes(file),StandardCharsets.UTF_8));
        try(DirectoryStream<Path> temporary=Files.newDirectoryStream(file.getParent(),".yomu-*.tmp")){assertFalse(temporary.iterator().hasNext());}
        StringBuilder output=new StringBuilder("original");
        try {mapper.writeTo(output,Double.POSITIVE_INFINITY);fail();}catch(IllegalArgumentException expected){}
        assertEquals("original",output.toString());
        try {mapper.writeStreamingTo(new StringBuilder(),Double.NaN);fail();}catch(IllegalArgumentException expected){}
    }
}
