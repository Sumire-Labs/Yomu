package com.sumirelabs.yomu;

import java.io.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class StreamingTest {
    private static Reader chunks(String text,int size){return new StringReader(text){public int read(char[] b,int off,int len)throws IOException{return super.read(b,off,Math.min(size,len));}};}
    private static Object parse(String text,Json5ReadOptions options,int chunk)throws IOException{return Yomu.parse(chunks(text,chunk),options);}
    private static void bad(String text,Json5ReadOptions options)throws IOException{try{parse(text,options,1);fail(text);}catch(Json5Exception expected){assertTrue(expected.getLine()>0);}}
    @Test public void pullEventsAndSkippingWorkAcrossSingleCharacterReads()throws Exception {
        Json5StreamReader r=Yomu.stream(chunks("/*a*/{x:[1,{skip:true},3],name:'ok',empty:{},} //done",1));
        assertEquals(Json5StreamReader.Token.BEGIN_OBJECT,r.peek());assertEquals(r.peek(),r.peek());r.beginObject();assertEquals("x",r.nextName());r.beginArray();
        assertEquals(1L,r.nextNumber());r.skipValue();assertEquals(3L,r.nextNumber());assertFalse(r.hasNext());r.endArray();
        assertEquals("name",r.nextName());assertEquals("ok",r.nextString());assertEquals("empty",r.nextName());r.skipValue();r.endObject();r.requireEnd();
        assertEquals(Json5StreamReader.Token.END_DOCUMENT,r.peek());
    }
    @Test public void grammarAndUnicodeMatchStringParserAtChunkBoundaries()throws Exception {
        String text="\ufeff{𐐀:'a\\\r\nb',\\u03b1:'\\x41\\u2028',n:+0xFF,a:[-.5,+Infinity,-NaN,null,true,false,],/* split */z:-0}";
        Object expected=Yomu.parse(text);
        for(int i=1;i<=17;i++)assertEquals(Yomu.stringify(expected),Yomu.stringify(parse(text,Json5ReadOptions.DEFAULT,i)));
        for(String invalid:Arrays.asList("","[1,,2]","{x 1}","{x:1 y:2}","true false","/*unfinished","'\\u00XZ'","[1,","{x:","'line\nbreak'","{a-b:1}"))bad(invalid,Json5ReadOptions.DEFAULT);
    }
    @Test public void strictGrammarAndNumbersRemainIdentical()throws Exception {
        Json5ReadOptions strict=Json5ReadOptions.DEFAULT.withStrictJson(true).withBigDecimals(true);
        Map<?,?> values=(Map<?,?>)parse("{\"v\":0.123456789012345678900,\"a\":[-0,1e400]}",strict,1);
        assertEquals(new BigDecimal("0.123456789012345678900"),values.get("v"));
        assertEquals(Long.MIN_VALUE,Double.doubleToRawLongBits(((Number)((List<?>)values.get("a")).get(0)).doubleValue()));
        assertEquals(new BigDecimal("1e400"),((List<?>)values.get("a")).get(1));
        for(String invalid:Arrays.asList("NaN","Infinity","+1",".1","1.","0x1","'x'","[1,]","{x:1}","/*x*/0","\ufeff0","\"\\x41\"","\"\\v\""))bad(invalid,strict);
        for(String number:Arrays.asList("1e2147483648","1e-2147483647","0.1e2147483649","-0.000","9223372036854775808"))
            assertEquals(Yomu.parse(number,Json5ReadOptions.DEFAULT.withBigDecimals(true)),parse(number,Json5ReadOptions.DEFAULT.withBigDecimals(true),1));
    }
    @Test public void allUtf16CodeUnitsRoundTripWithoutWholeOutputBuilderInWriter()throws Exception {
        char[] chars=new char[65536];for(int i=0;i<chars.length;i++)chars[i]=(char)i;String text=new String(chars);
        StringWriter sink=new StringWriter();new Json5Writer().withStrictJson(true).writeStreaming(text,sink);
        assertEquals(new Json5Writer().withStrictJson(true).stringify(text),sink.toString());
        assertEquals(text,parse(sink.toString(),Json5ReadOptions.DEFAULT.withStrictJson(true),3));
    }
    @Test public void tokenWriterSupportsFormattingAndSafeComments()throws Exception {
        StringWriter out=new StringWriter();Json5StreamWriter w=new Json5Writer().withIndent("  ").withQuoteKeys(false).withExpandArrays(false).stream(out);
        w.beginObject().name("x").beginArray().value(1).comment("*/ injected").value("a").endArray().name("none").nullValue().endObject();w.finish();
        assertEquals(Yomu.parse("{x:[1,'a'],none:null}"),Yomu.parse(out.toString()));assertTrue(out.toString().contains("\n  x:"));
        StringWriter json=new StringWriter();Json5StreamWriter strict=new Json5Writer().withStrictJson(true).stream(json);
        strict.beginArray().comment("hello").value(true).value((Number)2).nullValue().endArray();strict.finish();assertEquals("[true,2,null]",json.toString());
    }
    @Test public void coreWriterMatchesExistingOutputExceptCommentLayout()throws Exception {
        Object value=Yomu.parse("{x:[1,{a:'escape\\n𐐀'},false],none:null}");
        for(boolean strict:Arrays.asList(false,true))for(boolean expanded:Arrays.asList(false,true)) {
            Json5Writer writer=new Json5Writer().withStrictJson(strict).withQuoteKeys(false).withExpandArrays(expanded).withIndent(" ");StringWriter out=new StringWriter();
            writer.writeStreaming(value,out);assertEquals(writer.stringify(value),out.toString());
        }
        StringWriter comments=new StringWriter();new Json5Writer().writeStreaming(Yomu.commented("a */ b",value),comments);assertEquals(value,Yomu.parse(comments.toString()));
    }
    @Test public void streamOwnershipAndPathHelpersAreExplicit()throws Exception {
        class Input extends StringReader {boolean closed;Input(){super("[1]");}public void close(){closed=true;super.close();}}
        class Output extends StringWriter {boolean closed;public void close(){closed=true;}}
        Input input=new Input();try(Json5StreamReader r=Yomu.stream(input)){r.skipValue();r.requireEnd();}assertFalse(input.closed);
        Output output=new Output();try(Json5StreamWriter w=new Json5Writer().stream(output)){w.value("ok");}assertFalse(output.closed);
        Path file=Files.createTempFile("yomu-stream-",".json5");
        try{new Json5Writer().writeStreaming(Arrays.asList(1,2),file);try(Json5StreamReader r=Yomu.stream(file)){assertEquals(Arrays.asList(1L,2L),r.readValue());r.requireEnd();}}finally{Files.delete(file);}
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();new Json5Writer().writeStreaming("𐐀",bytes);assertEquals("𐐀",Yomu.parse(new ByteArrayInputStream(bytes.toByteArray())));
    }
    @Test public void malformedUtf8AndIoErrorsArePropagated()throws Exception {
        try{Yomu.parse(new ByteArrayInputStream(new byte[]{'"',(byte)0xc0,'"'}));fail();}catch(java.nio.charset.CharacterCodingException expected){}
        Reader broken=new Reader(){public int read(char[] b,int off,int len)throws IOException{throw new IOException("read failed");}public void close(){}};
        try{Yomu.parse(broken);fail();}catch(IOException e){assertEquals("read failed",e.getMessage());}
        Appendable brokenOut=new Appendable(){public Appendable append(char c)throws IOException{throw new IOException("write failed");}public Appendable append(CharSequence s)throws IOException{return append('x');}public Appendable append(CharSequence s,int a,int b)throws IOException{return append('x');}};
        try{new Json5Writer().writeStreaming(Arrays.asList(1,2),brokenOut);fail();}catch(IOException e){assertEquals("write failed",e.getMessage());}
    }
    @Test public void stateDepthAndCyclesAreChecked()throws Exception {
        Json5StreamWriter w=new Json5StreamWriter(new StringBuilder());try{w.name("x");fail();}catch(IllegalStateException expected){}
        w.beginObject().name("x");try{w.endObject();fail();}catch(IllegalStateException expected){}w.value(1).endObject();w.finish();
        try{w.value(2);fail();}catch(IllegalStateException expected){}
        List<Object> cycle=new ArrayList<Object>();cycle.add(cycle);try{new Json5Writer().writeStreaming(cycle,new StringWriter());fail();}catch(IllegalArgumentException e){assertTrue(e.getMessage().contains("Cyclic"));}
        String deep="[[[]]]";try{parse(deep,Json5ReadOptions.DEFAULT.withMaxDepth(2),1);fail();}catch(Json5Exception expected){}
        Json5StreamReader r=Yomu.stream(new StringReader("[1]"));r.beginArray();r.nextNumber();try{r.requireEnd();fail();}catch(Json5Exception expected){}r.endArray();r.requireEnd();r.close();try{r.peek();fail();}catch(IllegalStateException expected){}
        StringBuilder untouched=new StringBuilder("prefix");try{new Json5Writer().withStrictJson(true).write(Double.NaN,untouched);fail();}catch(IllegalArgumentException expected){}assertEquals("prefix",untouched.toString());
    }
    @Test public void locationAndDuplicateKeyBehaviorAreMaintained()throws Exception {
        assertEquals(Yomu.parse("{a:1,b:2,a:3}"),parse("{a:1,b:2,a:3}",Json5ReadOptions.DEFAULT,1));
        String source="{\r\n x:'\\u00XZ' }";
        Json5Exception original=null,streamed=null;try{Yomu.parse(source);}catch(Json5Exception e){original=e;}try{parse(source,Json5ReadOptions.DEFAULT,1);}catch(Json5Exception e){streamed=e;}
        assertNotNull(original);assertNotNull(streamed);assertEquals(original.getOffset(),streamed.getOffset());assertEquals(original.getLine(),streamed.getLine());assertEquals(original.getColumn(),streamed.getColumn());
    }
    @Test public void writingAndReadingProgressBeforeTheDocumentEnds()throws Exception {
        final StringBuilder sink=new StringBuilder();Iterable<Integer> values=()->new Iterator<Integer>(){int i;public boolean hasNext(){assertTrue(sink.length()>0);return i<1000;}public Integer next(){return i++;}};
        new Json5Writer().writeStreaming(values,sink);assertEquals(1000,((List<?>)Yomu.parse(sink.toString())).size());
        String input="[0,"+String.join("",Collections.nCopies(20000,"0,"))+"0]";
        Json5StreamReader reader=Yomu.stream(chunks(input,1));reader.beginArray();reader.nextNumber();assertTrue(reader.getOffset()<10);reader.close();
    }
    @Test public void boundedHeapCanProcessMoreThanItsHeapSize()throws Exception {
        String java=new File(System.getProperty("java.home"),"bin/java"+(System.getProperty("os.name").startsWith("Windows")?".exe":"")).getPath();
        Process process=new ProcessBuilder(java,"-Xmx32m","-cp",System.getProperty("java.class.path"),StreamingMemoryProbe.class.getName()).redirectErrorStream(true).start();
        ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;while((count=process.getInputStream().read(buffer))!=-1)output.write(buffer,0,count);
        assertEquals(new String(output.toByteArray(),StandardCharsets.UTF_8),0,process.waitFor());
    }
}
