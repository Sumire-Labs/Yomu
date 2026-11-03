package com.sumirelabs.yomu;

import java.io.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class BigDecimalReadTest {
    private static final Json5ReadOptions EXACT=Json5ReadOptions.DEFAULT.withBigDecimals(true);

    @Test public void optInPreservesDecimalDigitsAndScaleAndDefaultRemainsDouble() {
        String literal="0.12345678901234567890123456789012345678900";
        assertEquals(new BigDecimal(literal),Yomu.parse(literal,EXACT));
        assertEquals(Double.valueOf(literal),Yomu.parse(literal));
        assertEquals(Double.valueOf(literal),Yomu.parse(literal,Json5ReadOptions.DEFAULT));
        assertEquals(Double.valueOf(literal),Yomu.parse(literal,256));
        assertEquals(Double.valueOf(literal),Yomu.parse(literal,EXACT.withBigDecimals(false)));
        assertEquals(new BigDecimal("1.2300"),Yomu.parseObject("{amount:1.2300,}",EXACT).get("amount"));
    }

    @Test public void allDecimalSpellingsAreParsedDirectlyIncludingHugeAndTinyValues() {
        for(String literal:new String[]{".5","+.5","-.5","1.","+1.e2","1.2300e+5","1E-400","1e400","-1e-1000","0.000","1.00e+2","1e00000002"})
            assertEquals(literal,new BigDecimal(literal),Yomu.parse(literal,EXACT));
        assertEquals(Double.POSITIVE_INFINITY,Yomu.parse("1e400"));
        assertEquals(Double.valueOf(0),Yomu.parse("1e-400"));
        assertEquals(Integer.MAX_VALUE,((BigDecimal)Yomu.parse("1e-2147483647",EXACT)).scale());
        assertEquals(-Integer.MAX_VALUE,((BigDecimal)Yomu.parse("1e2147483647",EXACT)).scale());
        assertEquals(new BigDecimal(BigInteger.ONE,Integer.MIN_VALUE),Yomu.parse("1e2147483648",EXACT));
        assertEquals(new BigDecimal(BigInteger.TEN,Integer.MIN_VALUE),Yomu.parse("1.0e2147483649",EXACT));
        BigDecimal boundary=new BigDecimal(BigInteger.ONE,Integer.MIN_VALUE);
        assertEquals(boundary,Yomu.parse(Yomu.stringify(boundary),EXACT));
        assertEquals(new BigDecimal("1e2"),Yomu.parse("1e000000000000000000000000000000000002",EXACT));
    }

    @Test public void negativeZeroAndNonFiniteValuesRetainJson5Semantics() {
        for(String literal:new String[]{"-0","-0.","-0.000","-0e400","-0x0","-0X00"}) {
            Object value=Yomu.parse(literal,EXACT);
            assertTrue(literal,value instanceof Double);
            assertEquals(literal,Double.doubleToRawLongBits(-0.0),Double.doubleToRawLongBits((Double)value));
        }
        for(String literal:new String[]{"NaN","+NaN","-NaN","Infinity","+Infinity","-Infinity"}) {
            Object value=Yomu.parse(literal,EXACT);
            assertTrue(value instanceof Double);
            assertEquals(Yomu.parse(literal),value);
        }
        assertEquals(new BigDecimal("0.000"),Yomu.parse("+0.000",EXACT));
    }

    @Test public void integerAndHexadecimalRepresentationsAreUnchanged() {
        for(String literal:new String[]{"0","+42","-42","9223372036854775807","-9223372036854775808",
                "9223372036854775808","0xff","-0xffffffffffffffff"}) {
            Object expected=Yomu.parse(literal),actual=Yomu.parse(literal,EXACT);
            assertEquals(expected,actual);assertEquals(expected.getClass(),actual.getClass());
        }
    }

    @Test public void optionsApplyToAllInputSourcesWithoutChangingOwnershipOrUtf8Rules() throws Exception {
        String source="{日本語:1.23000000000000000000001}";
        Object expected=Yomu.parse(source,EXACT);
        final boolean[] closed={false,false};
        Reader reader=new StringReader(source){public void close(){closed[0]=true;super.close();}};
        InputStream stream=new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)){
            public void close() throws IOException{closed[1]=true;super.close();}
        };
        assertEquals(expected,Yomu.parse(reader,EXACT));
        assertEquals(expected,Yomu.parse(stream,EXACT));
        assertFalse(closed[0]);assertFalse(closed[1]);reader.close();stream.close();
        Path path=Files.createTempFile("yomu-exact-",".json5");
        try {Files.write(path,source.getBytes(StandardCharsets.UTF_8));assertEquals(expected,Yomu.parse(path,EXACT));}
        finally {Files.delete(path);}
        try {Yomu.parse(new ByteArrayInputStream(new byte[]{(byte)0xc3}),EXACT);fail("Malformed UTF-8");}
        catch(IOException expectedError) { }
    }

    @Test public void optionsAreImmutableAndDepthLimitsRemainEffective() {
        Json5ReadOptions shallow=EXACT.withMaxDepth(1);
        assertFalse(Json5ReadOptions.DEFAULT.isBigDecimals());assertTrue(shallow.isBigDecimals());
        assertEquals(256,EXACT.getMaxDepth());assertEquals(1,shallow.getMaxDepth());
        assertEquals(Collections.singletonList(new BigDecimal(".1")),Yomu.parse("[.1]",shallow));
        invalid("[[.1]]",shallow);
        for(int value:new int[]{-1,0,513}) {
            try {EXACT.withMaxDepth(value);fail();}catch(IllegalArgumentException expected) { }
        }
        try {Yomu.parse("0",(Json5ReadOptions)null);fail();}catch(NullPointerException expected) { }
        try {Yomu.parseObject("[.1]",EXACT);fail();}catch(IllegalArgumentException expected) { }
    }

    @Test public void unsupportedExponentsFailExplicitlyWithLocationAndCause() {
        for(String literal:new String[]{"1e2147483649","1e-2147483648","1.0e-2147483647","1e999999999999999999999"}) {
            String source="{\n amount:"+literal+"}";
            Json5Exception error=invalid(source,EXACT);
            assertEquals(source.indexOf(literal),error.getOffset());assertEquals(2,error.getLine());
            assertTrue(error.getCause() instanceof NumberFormatException);
            assertTrue(Yomu.parse(literal) instanceof Double);
        }
    }

    @Test public void malformedGrammarHasSameErrorsInBothModes() {
        for(String source:new String[]{"01.2",".e2","1e","1e+","+.","1.2.3","0x.1","1_0.5","[.5,,]","{a:1.2 b:3}","1e2x","--.5"})
            assertEquals(source,invalid(source,Json5ReadOptions.DEFAULT).getOffset(),invalid(source,EXACT).getOffset());
    }

    @Test public void generatedDecimalsAndWriterRoundTripsPreserveExactValueAndScale() {
        Random random=new Random(0xdec1a1L);
        for(int i=0;i<1000;i++) {
            String literal=(random.nextBoolean()?"-":"+")+(1+random.nextInt(9))+"."
                    +String.format(Locale.ROOT,"%018d",Math.abs(random.nextLong()%1000000000000000000L))
                    +"00e"+(random.nextInt(801)-400);
            BigDecimal expected=new BigDecimal(literal);
            Object actual=Yomu.parse(literal,EXACT);
            assertEquals(expected,actual);
            Object roundTrip=Yomu.parse(Yomu.stringify(actual),EXACT);
            // A scale-zero BigDecimal is written as an integer literal, whose existing type policy remains unchanged.
            if(expected.scale()==0)assertEquals(0,expected.compareTo(new BigDecimal(roundTrip.toString())));
            else assertEquals(expected,roundTrip);
            assertEquals(Double.valueOf(literal),Yomu.parse(literal));
        }
    }

    @Test public void parsingOptionsCanBeSharedConcurrently() throws Exception {
        ExecutorService executor=Executors.newFixedThreadPool(4);
        try {
            List<Future<Object>> values=new ArrayList<Future<Object>>();
            for(int i=0;i<100;i++){final int n=i;values.add(executor.submit(() -> Yomu.parse(n+".0000000000000000001",EXACT)));}
            for(int i=0;i<values.size();i++)assertEquals(new BigDecimal(i+".0000000000000000001"),values.get(i).get());
        } finally {executor.shutdownNow();}
    }

    private static Json5Exception invalid(String source,Json5ReadOptions options) {
        try {Yomu.parse(source,options);fail("Expected failure: "+source);throw new AssertionError();}
        catch(Json5Exception e){return e;}
    }
}
