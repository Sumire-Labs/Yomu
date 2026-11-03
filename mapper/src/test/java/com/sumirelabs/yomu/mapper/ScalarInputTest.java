package com.sumirelabs.yomu.mapper;

import org.junit.Test;
import static org.junit.Assert.*;

public class ScalarInputTest {
    public static class CustomNumber extends Number {
        private static final long serialVersionUID=1;
        public int value;
        public int intValue(){return value;}
        public long longValue(){return value;}
        public float floatValue(){return value;}
        public double doubleValue(){return value;}
    }
    public static class Values {public boolean enabled;public int count;public long large;public double fraction;}
    @Test public void customNumberClassesStillUsePojoConversion() {
        assertEquals(7,new Json5Mapper().read("{value:7}",CustomNumber.class).value);
    }
    @Test public void directScalarsKeepRangesSignedZeroAndCodecOverrides() {
        Json5Mapper mapper=new Json5Mapper();
        Values value=mapper.read("{enabled:true,count:2147483647,large:9223372036854775807,fraction:-0}",Values.class);
        assertTrue(value.enabled);assertEquals(Integer.MAX_VALUE,value.count);assertEquals(Long.MAX_VALUE,value.large);
        assertEquals(Double.doubleToRawLongBits(-0.0),Double.doubleToRawLongBits(value.fraction));
        assertTrue(Double.isNaN(mapper.read("{fraction:NaN}",Values.class).fraction));
        for(String invalid:new String[]{"{count:2147483648}","{count:-2147483649}","{count:1.5}","{count:null}"}) {
            try {mapper.read(invalid,Values.class);fail("Invalid int accepted");}
            catch(MappingException expected){assertEquals("$[\"count\"]",expected.getPath());}
        }
        Json5Mapper customized=mapper.withCodec(int.class,new Codec<Integer>() {
            public Integer decode(Object input,CodecContext context){return 42;}
            public Object encode(Integer input,CodecContext context){return input;}
        });
        assertEquals(42,customized.read("{count:3}",Values.class).count);
    }
}
