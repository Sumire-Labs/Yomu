package com.sumirelabs.yomu;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ApiUsabilityTest {
    private static final class Input extends ByteArrayInputStream {
        boolean closed;
        Input(String text) { super(text.getBytes(StandardCharsets.UTF_8)); }
        public void close() { closed=true; }
    }
    private static final class Text extends StringReader {
        boolean closed;
        Text(String text) { super(text); }
        public void close() { closed=true; super.close(); }
    }
    private static final class Output extends ByteArrayOutputStream {
        boolean closed,flushed;
        public void close() { closed=true; }
        public void flush() { flushed=true; }
        String text() { return new String(toByteArray(),StandardCharsets.UTF_8); }
    }
    @Test public void unifiedReadersKeepModesValuesAndBorrowedOwnership() throws Exception {
        for(boolean strict:new boolean[]{false,true}) {
            Json5ReadOptions options=Json5ReadOptions.builder().strictJson(strict).bigDecimals(true).build();
            String text=strict?"{\"text\":\"すみれ\",\"n\":1.2300}":"/*x*/{text:'すみれ',n:1.2300,}";
            Object expected=Yomu.parse(text,options);
            Text reader=new Text(text);Input input=new Input(text);
            assertEquals(expected,Yomu.read(text,options));
            assertEquals(expected,Yomu.read(reader,options));assertFalse(reader.closed);
            assertEquals(expected,Yomu.read(input,options));assertFalse(input.closed);
            Path file=Files.createTempFile("yomu-api-", ".json5");
            try {
                Files.write(file,text.getBytes(StandardCharsets.UTF_8));
                assertEquals(expected,Yomu.read(file,options));
                for(Json5Object object:Arrays.asList(Yomu.readObject(text,options),Yomu.readObject(new StringReader(text),options),Yomu.readObject(new Input(text),options),Yomu.readObject(file,options))) {
                    assertEquals("すみれ",object.getString("text"));assertEquals(new BigDecimal("1.2300"),object.getBigDecimal("n"));
                    object.with("extra",true);assertEquals(Boolean.TRUE,object.asMap().get("extra"));
                }
                String array="[1,\"すみれ\"]";Files.write(file,array.getBytes(StandardCharsets.UTF_8));
                for(Json5Array value:Arrays.asList(Yomu.readArray(array,options),Yomu.readArray(new StringReader(array),options),Yomu.readArray(new Input(array),options),Yomu.readArray(file,options)))assertEquals("すみれ",value.getString(1));
            } finally { Files.delete(file); }
        }
    }
    @Test public void defaultReaderOverloadsAndRootValidationRemainPredictable() throws Exception {
        String object="{a:1}",array="[1]";Path file=Files.createTempFile("yomu-api-", ".json5");
        try {
            Files.write(file,object.getBytes(StandardCharsets.UTF_8));
            assertEquals(Yomu.parse(object),Yomu.read(object));
            assertEquals(Yomu.read(object),Yomu.read(new StringReader(object)));
            assertEquals(Yomu.read(object),Yomu.read(new Input(object)));
            assertEquals(Yomu.read(object),Yomu.read(file));
            assertEquals(1,Yomu.readObject(object).getInt("a"));
            assertEquals(1,Yomu.readObject(new StringReader(object)).getInt("a"));
            assertEquals(1,Yomu.readObject(new Input(object)).getInt("a"));
            assertEquals(1,Yomu.readObject(file).getInt("a"));
            Files.write(file,array.getBytes(StandardCharsets.UTF_8));
            assertEquals(1,Yomu.readArray(array).getInt(0));
            assertEquals(1,Yomu.readArray(new StringReader(array)).getInt(0));
            assertEquals(1,Yomu.readArray(new Input(array)).getInt(0));
            assertEquals(1,Yomu.readArray(file).getInt(0));
        } finally { Files.delete(file); }
        for(String invalid:new String[]{"null","[]","1"})try{Yomu.readObject(invalid);fail(invalid);}catch(Json5AccessException expected){assertEquals("$",expected.getPath());}
        for(String invalid:new String[]{"null","{}","1"})try{Yomu.readArray(invalid);fail(invalid);}catch(Json5AccessException expected){assertEquals("$",expected.getPath());}
        try{Yomu.read(new StringReader("{} {}"));fail();}catch(Json5Exception expected){assertTrue(expected.getOffset()>0);}
        try{Yomu.read(new ByteArrayInputStream(new byte[]{(byte)0xc0,(byte)0xaf}));fail();}catch(IOException expected){ }
    }
    @Test public void destinationFirstOutputSupportsNullUnicodeAndAllDestinations() throws Exception {
        Object value=Yomu.object(Yomu.entry("text","すみれ"),Yomu.entry("null",null));
        Json5Writer writer=Json5Writer.builder().indent("  ").quoteKeys(false).expandArrays(false).build();
        Path file=Files.createTempFile("yomu-api-", ".json5");
        try {
            StringBuilder chars=new StringBuilder();Output bytes=new Output();
            writer.writeTo(chars,value);writer.writeTo(bytes,value);writer.writeTo(file,value);
            assertEquals(writer.stringify(value),chars.toString());assertEquals(chars.toString(),bytes.text());
            assertEquals(chars.toString(),new String(Files.readAllBytes(file),StandardCharsets.UTF_8));
            assertFalse(bytes.closed);assertFalse(bytes.flushed);
            chars.setLength(0);bytes.reset();Yomu.writeTo(chars,value);Yomu.writeTo(bytes,value);Yomu.writeTo(file,value);
            assertEquals(Yomu.stringify(value),chars.toString());assertEquals(chars.toString(),bytes.text());assertEquals(chars.toString(),new String(Files.readAllBytes(file),StandardCharsets.UTF_8));
            writer.writeTo(file,null);assertEquals("null",new String(Files.readAllBytes(file),StandardCharsets.UTF_8));
            chars.setLength(0);bytes.reset();writer.writeStreamingTo(chars,value);writer.writeStreamingTo(bytes,value);writer.writeStreamingTo(file,value);
            assertEquals(value,Yomu.read(chars.toString()));assertEquals(value,Yomu.read(bytes.text()));assertEquals(value,Yomu.read(file));
            assertFalse(bytes.closed);assertTrue(bytes.flushed);
        } finally { Files.delete(file); }
    }
    @Test public void strictDestinationFirstOutputValidatesBeforeWriting() throws Exception {
        Json5Writer strict=Json5Writer.builder().strictJson(true).build();
        Output bytes=new Output();StringBuilder chars=new StringBuilder("unchanged");
        Path file=Files.createTempFile("yomu-api-", ".json");
        try {
            Files.write(file,"unchanged".getBytes(StandardCharsets.UTF_8));
            for(Object value:new Object[]{Double.NaN,Double.POSITIVE_INFINITY,Arrays.asList(1,Double.NEGATIVE_INFINITY)}) {
                try{strict.writeTo(bytes,value);fail();}catch(IllegalArgumentException expected){assertEquals(0,bytes.size());}
                try{strict.writeTo(chars,value);fail();}catch(IllegalArgumentException expected){assertEquals("unchanged",chars.toString());}
                try{strict.writeTo(file,value);fail();}catch(IllegalArgumentException expected){assertEquals("unchanged",new String(Files.readAllBytes(file),StandardCharsets.UTF_8));}
            }
            assertFalse(bytes.closed);assertFalse(bytes.flushed);
        } finally { Files.delete(file); }
    }
    @Test public void writerBuilderRemembersIgnoredReturnsAndBuildsIndependentSnapshots() {
        Json5Writer.Builder builder=Json5Writer.builder();builder.indent("  ");builder.quoteKeys(false);builder.expandArrays(false);
        Json5Writer first=builder.build();builder.strictJson(true);Json5Writer strict=builder.build();
        Object value=Yomu.object(Yomu.entry("a",Yomu.commented("note",Arrays.asList(1,2))));
        String json5=first.stringify(value);assertTrue(json5.contains("// note"));assertTrue(json5.contains("a:"));assertTrue(json5.contains("\n  "));
        String json=strict.stringify(value);assertFalse(json.contains("//"));assertTrue(json.contains("\"a\""));
        assertEquals(json5,strict.toBuilder().strictJson(false).build().stringify(value));
        Json5Writer original=new Json5Writer();original.withStrictJson(true);assertFalse(original.isStrictJson());
        try{builder.indent("bad");fail();}catch(IllegalArgumentException expected){assertEquals(json,builder.build().stringify(value));}
    }
    @Test public void readOptionsBuilderIsValidatedAndDoesNotMutatePreviousBuilds() {
        Json5ReadOptions.Builder builder=Json5ReadOptions.builder();builder.bigDecimals(true);builder.maxDepth(3);
        Json5ReadOptions first=builder.build();builder.strictJson(true);Json5ReadOptions strict=builder.build();
        assertFalse(first.isStrictJson());assertTrue(strict.isStrictJson());assertTrue(strict.isBigDecimals());assertEquals(3,strict.getMaxDepth());
        Json5ReadOptions copied=strict.toBuilder().bigDecimals(false).build();assertTrue(copied.isStrictJson());assertEquals(3,copied.getMaxDepth());
        assertFalse(Json5ReadOptions.DEFAULT.isBigDecimals());assertFalse(Json5ReadOptions.DEFAULT.isStrictJson());
        try{builder.maxDepth(0);fail();}catch(IllegalArgumentException expected){assertEquals(3,builder.build().getMaxDepth());}
        try{Yomu.read("[[[[0]]]]",first);fail();}catch(Json5Exception expected){ }
        try{Yomu.read("/*x*/0",strict);fail();}catch(Json5Exception expected){ }
    }
}
