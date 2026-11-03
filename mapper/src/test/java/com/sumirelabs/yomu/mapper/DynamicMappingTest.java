package com.sumirelabs.yomu.mapper;

import com.sumirelabs.yomu.Yomu;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Stream;
import javax.tools.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** Modern fixtures are compiled at test time and never enter the Java 8 production JAR. */
public class DynamicMappingTest {
    @Test public void realParameterNamesAreUsedWhenPresent() throws Exception {
        try (Compiled fixture=compile("NamedModel",false)) {
            Class<?> type=fixture.loader.loadClass("fixtures.NamedModel");
            Object value=new Json5Mapper().read("{count:4,label:'sumire'}",type);
            assertEquals(4,type.getField("count").get(value));
            assertEquals("sumire",type.getField("label").get(value));
        }
    }

    @Test public void recordsUseCanonicalConstructorsAndPropagateOptionsWithoutDuplicateProperties() throws Exception {
        boolean records;
        try {Class.class.getMethod("isRecord");records=true;}catch(NoSuchMethodException e){records=false;}
        assumeTrue("Records require Java 16 or later",records);
        try (Compiled fixture=compile("ModernModels",true)) {
            Json5Mapper mapper=new Json5Mapper().withFailOnUnknownFields(true);
            Class<?> type=fixture.loader.loadClass("fixtures.ModernModels$Config");
            Object value=mapper.read("{old:20,day:'2024/02/29',entries:[{label:'sumire'}]}",type);
            assertEquals(20,type.getMethod("refreshTicks").invoke(value));
            assertEquals(LocalDate.of(2024,2,29),type.getMethod("day").invoke(value));
            String encoded=mapper.write(value);
            assertTrue(encoded.contains("// tick interval"));
            assertEquals(value,mapper.read(encoded,type));
            Map<String,Object> out=Yomu.parseObject(encoded);
            assertEquals(new HashSet<String>(Arrays.asList("refresh_ticks","day","entries")),out.keySet());
            failure(() -> mapper.read("{old:1,day:'2024/02/29'}",type),"$[\"entries\"]");
            failure(() -> mapper.read("{old:1,refresh_ticks:2,day:null,entries:[]}",type),"$[\"refresh_ticks\"]");
            failure(() -> mapper.read("{old:null,day:null,entries:[]}",type),"$[\"old\"]");
            fixture.loader.loadClass("fixtures.ModernModels").getMethod("verifyGeneric",Json5Mapper.class).invoke(null,mapper);
            fixture.loader.loadClass("fixtures.ModernModels").getMethod("verifyMapKeys",Json5Mapper.class).invoke(null,mapper);
            fixture.loader.loadClass("fixtures.ModernModels").getMethod("verifyGraph",Json5Mapper.class).invoke(null,mapper);
            fixture.loader.loadClass("fixtures.ModernModels").getMethod("verifyPolymorphism",Json5Mapper.class).invoke(null,mapper);
            fixture.loader.loadClass("fixtures.ModernModels").getMethod("verifyStreaming",Json5Mapper.class).invoke(null,mapper);
            Class<?> odd=fixture.loader.loadClass("fixtures.ModernModels$Odd");
            assertEquals("{\"getCount\":4}",mapper.withBeanAccessors(true).write(mapper.read("{getCount:4}",odd)));
            Class<?> optional=fixture.loader.loadClass("fixtures.ModernModels$Optional");
            Object opt=mapper.read("{secret:3,generated:false}",optional);
            assertEquals(0,optional.getMethod("count").invoke(opt));
            assertEquals(true,optional.getMethod("generated").invoke(opt));
            assertNull(optional.getMethod("secret").invoke(opt));
            Class<?> explicit=fixture.loader.loadClass("fixtures.ModernModels$Explicit");
            assertEquals("ABC",explicit.getMethod("value").invoke(mapper.read("{value:'abc'}",explicit)));
            Class<?> checked=fixture.loader.loadClass("fixtures.ModernModels$Checked");
            MappingException error=failure(() -> mapper.read("{count:-1}",checked),"$");
            assertTrue(error.getCause() instanceof IllegalStateException);
            assertEquals(value,mapper.withNamingStrategy(NamingPolicy.SNAKE_CASE).read(encoded,type));
            Class<?> decimal=fixture.loader.loadClass("fixtures.ModernModels$Decimal");
            String literal="0.1234567890123456789012345678900";
            Object exact=mapper.withBigDecimals(true).read("{amount:"+literal+"}",decimal);
            assertEquals(new java.math.BigDecimal(literal),decimal.getMethod("amount").invoke(exact));
            Json5Mapper strict=mapper.withStrictJson(true).withBigDecimals(true);
            Object strictRecord=strict.read("{\"amount\":"+literal+"}",decimal);
            assertEquals(strictRecord,strict.read(strict.write(strictRecord),decimal));
            Class<?> staticRecord=fixture.loader.loadClass("fixtures.ModernModels$StaticRecord");
            Object staticValue=mapper.read("{count:2,schema:999}",staticRecord);
            assertEquals(2,staticRecord.getMethod("count").invoke(staticValue));
            assertEquals(1L,Yomu.parseObject(mapper.write(staticValue)).get("schema"));
            assertEquals("{\"schema\":1}",mapper.writeStatics(staticRecord));
        }
    }

    private static Compiled compile(String name,boolean record) throws Exception {
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
        assertNotNull("Fixture tests require a JDK",compiler);
        Compiled fixture=new Compiled();
        try {
            Path source=fixture.root.resolve(name+".java");
            try(InputStream input=DynamicMappingTest.class.getResourceAsStream("/records/"+name+".java.txt")) {
                assertNotNull(input);Files.copy(input,source);
            }
            String classpath=Paths.get(Json5Mapper.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    +File.pathSeparator+Paths.get(Yomu.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            List<String> options=new ArrayList<String>(Arrays.asList("-encoding","UTF-8","-classpath",classpath,"-d",fixture.root.toString(),"-parameters"));
            if(record)Collections.addAll(options,"--release","17");
            else Collections.addAll(options,"-source","8","-target","8","-Xlint:-options");
            StringWriter diagnostics=new StringWriter();
            try(StandardJavaFileManager manager=compiler.getStandardFileManager(null,Locale.ROOT,StandardCharsets.UTF_8)) {
                boolean success=compiler.getTask(diagnostics,manager,null,options,null,manager.getJavaFileObjects(source.toFile())).call();
                assertTrue(diagnostics.toString(),success);
            }
            fixture.loader=new URLClassLoader(new URL[]{fixture.root.toUri().toURL()},DynamicMappingTest.class.getClassLoader());
            return fixture;
        } catch(Exception | AssertionError error) { fixture.close();throw error; }
    }

    private static final class Compiled implements AutoCloseable {
        final Path root=Files.createTempDirectory("yomu-mapping-fixture-");
        URLClassLoader loader;
        Compiled() throws IOException { }
        public void close() throws IOException {
            if(loader!=null)loader.close();
            try(Stream<Path> files=Files.walk(root)) {
                for(Path path:(Iterable<Path>)files.sorted(Comparator.reverseOrder())::iterator)Files.delete(path);
            }
        }
    }
    private static MappingException failure(Runnable action,String path) {
        try {action.run();fail("Expected mapping failure");throw new AssertionError();}
        catch(MappingException e){assertEquals(e.getMessage(),path,e.getPath());return e;}
    }
}
