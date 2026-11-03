package com.sumirelabs.yomu;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class WriterEscapingTest {
    private static final class PlainWriter extends Writer {
        final StringBuilder text=new StringBuilder();boolean closed;int flushes;
        public void write(char[] chars,int offset,int length){text.append(chars,offset,length);}
        public void flush(){flushes++;}public void close(){closed=true;}
    }
    private static final class EncodedWriter extends OutputStreamWriter {
        int largest;
        EncodedWriter(OutputStream output){super(output,StandardCharsets.UTF_8);}
        public void write(char[] chars,int offset,int length)throws IOException{largest=Math.max(largest,length);super.write(chars,offset,length);}
    }
    private static void same(String value,char quote)throws Exception {
        String expected=Json5Strings.quote(value,quote);
        PlainWriter plain=new PlainWriter();StringEscapes.quoted(value,(Appendable)plain,quote);assertEquals(expected,plain.text.toString());
        StringWriter strings=new StringWriter();StringEscapes.quoted(value,(Appendable)strings,quote);assertEquals(expected,strings.toString());
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();EncodedWriter encoded=new EncodedWriter(bytes);
        StringEscapes.quoted(value,(Appendable)encoded,quote);encoded.flush();
        assertEquals(expected,new String(bytes.toByteArray(),StandardCharsets.UTF_8));assertTrue(encoded.largest<=1024);
        assertEquals(value,Yomu.parse(expected));
        if(quote=='"')assertEquals(value,Yomu.parse(expected,Json5ReadOptions.DEFAULT.withStrictJson(true)));
    }
    @Test public void everyUtf16CodeUnitMatchesExistingQuoteEncoding()throws Exception {
        StringBuilder text=new StringBuilder();for(int c=0;c<=Character.MAX_VALUE;c++)text.append((char)c);
        same(text.toString(),'"');same(text.toString(),'\'');
    }
    @Test public void stagingBoundariesKeepEscapesAndClosingQuotesExact()throws Exception {
        for(int length:new int[]{0,1,2,5,6,7,8,9,31,255,256,1020,1021,1022,1023,1024,1025,2048,8193}) {
            for(String pattern:new String[]{"plain日本語","\n\t\b\f\r\\\"'\u0000\u000b\u2028\u2029\ud800\udfff"}) {
                StringBuilder text=new StringBuilder();while(text.length()<length)text.append(pattern);text.setLength(length);
                same(text.toString(),'"');same(text.toString(),'\'');
            }
        }
    }
    @Test public void longValidatedPrefixesAndEscapesCrossBatches()throws Exception {
        for(int length:new int[]{1,1021,1022,1023,1024,1025,2047,2048,8193}) {
            StringBuilder text=new StringBuilder();while(text.length()<length)text.append("plain日本語");text.setLength(length);
            text.append("\n\"\\\u0000\ud800end");same(text.toString(),'"');same(text.toString(),'\'');
        }
    }
    @Test public void modesFormattingAndCommentsMatchAcrossOutputSinks()throws Exception {
        Map<String,Object> value=new LinkedHashMap<String,Object>();
        value.put("line\n\"key",Arrays.asList("\"\\\n\t日本語\ud800",null,true,42));
        value.put("validKey",new CommentedValue("説明 */ 続き",Arrays.asList("\u2028\u2029",-0.0)));
        for(boolean strict:new boolean[]{false,true})for(boolean quote:new boolean[]{false,true})for(boolean expand:new boolean[]{false,true})for(String indent:new String[]{"","  "}) {
            Json5Writer writer=new Json5Writer().withStrictJson(strict).withQuoteKeys(quote).withExpandArrays(expand).withIndent(indent);
            StringBuilder expected=new StringBuilder();writer.writeStreaming(value,(Appendable)expected);
            PlainWriter plain=new PlainWriter();writer.writeStreaming(value,plain);assertEquals(expected.toString(),plain.text.toString());assertFalse(plain.closed);
            StringWriter target=new StringWriter();BufferedWriter buffered=new BufferedWriter(target,17);writer.writeStreaming(value,buffered);assertEquals(expected.toString(),target.toString());
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();writer.writeStreaming(value,bytes);assertEquals(expected.toString(),new String(bytes.toByteArray(),StandardCharsets.UTF_8));
        }
    }
    @Test public void ioFailuresPropagateAndBorrowedStreamsStayOpen()throws Exception {
        final boolean[] closed={false};OutputStream failing=new OutputStream(){int count;public void write(int value)throws IOException{if(++count>128)throw new IOException("sink failure");}public void close(){closed[0]=true;}};
        char[] chars=new char[10000];Arrays.fill(chars,'\n');String value=new String(chars);
        try{new Json5Writer().writeStreaming(value,failing);fail("IOException expected");}catch(IOException expected){assertEquals("sink failure",expected.getMessage());}
        assertFalse(closed[0]);ByteArrayOutputStream next=new ByteArrayOutputStream();new Json5Writer().writeStreaming(value,next);
        assertEquals(value,Yomu.parse(new String(next.toByteArray(),StandardCharsets.UTF_8)));
        PlainWriter plain=new PlainWriter();new Json5Writer().writeStreaming(value,plain);assertFalse(plain.closed);assertTrue(plain.flushes>0);
    }
    @Test public void concurrentWritesUseIndependentStaging()throws Exception {
        final Json5Writer writer=new Json5Writer().withStrictJson(true);ExecutorService pool=Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> futures=new ArrayList<Future<?>>();
            for(int task=0;task<32;task++){final int id=task;futures.add(pool.submit(new Callable<Void>(){public Void call()throws Exception{String value="task-"+id+"\n日本語\ud800";for(int i=0;i<10;i++){ByteArrayOutputStream out=new ByteArrayOutputStream();writer.writeStreaming(value,out);assertEquals(value,Yomu.parse(new String(out.toByteArray(),StandardCharsets.UTF_8),Json5ReadOptions.DEFAULT.withStrictJson(true)));}return null;}}));}
            for(Future<?> future:futures)future.get();
        }finally{pool.shutdownNow();}
    }
    @Test public void sharedUtf8ScratchKeepsValuesAcrossGrowthAndShrink()throws Exception {
        List<String> values=new ArrayList<String>();int[] lengths={0,1,7,8,31,255,256,383,384,1020,1021,1022,1023,1024,1025,8193};
        for(int pass=0;pass<2;pass++)for(int index=0;index<lengths.length;index++) {
            int length=lengths[pass==0?index:lengths.length-1-index];StringBuilder text=new StringBuilder();
            while(text.length()<length)text.append("A\n\\\"日本\u2028\ud800");text.setLength(length);values.add(text.toString());
        }
        Map<String,Object> document=new LinkedHashMap<String,Object>();document.put("growing\n",values);document.put("tail","");
        Json5Writer writer=new Json5Writer().withStrictJson(true).withIndent("  ");String expected=writer.stringify(document);
        for(int repeat=0;repeat<2;repeat++) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();Json5StreamWriter tokens=writer.stream(bytes);
            tokens.beginObject().name("growing\n").value(values).name("tail").value("").endObject().finish();
            assertEquals(expected,new String(bytes.toByteArray(),StandardCharsets.UTF_8));
        }
    }
}
