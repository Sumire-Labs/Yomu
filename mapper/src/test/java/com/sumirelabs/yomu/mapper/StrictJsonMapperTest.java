package com.sumirelabs.yomu.mapper;

import com.sumirelabs.yomu.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class StrictJsonMapperTest {
    public static class Config {
        @Json5Field(name="refresh_ticks",aliases={"old"},comment="tick interval") public int refreshTicks;
        public BigDecimal amount;
    }

    @Test public void strictConvenienceConfiguresBothDirectionsWithoutChangingOriginalMapper() {
        Json5Mapper plain=new Json5Mapper();
        Json5Mapper mapper=plain.withWriter(new Json5Writer().withQuoteKeys(false).withIndent("  ")).withStrictJson(true);
        Config value=mapper.read("{\"old\":20,\"amount\":1.25}",Config.class);
        assertEquals(20,value.refreshTicks);
        String output=mapper.write(value);
        assertFalse(output.contains("//"));assertTrue(output.contains("\"refresh_ticks\""));
        assertEquals(20,mapper.read(output,Config.class).refreshTicks);
        invalid(() -> mapper.read("{old:20}",Config.class));
        assertEquals(20,plain.read("{old:20}",Config.class).refreshTicks);
        assertTrue(plain.write(value).contains("// tick interval"));
        assertEquals(20,mapper.withStrictJson(false).read("{old:20,}",Config.class).refreshTicks);
    }

    @Test public void exactNumbersAndExistingOptionsCanBeCombinedWithStrictSyntax() {
        Json5Mapper mapper=new Json5Mapper().withWriter(new Json5Writer().withIndent(" ")).withStrictJson(true)
                .withBigDecimals(true).withBeanAccessors(true).withNamingStrategy(NamingPolicy.SNAKE_CASE)
                .withFailOnUnknownFields(true).withStandardCodecs().withCodec(UUID.class,StandardCodecs.UUID);
        BigDecimal expected=new BigDecimal("0.123456789012345678900");
        assertEquals(expected,mapper.read("0.123456789012345678900",BigDecimal.class));
        invalid(() -> mapper.read(".5",BigDecimal.class));
        rejected(() -> mapper.write(Double.NaN));
        assertEquals(new BigDecimal("1e400"),mapper.read("1e400",BigDecimal.class));
        assertEquals("1E+400",mapper.write(new BigDecimal("1e400")));
    }

    @Test public void readAndWriteOptionsCanBeConfiguredIndependently() {
        Json5Mapper inputOnly=new Json5Mapper().withReadOptions(new Json5ReadOptions().withStrictJson(true));
        invalid(() -> inputOnly.read("{old:1}",Config.class));
        assertEquals("NaN",inputOnly.write(Double.NaN));
        Json5Mapper outputOnly=new Json5Mapper().withWriter(new Json5Writer().withStrictJson(true));
        assertEquals(1,outputOnly.read("{old:1}",Config.class).refreshTicks);
        rejected(() -> outputOnly.write(Double.NaN));
    }

    @Test public void codecsCannotEmitNonFiniteJsonNumbersAndConvertDoesNotReparseTrees() {
        Json5Mapper mapper=new Json5Mapper().withStrictJson(true).withCodec(UUID.class,new Codec<UUID>() {
            public UUID decode(Object value,CodecContext context){return UUID.fromString((String)value);}
            public Object encode(UUID value,CodecContext context){return Collections.singletonMap("n",Double.NaN);}
        });
        rejected(() -> mapper.write(UUID.randomUUID()));
        assertEquals(Double.valueOf(Double.NaN),mapper.convert(Double.NaN,Double.class));
        rejected(() -> mapper.write(Double.NaN));
        assertEquals("NaN",mapper.read("\"NaN\"",String.class));
    }

    @Test public void pathsAndTypedRootsUseStrictReadAndWriteSettings() throws Exception {
        Path file=Files.createTempFile("yomu-strict-mapper-",".json");
        Json5Mapper mapper=new Json5Mapper().withStrictJson(true);
        try {
            Files.write(file,"{\"old\":4}".getBytes(StandardCharsets.UTF_8));
            Config value=mapper.read(file,Config.class);assertEquals(4,value.refreshTicks);
            TypeRef<List<Config>> type=new TypeRef<List<Config>>(){};
            mapper.writeAs(file,Collections.singletonList(value),type);
            assertEquals(4,mapper.read(file,type).get(0).refreshTicks);
            assertFalse(new String(Files.readAllBytes(file),StandardCharsets.UTF_8).contains("//"));
            Files.write(file,"[{old:4}]".getBytes(StandardCharsets.UTF_8));
            try {mapper.read(file,type);fail();}catch(Json5Exception expected) { }
        } finally {Files.delete(file);}
    }

    private static void invalid(Runnable action) {
        try {action.run();fail("Expected JSON syntax error");}catch(Json5Exception expected) { }
    }
    private static void rejected(Runnable action) {
        try {action.run();fail("Expected JSON output rejection");}catch(IllegalArgumentException expected) { }
    }
}
