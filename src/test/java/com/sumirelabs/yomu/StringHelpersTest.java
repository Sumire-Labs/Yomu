package com.sumirelabs.yomu;

import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class StringHelpersTest {
    private static final Json5ReadOptions JSON=Json5ReadOptions.DEFAULT.withStrictJson(true);
    @Test public void bodyAndLiteralOperationsHaveDistinctBoundaries() {
        String value="line\n\"quoted\"\\tail";
        assertEquals("line\\n\\\"quoted\\\"\\\\tail",Yomu.escape(value));
        assertEquals(value,Yomu.unescape(Yomu.escape(value)));
        assertEquals('"'+Json5Strings.escape(value)+'"',Json5Strings.quote(value));
        assertEquals(value,Json5Strings.unquote(Json5Strings.quote(value),JSON));
        assertEquals("'x'",Json5Strings.unescape("'x'"));
        assertEquals("x",Json5Strings.unquote("'x'"));
        assertEquals("",Json5Strings.unescape(""));assertEquals("\"\"",Json5Strings.quote(""));
    }
    @Test public void writerAndUtilityUseExactlyTheSameDoubleQuotedEncoding() {
        for(int c=0;c<=0xffff;c++) {
            String value=String.valueOf((char)c);
            String quoted=Json5Strings.quote(value);
            assertEquals(Yomu.stringify(value),quoted);
            assertEquals(value,Json5Strings.unquote(quoted,JSON));
            assertEquals(value,Json5Strings.unescape(Json5Strings.escape(value),JSON));
        }
    }
    @Test public void singleQuotedJson5StringsEscapeTheChosenDelimiter() {
        String value="He said \"I'm here\"\n\ud800";
        String quoted=Json5Strings.quote(value,'\'');
        assertTrue(quoted.startsWith("'He said \"I\\'m"));
        assertEquals(value,Yomu.parse(quoted));
        assertEquals(value,Json5Strings.unquote(quoted));
        assertEquals(value,Json5Strings.unescape(Json5Strings.escape(value,'\''),'\''));
        rejected(() -> Json5Strings.unquote(quoted,JSON));
        rejected(() -> Json5Strings.unescape("x",'\'',JSON));
    }
    @Test public void json5AndStrictUnescapingMatchTheirParserRules() {
        for(String body:new String[]{"\\v","\\x41","\\0","\\a","\\'","a\\\nb","a\\\r\nb","a\\\u2028b"}) {
            assertEquals(Yomu.parse('"'+body+'"'),Json5Strings.unescape(body));
            rejected(() -> Json5Strings.unescape(body,JSON));
        }
        for(String body:new String[]{"\\b\\f\\n\\r\\t","\\\"\\\\\\/","\\u0000\\ud800","\u2028\u2029"})
            assertEquals(Yomu.parse('"'+body+'"',JSON),Json5Strings.unescape(body,JSON));
        rejected(() -> Json5Strings.unescape("\u0000",JSON));
        assertEquals("\u0000",Json5Strings.unescape("\u0000"));
    }
    @Test public void malformedEscapesAndUnescapedDelimitersAreRejectedWithBodyOffsets() {
        for(String body:new String[]{"\\","\\x0g","\\u12","\\u000z","\\01","\\1","a\nb","a\rb","a\"b"})
            rejected(() -> Json5Strings.unescape(body));
        try {Json5Strings.unescape("a\"b");fail();}catch(Json5Exception e){assertEquals(1,e.getOffset());}
        try {Json5Strings.unescape("ab\\");fail();}catch(Json5Exception e){assertEquals(3,e.getOffset());}
    }
    @Test public void unquoteAcceptsOnlyOneLiteralWithoutOutsideTriviaOrTokenInjection() {
        for(String source:new String[]{"","null","1","[]","'x","\"x"," 'x'","'x' ","'x'/*comment*/","'x'+'y'","\"x\" \"y\""})
            rejected(() -> Json5Strings.unquote(source));
        assertEquals("// text",Json5Strings.unquote("'// text'"));
        assertEquals("/* text */",Json5Strings.unquote("'/* text */'"));
    }
    @Test public void unchangedBodiesAndLongEscapedStringsRetainTheirValues() {
        String plain=new String("plain 日本語 / ' text");
        assertSame(plain,Json5Strings.escape(plain));
        StringBuilder value=new StringBuilder();
        for(int i=0;i<4000;i++)value.append(i%2==0?"あ🌸\\\n":"text\t");
        assertEquals(value.toString(),Json5Strings.unescape(Json5Strings.escape(value.toString()),JSON));
        assertEquals(value.toString(),Json5Strings.unquote(Json5Strings.quote(value.toString())));
    }
    @Test public void invalidArgumentsAreRejectedAndOptionsAreNotMutated() {
        for(char quote:new char[]{'`','x','\0'}) {
            rejected(() -> Json5Strings.escape("x",quote));rejected(() -> Json5Strings.quote("x",quote));
            rejected(() -> Json5Strings.unescape("x",quote));
        }
        try {Json5Strings.escape(null);fail();}catch(NullPointerException expected) { }
        try {Json5Strings.unquote(null);fail();}catch(NullPointerException expected) { }
        try {Json5Strings.unescape("x",(Json5ReadOptions)null);fail();}catch(NullPointerException expected) { }
        Json5ReadOptions options=JSON.withBigDecimals(true).withMaxDepth(1);
        assertEquals("x",Json5Strings.unescape("\\x78"));
        rejected(() -> Json5Strings.unescape("\\x78",options));
        assertTrue(options.isStrictJson());assertTrue(options.isBigDecimals());assertEquals(1,options.getMaxDepth());
    }
    @Test public void independentStringOperationsCanRunConcurrently() throws Exception {
        ExecutorService executor=Executors.newFixedThreadPool(4);
        try {
            List<Future<String>> values=new ArrayList<Future<String>>();
            for(int i=0;i<100;i++){final String text="value\\\n\""+i;values.add(executor.submit(() -> Json5Strings.unquote(Json5Strings.quote(text),JSON)));}
            for(int i=0;i<values.size();i++)assertEquals("value\\\n\""+i,values.get(i).get());
        } finally {executor.shutdownNow();}
    }
    private static void rejected(Runnable action) {
        try {action.run();fail("Expected string rejection");}catch(IllegalArgumentException expected) { }
    }
}
