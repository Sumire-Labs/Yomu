package com.sumirelabs.yomu.mapper;

import com.sumirelabs.yomu.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class BigDecimalMapperTest {
    private static final String LITERAL="1.234567890123456789012345678900";
    public static class Config {public BigDecimal amount;public List<BigDecimal> values;public Map<String,BigDecimal> entries;public Number number;}
    public static class Immutable {
        public final BigDecimal amount;
        @Json5Creator public Immutable(@Json5Field(name="amount") BigDecimal amount){this.amount=amount;}
    }
    public static class Bean {
        private BigDecimal value;
        public BigDecimal getValue(){return value;}
        public void setValue(BigDecimal value){this.value=value;}
    }

    @Test public void exactReadsCoverRootsFieldsContainersAndCreatorArguments() {
        Json5Mapper mapper=new Json5Mapper().withBigDecimals(true);
        BigDecimal expected=new BigDecimal(LITERAL);
        assertEquals(expected,mapper.read(LITERAL,BigDecimal.class));
        Config config=mapper.read("{amount:"+LITERAL+",values:["+LITERAL+"],entries:{a:"+LITERAL+"},number:"+LITERAL+"}",Config.class);
        assertEquals(expected,config.amount);assertEquals(expected,config.values.get(0));
        assertEquals(expected,config.entries.get("a"));assertEquals(expected,config.number);
        assertEquals(expected,mapper.read(mapper.write(config),Config.class).amount);
        assertEquals(expected,mapper.read("{amount:"+LITERAL+"}",Immutable.class).amount);
        assertEquals(expected,mapper.read("["+LITERAL+"]",new TypeRef<List<BigDecimal>>(){}).get(0));
        assertEquals(expected,mapper.withBeanAccessors(true).read("{value:"+LITERAL+"}",Bean.class).getValue());
    }

    @Test public void defaultsRemainDoubleAndSettingsSurviveAllConfigurationMethods() {
        Json5Mapper plain=new Json5Mapper(),exact=plain.withBigDecimals(true);
        assertEquals(Double.valueOf(LITERAL),plain.read(LITERAL,Object.class));
        assertEquals(new BigDecimal(LITERAL),exact.withWriter(new Json5Writer()).withBeanAccessors(true)
                .withNamingStrategy(NamingPolicy.SNAKE_CASE).withFailOnUnknownFields(true).withStandardCodecs()
                .withCodec(UUID.class,StandardCodecs.UUID).read(LITERAL,Object.class));
        assertEquals(Double.valueOf(LITERAL),exact.withBigDecimals(false).read(LITERAL,Object.class));
        assertEquals(Double.valueOf(LITERAL),exact.convert(Yomu.parse(LITERAL),Object.class));
        Json5Mapper shallow=exact.withReadOptions(new Json5ReadOptions().withMaxDepth(1)).withBigDecimals(true);
        assertEquals(new BigDecimal(LITERAL),shallow.read(LITERAL,Object.class));
        try {shallow.read("[[0.1]]",Object.class);fail();}catch(Json5Exception expected) { }
    }

    @Test public void codecsReceiveUnroundedValuesAndObjectFieldsRemainNumeric() {
        Json5Mapper mapper=new Json5Mapper().withBigDecimals(true).withCodec(BigDecimal.class,new Codec<BigDecimal>() {
            public BigDecimal decode(Object value,CodecContext context){assertTrue(value instanceof BigDecimal);return (BigDecimal)value;}
            public Object encode(BigDecimal value,CodecContext context){return value;}
        });
        assertEquals(new BigDecimal(LITERAL),mapper.read(LITERAL,BigDecimal.class));
    }

    @Test(timeout=10000) public void boundedNumericConversionsRejectHugePowersWithoutExpandingThem() {
        Json5Mapper mapper=new Json5Mapper().withBigDecimals(true);
        for(Class<?> type:new Class<?>[]{byte.class,short.class,int.class,long.class}) {
            failure(() -> mapper.read("1e1000000000",type));
            failure(() -> mapper.read("1e-1000000000",type));
        }
        assertEquals(new BigDecimal("1e400"),mapper.read("1e400",BigDecimal.class));
        failure(() -> mapper.read("1e400",double.class));
        failure(() -> mapper.read("1e100",float.class));
        failure(() -> mapper.read("9223372036854775807.1",long.class));
        assertEquals(Long.valueOf(Long.MAX_VALUE),mapper.read("9223372036854775807.0",long.class));
        assertEquals(Integer.valueOf(100),mapper.read("1e2",int.class));
        assertEquals(Double.valueOf(-0.0),mapper.read("-0.0",double.class));
        assertEquals(Double.valueOf(Double.NEGATIVE_INFINITY),mapper.read("-Infinity",double.class));
        failure(() -> mapper.read("NaN",BigDecimal.class));
        failure(() -> mapper.read("Infinity",BigDecimal.class));
    }

    @Test public void pathReadsUseTheSameOptionsForClassAndTypeRef() throws Exception {
        Path path=Files.createTempFile("yomu-exact-mapper-",".json5");
        Json5Mapper mapper=new Json5Mapper().withBigDecimals(true);
        try {
            Files.write(path,("{amount:"+LITERAL+"}").getBytes(StandardCharsets.UTF_8));
            assertEquals(new BigDecimal(LITERAL),mapper.read(path,Config.class).amount);
            Files.write(path,("["+LITERAL+"]").getBytes(StandardCharsets.UTF_8));
            assertEquals(new BigDecimal(LITERAL),mapper.read(path,new TypeRef<List<BigDecimal>>(){}).get(0));
        } finally {Files.delete(path);}
    }

    private static void failure(Runnable action) {
        try {action.run();fail("Expected mapping failure");}catch(MappingException expected) {assertEquals("$",expected.getPath());}
    }
}
