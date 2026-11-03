package com.sumirelabs.yomu;

import java.io.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Long quoted tokens exercise decoding beyond the fixed input and scratch buffers. */
public class LongStreamStringTest {
    private static Object read(String source,Json5ReadOptions options,final int chunk)throws IOException {
        Reader input=new StringReader(source) {
            @Override public int read(char[] chars,int start,int length)throws IOException {
                return super.read(chars,start,Math.min(length,chunk));
            }
        };
        return Yomu.read(input,options);
    }
    @Test public void longValuesAndKeysMatchStringParserAcrossRefills()throws Exception {
        StringBuilder text=new StringBuilder();
        for(int i=0;i<1200;i++)text.append("日本語\\path\n\t\"\b\f\r").append((char)(i%32));
        Map<String,Object> value=new LinkedHashMap<String,Object>();value.put(text.toString(),Arrays.asList(text.toString(),"tail"));
        for(boolean strict:new boolean[]{false,true}) {
            String source=new Json5Writer().withStrictJson(strict).stringify(value);
            Json5ReadOptions options=Json5ReadOptions.DEFAULT.withStrictJson(strict);
            for(int chunk:new int[]{1,3,127,256,8191,8192})assertEquals(Yomu.read(source,options),read(source,options,chunk));
        }
    }
    @Test public void json5EscapesAndLineContinuationsCrossEveryBoundary()throws Exception {
        StringBuilder text=new StringBuilder("['");
        for(int i=0;i<600;i++)text.append("a\\\r\nb\\\nc\\\u2028d\\\u2029\\x41\\u3042\\0z\\v\\q\\'\\\\");
        text.append("','tail']");String source=text.toString();
        for(int chunk:new int[]{1,2,7,255,8192})assertEquals(Yomu.read(source),read(source,Json5ReadOptions.DEFAULT,chunk));
    }
    @Test public void malformedLongStringsKeepParserErrorLocations()throws Exception {
        char[] chars=new char[9000];Arrays.fill(chars,'a');String prefix="[\r\n\""+new String(chars);
        for(boolean strict:new boolean[]{false,true})for(String suffix:new String[]{"\\u00XZ\"]","\\xQ0\"]","\\9\"]","\\01\"]","\nend\"]"}) {
            Json5ReadOptions options=Json5ReadOptions.DEFAULT.withStrictJson(strict);String source=prefix+suffix;
            Json5Exception expected=null;try{Yomu.read(source,options);}catch(Json5Exception e){expected=e;}
            assertNotNull(expected);
            for(int chunk:new int[]{1,127,8192}) {
                try{read(source,options,chunk);fail("Accepted invalid string");}
                catch(Json5Exception actual){assertEquals(expected.getOffset(),actual.getOffset());assertEquals(expected.getLine(),actual.getLine());assertEquals(expected.getColumn(),actual.getColumn());}
            }
        }
    }
}
