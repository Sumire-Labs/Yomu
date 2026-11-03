package com.sumirelabs.yomu;

import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class StreamOptimizationTest {
    @Test public void commaTransitionsKeepGrammarAndLocationsAcrossRefills()throws Exception {
        for(boolean strict:new boolean[]{false,true})for(int chunk:new int[]{1,2,7,8192}) {
            Json5ReadOptions options=Json5ReadOptions.DEFAULT.withStrictJson(strict);
            for(String comma:new String[]{",",", ",",\r\n",",/*x*/",",//x\n",",,",",:",""}) {
                for(String tail:new String[]{"\"y\":true}","}","\"y\":[]}","'y':2}","y:2}","\"y\"true}",""})
                    same("{\"x\":0\r"+comma+tail,options,chunk);
                for(String tail:new String[]{"1]","true]","null]","[]]","{}]","'x']","]",",]",":1]",""})
                    same("[0\r"+comma+tail,options,chunk);
            }
        }
    }
    @Test public void colonTransitionKeepsValueGrammarAndErrorLocationsAcrossRefills()throws Exception {
        String[] values={"0","-0","1.25","true","false","null","\"x\\ny\"","'x'","[]","{}","[{},1]","{\"x\":[]}","NaN","Infinity","",":1",",1","}","/*open"};
        for(boolean strict:new boolean[]{false,true})for(int chunk:new int[]{1,2,7,8192}) {
            Json5ReadOptions options=Json5ReadOptions.DEFAULT.withStrictJson(strict);
            for(String value:values)for(String trivia:new String[]{""," \r\n","/*x*/","//x\n"}) {
                same("{\"key\":"+trivia+value+"}",options,chunk);
                same("[{\"key\":"+trivia+value+"},0]",options,chunk);
            }
        }
    }
    @Test public void foldedAsciiIdentifierStartsKeepTheJson5Set() {
        String allowed="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz$_";
        for(int c=0;c<128;c++)assertEquals("ASCII "+c,allowed.indexOf(c)>=0,Json5Lexical.identifierStart(c));
        assertFalse(Json5Lexical.identifierStart(-1));assertFalse(Json5Lexical.identifierStart(128));
        assertFalse(Json5Lexical.identifierStart('\u00a2'));assertTrue(Json5Lexical.identifierStart('\u03b1'));
        assertTrue(Json5Lexical.identifierStart(0x10400));
    }
    @Test public void decodedRunsKeepControlValidationAndPhysicalLineTracking()throws Exception {
        for(boolean strict:new boolean[]{false,true})for(int chunk:new int[]{1,7,8192}) {
            Json5ReadOptions options=Json5ReadOptions.DEFAULT.withStrictJson(strict);
            for(int c=0;c<128;c++) {
                String body="\\u0041"+(char)c+"z";
                same("[\""+body+"\"]",options,chunk);
                same("{\""+body+"\":1}",options,chunk);
            }
            for(char c:new char[]{'\u2027','\u2028','\u2029','\u202a'}) {
                String text="\"\\n"+c+"x\"";same(text,options,chunk);
                try(Json5StreamReader reader=Yomu.stream(chunksForControls(text,chunk),options)) {
                    assertEquals("\n"+c+"x",reader.nextString());
                    assertEquals(c=='\u2028'||c=='\u2029'?2:1,reader.getLine());reader.requireEnd();
                }
            }
        }
    }
    private static Reader chunksForControls(String text,final int chunk) {
        return new StringReader(text){public int read(char[] b,int off,int len)throws IOException{return super.read(b,off,Math.min(chunk,len));}};
    }
    @Test public void triviaFastPathPreservesWhitespaceCommentsAndInvalidCharacters()throws Exception {
        String[] separators={""," ","\t","\r\n","\u000b","\f","\u00a0","\u1680","\u2000","\u2007","\u2028","\u2029","\u202f","\u205f","\u3000","\ufeff","/*x*/","//x\r\n","/","\u0085","\u180e","\u200b"};
        for(boolean strict:new boolean[]{false,true})for(int chunk:new int[]{1,7,8192}) {
            Json5ReadOptions opts=Json5ReadOptions.DEFAULT.withStrictJson(strict);
            for(String s:separators) {
                same(s+"["+s+"{\"x\""+s+":"+s+"true"+s+"}"+s+","+s+"null"+s+"]"+s,opts,chunk);
                same("0"+s+"1",opts,chunk);
            }
            for(int c=0;c<128;c++)same("[0,"+(char)c+"1]",opts,chunk);
        }
    }
    @Test public void hexDigitsAcceptOnlyAsciiDigitsAcrossTheCharRange() {
        for(int c=0;c<=Character.MAX_VALUE;c++) {
            int expected="0123456789abcdef".indexOf(c);
            if(expected<0){int upper="ABCDEF".indexOf(c);if(upper>=0)expected=upper+10;}
            assertEquals("U+"+Integer.toHexString(c),expected,Json5Lexical.hexDigit((char)c));
        }
    }
    private static Object read(String text,Json5ReadOptions options,int chunk)throws IOException {
        Reader input=new StringReader(text){public int read(char[] b,int off,int len)throws IOException{return super.read(b,off,Math.min(chunk,len));}};
        return Yomu.parse(input,options);
    }
    private static void same(String text,Json5ReadOptions options,int chunk)throws IOException {
        Object expected;
        try{expected=Yomu.parse(text,options);}catch(Json5Exception original){
            try{read(text,options,chunk);fail("Stream accepted invalid input: "+text);}catch(Json5Exception error){assertTrue(error.getLine()>0);}return;
        }
        assertEquals(text,Yomu.stringify(expected),Yomu.stringify(read(text,options,chunk)));
    }
    @Test public void tokensAcrossAllBufferBoundariesMatchReference()throws Exception {
        String body="{text:'a\\n\\t\\x41\\u3042',path:'a\\\\b',label:'日本語',𐐀:1,\\u0061:'z',v:[true,false,null,+17,-0,1.25,0xFF,],}";
        for(int padding=8100;padding<8240;padding++)same("/*"+String.join("",Collections.nCopies(padding," "))+"*/"+body,Json5ReadOptions.DEFAULT,8192);
        for(int chunk:new int[]{1,2,3,7,31,64,127,256,8192})same(body,Json5ReadOptions.DEFAULT,chunk);
        for(int length:new int[]{64,128,255,256,257,8191,8192,10000}) {
            String escaped="[\""+String.join("",Collections.nCopies(length,"\\u0041"))+"\"]";
            for(boolean strict:new boolean[]{false,true})same(escaped,Json5ReadOptions.DEFAULT.withStrictJson(strict),8192);
        }
    }
    @Test public void escapeKindsControlsAndLoneSurrogatesMatchInBothModes()throws Exception {
        for(boolean strict:new boolean[]{false,true}) {
            Json5ReadOptions opts=Json5ReadOptions.DEFAULT.withStrictJson(strict);
            for(int c=0;c<128;c++)same("[\"a\\"+(char)c+"z\"]",opts,8192);
            for(String body:new String[]{"\\u0000","\\uD800","\\uDC00","\\uD800\\uDC00","\\u2028","\\x41","\\0","\\01","\\9","\\\r\n","\\\u2028","\\u00XZ","\\u{41}"})same("[\""+body+"\"]",opts,8192);
            for(char c:new char[]{0,1,9,10,13,31,32,'\u2028','\u2029','\ud800','\udc00'})same("[\"a"+c+"z\"]",opts,8192);
        }
    }
    @Test public void numericAndKeywordFastPathsDoNotWeakenGrammar()throws Exception {
        for(boolean strict:new boolean[]{false,true})for(boolean exact:new boolean[]{false,true}) {
            Json5ReadOptions opts=Json5ReadOptions.DEFAULT.withStrictJson(strict).withBigDecimals(exact);
            for(String token:new String[]{"0","-0","+0","17","-17","999999999999999999","9223372036854775807","-9223372036854775808","9223372036854775808","01","-01","+01","1.",".1","-.1","1e400","1e2147483648","0xFF","-0x0","true","false","null","truefalse","nullx","+","-","--1","1_0","1true"})same("["+token+"]",opts,8192);
        }
    }
    @Test public void literalCacheCannotValidateDifferentEscapesOrQuotes()throws Exception {
        for(String second:new String[]{"'a\\x41'","'a\\x4Z'","'a\\u0041'","'a\\u00XZ'","'a\\0'","'a\\09'","'a\\q'","\"a\\'\"","'a\\\nb'","'a\nb'"}) {
            same("['a\\x41',"+second+",'a\\x41']",Json5ReadOptions.DEFAULT,8192);
            same("[\"a\\u0041\","+second+",\"a\\u0041\"]",Json5ReadOptions.DEFAULT.withStrictJson(true),8192);
        }
        same("[\"x\",\"x\"oops]",Json5ReadOptions.DEFAULT,8192);
        same("{text:'text',\"text\":'text'}",Json5ReadOptions.DEFAULT,8192);
        String value="ab"+String.join("",Collections.nCopies(62,"x"));
        String prefix="[\""+value+"\",";
        String padding="/*"+String.join("",Collections.nCopies(8190-prefix.length()-4," "))+"*/";
        try(Json5StreamReader r=Yomu.stream(new StringReader(prefix+padding+"\"ab\",\""+value+"\"]"))) {
            r.beginArray();String first=r.nextString();assertEquals("ab",r.nextString());
            // A different value split across the buffer must not disable later sharing of this slot.
            assertSame(first,r.nextString());r.endArray();r.requireEnd();
        }
        String maximum="\"a@abcd"+String.join("",Collections.nCopies(20,"\\u0041"))+"\"";
        assertEquals(128,maximum.length()); // Exercise the entire last fixed slot.
        for(boolean strict:new boolean[]{false,true})try(Json5StreamReader r=Yomu.stream(new StringReader("["+maximum+","+maximum+"]"),Json5ReadOptions.DEFAULT.withStrictJson(strict))) {
            r.beginArray();String first=r.nextString();assertEquals("a@abcd"+String.join("",Collections.nCopies(20,"A")),first);
            assertSame(first,r.nextString());r.endArray();r.requireEnd();
        }
    }
    @Test public void poolIsClearedAndEofThenCloseCannotCorruptAnotherReader()throws Exception {
        Json5StreamReader first=Yomu.stream(new StringReader("{secret:'private\\nvalue'}"));Object value=first.readValue();first.requireEnd();
        Json5StreamReader second=Yomu.stream(new StringReader("[1,2,3]"));second.beginArray();first.close();first.close();assertEquals(1L,second.nextNumber());assertEquals(2L,second.nextNumber());assertEquals(3L,second.nextNumber());second.endArray();second.requireEnd();second.close();
        assertEquals("private\nvalue",((Map<?,?>)value).get("secret"));
        Field spare=StreamBuffers.class.getDeclaredField("SPARE");spare.setAccessible(true);StreamBuffers block=(StreamBuffers)((AtomicReference<?>)spare.get(null)).get();assertNotNull(block);
        for(char c:block.input)assertEquals(0,c);for(char c:block.raw)assertEquals(0,c);for(char c:block.decoded)assertEquals(0,c);
        for(String s:block.keys)assertNull(s);for(String s:block.literals)assertNull(s);
    }
    @Test public void strictModeAndDocumentsNeverShareValidatedText()throws Exception {
        for(int i=0;i<100;i++) {
            assertEquals(Collections.singletonList("a\u000b"),read("['a\\v']",Json5ReadOptions.DEFAULT,8192));
            try{read("['a\\v']",Json5ReadOptions.DEFAULT.withStrictJson(true),8192);fail();}catch(Json5Exception expected){}
            same("[\"different-"+i+"\"]",Json5ReadOptions.DEFAULT.withStrictJson(true),8192);
        }
    }
    @Test public void nestedAndConcurrentReadersHaveIndependentCaches()throws Exception {
        Json5StreamReader outer=Yomu.stream(new StringReader("['outer','outer']"));outer.beginArray();assertEquals("outer",outer.nextString());
        assertEquals(Collections.singletonList("inner"),read("['inner']",Json5ReadOptions.DEFAULT,8192));assertEquals("outer",outer.nextString());outer.endArray();outer.requireEnd();outer.close();
        ExecutorService pool=Executors.newFixedThreadPool(4);
        try{List<Future<?>> jobs=new ArrayList<Future<?>>();for(int i=0;i<100;i++){final int id=i;jobs.add(pool.submit(()->{try{same("{a:'"+id+"',b:'"+id+"'}",Json5ReadOptions.DEFAULT,8192);}catch(IOException e){throw new RuntimeException(e);}}));}for(Future<?> job:jobs)job.get();}finally{pool.shutdownNow();}
    }
    @Test public void positionsAfterCachedTokensAndLineContinuationsRemainAccurate()throws Exception {
        for(String input:new String[]{"['a','a','a']\r\n?","['a\\\r\nb','a\\\r\nb']\n?","[\"a\u2028b\",\"a\u2028b\"] ?"}) {
            Json5Exception a=null,b=null;try{Yomu.parse(input);}catch(Json5Exception e){a=e;}try{read(input,Json5ReadOptions.DEFAULT,8192);}catch(Json5Exception e){b=e;}
            assertNotNull(a);assertNotNull(b);assertEquals(a.getOffset(),b.getOffset());assertEquals(a.getLine(),b.getLine());assertEquals(a.getColumn(),b.getColumn());
        }
    }
    @Test public void finishedInvalidLiteralsDoNotRequestAnotherInputChunk()throws Exception {
        for(String text:new String[]{"\"\\u\"","\"\\u0G\"","\"\\x\"","\"\\xQ\"","\"\\9\"","\"a\nb\""}) {
            Reader input=new StringReader(text) {
                boolean read;
                public int read(char[] b,int off,int len)throws IOException {
                    if(read)throw new IOException("read beyond finished literal");read=true;return super.read(b,off,len);
                }
            };
            try(Json5StreamReader r=Yomu.stream(input)) {
                try{r.nextString();fail(text);}catch(Json5Exception expected){assertTrue(expected.getOffset()>0);}
            }
        }
    }
    @Test public void identifierEofAndLargeOrFragmentedStringsKeepTheFallbackContract()throws Exception {
        for(String text:new String[]{"{","{a:1,","{a:1,b","{true:1,false:2,null:3}","{$x:1,_x2:2,a-b:3}"})
            for(int chunk:new int[]{1,2,7,8192})same(text,Json5ReadOptions.DEFAULT,chunk);
        for(int size:new int[]{255,256,257,8191,8192,8193,16384}) {
            String plain="\""+String.join("",Collections.nCopies(size,"a"))+"\"";
            same(plain,Json5ReadOptions.DEFAULT,1);same(plain,Json5ReadOptions.DEFAULT.withStrictJson(true),8192);
        }
    }
    @Test public void splitIdentifierKeepsCanonicalKeyAndGrammar()throws Exception {
        String prefix="[{text:1},{";
        String padding="/*"+String.join("",Collections.nCopies(8190-prefix.length()-4," "))+"*/";
        try(Json5StreamReader r=Yomu.stream(new StringReader(prefix+padding+"text:2}]"))) {
            r.beginArray();r.beginObject();String name=r.nextName();assertEquals(1L,r.nextNumber());r.endObject();
            r.beginObject();assertSame(name,r.nextName());assertEquals(2L,r.nextNumber());r.endObject();r.endArray();r.requireEnd();
        }
        for(int paddingLength=8185;paddingLength<8200;paddingLength++)for(String key:new String[]{"abc","_123","$a","a-b","a\\u0061","a\u200cz","a𐐀","3key"}) {
            String text="{/*"+String.join("",Collections.nCopies(paddingLength," "))+"*/"+key+":1}";
            same(text,Json5ReadOptions.DEFAULT,8192);same(text,Json5ReadOptions.DEFAULT.withStrictJson(true),8192);
        }
    }
    @Test public void allLiteralCacheSlotsRemainBoundedAndCleared()throws Exception {
        StringBuilder text=new StringBuilder("[");
        for(int i=0;i<32;i++){if(i!=0)text.append(',');text.append('"').append('a').append((char)('0'+i)).append('"');}text.append(']');
        read(text.toString(),Json5ReadOptions.DEFAULT,8192);
        Field field=StreamBuffers.class.getDeclaredField("SPARE");field.setAccessible(true);
        StreamBuffers spare=(StreamBuffers)((AtomicReference<?>)field.get(null)).get();assertNotNull(spare);
        assertEquals(4096,spare.raw.length);for(char c:spare.raw)assertEquals(0,c);
    }
    @Test public void nestingStateRetainsTheFullConfiguredDepth()throws Exception {
        String text=String.join("",Collections.nCopies(512,"["))+"0"+String.join("",Collections.nCopies(512,"]"));
        try(Json5StreamReader r=Yomu.stream(new StringReader(text),Json5ReadOptions.DEFAULT.withMaxDepth(512))){r.skipValue();r.requireEnd();}
        try(Json5StreamReader r=Yomu.stream(new StringReader("["+text+"]"),Json5ReadOptions.DEFAULT.withMaxDepth(512))){
            try{r.skipValue();fail();}catch(Json5Exception expected){assertTrue(expected.getMessage().contains("depth"));}
        }
    }
    @Test public void tokenFastPathDoesNotLoadTheFullStringParser()throws Exception {
        java.net.URL location=Yomu.class.getProtectionDomain().getCodeSource().getLocation();
        try(java.net.URLClassLoader loader=new java.net.URLClassLoader(new java.net.URL[]{location},null) {
            protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {
                if(name.equals("com.sumirelabs.yomu.Parser"))throw new ClassNotFoundException("Full parser loaded on token fast path");
                return super.loadClass(name,resolve);
            }
        }) {
            Class<?> api=Class.forName("com.sumirelabs.yomu.Yomu",true,loader);
            Object value=api.getMethod("parse",Reader.class).invoke(null,new StringReader("{text:'a\\n\\u0041',items:[1,-0,+17,true,false,null]}"));
            assertEquals("a\nA",((Map<?,?>)value).get("text"));
            Class<?> opts=Class.forName("com.sumirelabs.yomu.Json5ReadOptions",true,loader);
            Object strict=opts.getMethod("withStrictJson",boolean.class).invoke(opts.getField("DEFAULT").get(null),true);
            value=api.getMethod("parse",Reader.class,opts).invoke(null,new StringReader("{\"text\":\"a\\n\\u0041\",\"items\":[1,-0,true,false,null]}"),strict);
            assertEquals("a\nA",((Map<?,?>)value).get("text"));
        }
    }
}
