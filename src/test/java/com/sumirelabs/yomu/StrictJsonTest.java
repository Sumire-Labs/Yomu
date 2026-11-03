package com.sumirelabs.yomu;

import java.io.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class StrictJsonTest {
    private static final Json5ReadOptions JSON=Json5ReadOptions.DEFAULT.withStrictJson(true);
    private static final Json5Writer WRITER=new Json5Writer().withStrictJson(true);

    @Test public void allJsonRootTypesAndStandardEscapesAreAccepted() {
        for(String source:new String[]{"{}","[]","null","true","false","0","-0","-12","12.50","1e+2","1E-2",
                "\r\n\t {\"a\": [1, false, null, \"text\"]} \r\n",
                "\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u0000\\ud800\"","\"\u2028\u2029\ud800\""})
            assertEquals(source,Yomu.parse(source),Yomu.parse(source,JSON));
        assertEquals(2L,Yomu.parseObject("{\"a\":1,\"a\":2}",JSON).get("a"));
        assertEquals("Infinity",Yomu.parse("\"Infinity\"",JSON));
        assertEquals("NaN",Yomu.parse("\"NaN\"",JSON));
    }

    @Test public void json5ExtensionsAreRejectedWithoutChangingTheDefaultMode() {
        for(String source:new String[]{"// comment\n1","/* comment */1","{a:1}","{'a':1}","'text'","[1,]","{a:1,}",
                "{\"a\":1,}","+1",".5","-.5","1.","1.e2","0xff","-0xff","NaN","+NaN","-NaN",
                "Infinity","+Infinity","-Infinity","\u00a01\u00a0","\ufeff1","\u20281\u2029","\u000b1","\u000c1",
                "\"\\v\"","\"\\0\"","\"\\x41\"","\"\\a\"","\"\\'\"","\"a\\\nb\"","\"a\\\r\nb\""}) {
            Yomu.parse(source);
            invalid(source);
        }
        for(String source:new String[]{"{\"a\"/*x*/:1}","{\"a\":/*x*/1}","[1/*x*/,2]","1//x","[1,/*x*/]"}) {
            Yomu.parse(source);invalid(source);
        }
    }

    @Test public void strictStringsRejectEveryRawControlAndNonJsonEscape() {
        for(int c=0;c<32;c++)invalid("\"a"+(char)c+"b\"");
        for(int c=0;c<256;c++) {
            String suffix=c=='u'?"0000":"";
            String source="\"\\"+(char)c+suffix+"\"";
            if("\"\\/bfnrtu".indexOf((char)c)>=0)Yomu.parse(source,JSON);
            else invalid(source);
        }
        for(String source:new String[]{"\"\\u000g\"","\"\\u123\"","\"\\01\"","\"a\\\u2028b\"","\"a\\\u2029b\""})invalid(source);
    }

    @Test public void onlyFourWhitespaceCharactersAreSkipped() {
        for(char c:new char[]{' ','\t','\r','\n'})assertEquals(1L,Yomu.parse(c+"1"+c,JSON));
        for(int cp:new int[]{0x85,0xa0,0x1680,0x180e,0x2000,0x2007,0x2028,0x2029,0x202f,0x205f,0x3000,0xfeff})
            invalid(new String(Character.toChars(cp))+"1");
    }

    @Test public void strictNumberGrammarAndRepresentationAreIndependent() {
        for(String source:new String[]{"01","-01","00.1","1e","1e+","--1","1e2x","[1,,2]","[,]","truefalse"})invalid(source);
        assertEquals(Double.valueOf(Double.POSITIVE_INFINITY),Yomu.parse("1e400",JSON));
        assertEquals(new BigDecimal("1e400"),Yomu.parse("1e400",JSON.withBigDecimals(true)));
        assertEquals(new BigDecimal("0.123456789012345678900"),Yomu.parse("0.123456789012345678900",JSON.withBigDecimals(true)));
        assertEquals(Double.doubleToRawLongBits(-0.0),Double.doubleToRawLongBits((Double)Yomu.parse("-0.0",JSON)));
        invalid("[NaN]");invalid("{\"n\":Infinity}");
    }

    @Test public void strictValidationCannotBeBypassedByStringCaches() {
        StringBuilder source=new StringBuilder("[");
        for(int i=0;i<300;i++)source.append("\"value\\n\",\"value\\u000a\",");
        String prefix=source.toString();
        source.append("\"end\"]");
        assertEquals(601,((List<?>)Yomu.parse(source.toString(),JSON)).size());
        for(String tail:new String[]{"\"value\\x0a\"]","\"value\\u000g\"]","'end']","\"value\n\"]"})invalid(prefix+tail);
    }

    @Test public void optionsAreImmutableAndBothNumericAndDepthSettingsSurviveChanges() {
        assertFalse(Json5ReadOptions.DEFAULT.isStrictJson());
        Json5ReadOptions exact=JSON.withBigDecimals(true).withMaxDepth(1);
        assertTrue(exact.isStrictJson());assertEquals(1,exact.getMaxDepth());
        assertEquals(Collections.singletonList(new BigDecimal(".5")),Yomu.parse("[0.5]",exact));
        try {Yomu.parse("[[0.5]]",exact);fail();}catch(Json5Exception expected) { }
        assertEquals(new BigDecimal(".5"),Yomu.parse(".5",exact.withStrictJson(false)));
        assertTrue(exact.withBigDecimals(false).isStrictJson());
    }

    @Test public void strictOptionsApplyToReadersStreamsAndFiles() throws Exception {
        final boolean[] closed={false,false};
        Reader reader=new StringReader("{\"amount\":1.25}"){public void close(){closed[0]=true;}};
        InputStream stream=new ByteArrayInputStream("{\"amount\":1.25}".getBytes(StandardCharsets.UTF_8)){
            public void close(){closed[1]=true;}
        };
        assertEquals(Yomu.parse(reader,JSON),Yomu.parse(stream,JSON));
        assertFalse(closed[0]);assertFalse(closed[1]);reader.close();stream.close();
        Path file=Files.createTempFile("yomu-strict-",".json");
        try {
            Files.write(file,"{\"日本語\":1}".getBytes(StandardCharsets.UTF_8));
            assertEquals(1L,((Map<?,?>)Yomu.parse(file,JSON)).get("日本語"));
            Files.write(file,"{a:1}".getBytes(StandardCharsets.UTF_8));
            try {Yomu.parse(file,JSON);fail();}catch(Json5Exception expected) { }
        } finally {Files.delete(file);}
        try {Yomu.parse(new ByteArrayInputStream(new byte[]{(byte)0xc3}),JSON);fail();}catch(IOException expected) { }
    }

    @Test public void writerQuotesKeysAndOmitsOnlyCommentMetadata() {
        Map<String,Object> value=new LinkedHashMap<String,Object>();
        value.put("a",Yomu.commented("first\nsecond",1));
        value.put("items",Arrays.asList(Yomu.commented("item","x"),Yomu.commented("outer",Yomu.commented("inner",true))));
        Json5Writer pretty=WRITER.withQuoteKeys(false).withIndent("  ").withExpandArrays(false);
        String output=pretty.stringify(Yomu.commented("root",value));
        assertFalse(output.contains("//"));assertTrue(output.contains("\"a\""));
        assertEquals(Arrays.asList("x",true),((Map<?,?>)Yomu.parse(output,JSON)).get("items"));
        assertTrue(pretty.isStrictJson());assertFalse(new Json5Writer().isStrictJson());
        String json5=pretty.withStrictJson(false).stringify(value);
        assertTrue(json5.contains("// first"));assertTrue(json5.contains("a:"));
    }

    @Test public void nonFiniteNumbersAreRejectedAndLargeFiniteDecimalsRemainLegal() {
        for(Number number:new Number[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,
                Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY}) {
            rejected(() -> WRITER.stringify(number));
            rejected(() -> WRITER.stringify(Collections.singletonMap("n",Arrays.asList(1,number))));
            assertEquals(number.toString(),Yomu.stringify(number));
        }
        assertEquals("-0.0",WRITER.stringify(-0.0));
        assertEquals(new BigDecimal("1e400"),Yomu.parse(WRITER.stringify(new BigDecimal("1e400")),JSON.withBigDecimals(true)));
        assertEquals("\"NaN\"",WRITER.stringify("NaN"));
        rejected(() -> WRITER.stringify(new BadDecimal()));
    }
    private static final class BadDecimal extends BigDecimal {
        private static final long serialVersionUID=1L;
        BadDecimal(){super("1");}
        public String toString(){return "NaN";}
    }

    @Test public void strictOutputEscapesStringsAndDoesNotWritePartialDataOnValidationFailure() throws Exception {
        String value="\u0000\n\"\\\ud800\ud83c\udf38\u2028\u2029";
        assertEquals(value,Yomu.parse(WRITER.stringify(value),JSON));
        StringBuilder output=new StringBuilder("unchanged");
        try {WRITER.write(Arrays.asList(1,Double.NaN),output);fail();}catch(IllegalArgumentException expected) { }
        assertEquals("unchanged",output.toString());
        Path file=Files.createTempFile("yomu-strict-writer-",".json");
        try {
            Files.write(file,"unchanged".getBytes(StandardCharsets.UTF_8));
            try {WRITER.write(Double.NaN,file);fail();}catch(IllegalArgumentException expected) { }
            assertEquals("unchanged",new String(Files.readAllBytes(file),StandardCharsets.UTF_8));
            WRITER.write(Collections.singletonMap("text",value),file);
            assertEquals(value,((Map<?,?>)Yomu.parse(file,JSON)).get("text"));
        } finally {Files.delete(file);}
    }

    @Test public void strictConfigurationsAreSafeToReuseAcrossThreads() throws Exception {
        ExecutorService executor=Executors.newFixedThreadPool(4);
        try {
            List<Future<Object>> values=new ArrayList<Future<Object>>();
            for(int i=0;i<100;i++){final int n=i;values.add(executor.submit(() -> Yomu.parse(WRITER.stringify(Collections.singletonMap("n",n)),JSON)));}
            for(int i=0;i<values.size();i++)assertEquals((long)i,((Map<?,?>)values.get(i).get()).get("n"));
        } finally {executor.shutdownNow();}
    }

    private static void invalid(String source) {
        try {Yomu.parse(source,JSON);fail("Invalid JSON was accepted: "+source);}
        catch(Json5Exception expected){assertTrue(expected.getOffset()>=0);assertTrue(expected.getLine()>=1);}
    }
    private static void rejected(Runnable action) {
        try {action.run();fail("Expected output rejection");}catch(IllegalArgumentException expected) { }
    }
}
