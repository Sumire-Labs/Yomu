package com.sumirelabs.yomu.benchmark;

import com.sumirelabs.yomu.*;
import com.sumirelabs.yomu.mapper.Json5Mapper;
import com.cedarsoftware.io.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import java.io.*;
import java.lang.invoke.*;
import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Fixed-version lifecycle comparison. No production code is modified for measurement. */
public final class CurrentBenchmark {
    static volatile Object sink;
    static final int SAMPLES=Integer.getInteger("bench.samples",5),OPS=Integer.getInteger("bench.operations",2048);
    static final long WARMUP=Long.getLong("bench.warmupMillis",1500L)*1000000L;
    public static class Document { public List<Row> entries; }
    public static class Row { public String text,path,label; }
    static final class CountingWriter extends Writer {
        long count;
        public void write(char[] c,int off,int len){count+=len;}
        public void write(String s){count+=s.length();}
        public void write(String s,int off,int len){count+=len;}
        public void write(int c){count++;}
        public void flush(){}public void close(){}
    }
    static final class Adapter {
        final String name;boolean initialized;
        ObjectMapper jackson;Json5Mapper mapper;Json5Writer writer;WriteOptions ioOptions;
        MethodHandle nativeParser,nativeReader,nativeWriter,nativeStream;
        Adapter(String name){this.name=name;}
        void initialize() throws Throwable {
            if(initialized)return;
            if(name.startsWith("jackson"))jackson=JsonMapper.builder().enable(JsonReadFeature.ALLOW_JAVA_COMMENTS,
                    JsonReadFeature.ALLOW_SINGLE_QUOTES,JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES,JsonReadFeature.ALLOW_TRAILING_COMMA,
                    JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS,JsonReadFeature.ALLOW_LEADING_PLUS_SIGN_FOR_NUMBERS,
                    JsonReadFeature.ALLOW_LEADING_DECIMAL_POINT_FOR_NUMBERS,JsonReadFeature.ALLOW_TRAILING_DECIMAL_POINT_FOR_NUMBERS,
                    JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS,JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER).build();
            if(name.startsWith("yomu"))writer=new Json5Writer();
            if(name.startsWith("mapper"))mapper=new Json5Mapper();
            if(name.startsWith("jsonIo")&&name.contains("Write"))ioOptions=new WriteOptionsBuilder().showTypeInfoNever().cycleSupport(false).build();
            if(name.startsWith("zson")) {
                Class<?> z=Class.forName("dev.nolij.zson.Zson");
                nativeParser=MethodHandles.lookup().unreflect(z.getMethod("parseString",String.class)).asType(MethodType.methodType(Object.class,String.class));
                nativeReader=MethodHandles.lookup().unreflect(z.getMethod("parse",Reader.class)).asType(MethodType.methodType(Object.class,Reader.class));
                if(name.contains("Write")) {
                    Object instance=z.getConstructor().newInstance();
                    z.getMethod("withIndent",String.class).invoke(instance,"");z.getMethod("withExpandArrays",boolean.class).invoke(instance,false);
                    z.getMethod("withQuoteKeys",boolean.class).invoke(instance,true);
                    nativeWriter=MethodHandles.lookup().unreflect(z.getMethod("stringify",Map.class)).bindTo(instance).asType(MethodType.methodType(String.class,Object.class));
                    nativeStream=MethodHandles.lookup().unreflect(z.getMethod("write",Map.class,Appendable.class)).bindTo(instance).asType(MethodType.methodType(void.class,Object.class,Appendable.class));
                }
            }
            if(name.startsWith("cason")) {
                Class<?> p=Class.forName("com.github.cao.awa.cason.serialize.parser.JSONParser");Object companion=p.getField("Companion").get(null);
                nativeParser=MethodHandles.lookup().unreflect(companion.getClass().getMethod("parseObject",String.class)).bindTo(companion).asType(MethodType.methodType(Object.class,String.class));
                if(name.contains("Write")) {
                    Class<?> w=Class.forName("com.github.cao.awa.cason.serialize.writer.JSONWriter"),element=Class.forName("com.github.cao.awa.cason.JSONElement");
                    MethodHandle h=MethodHandles.lookup().unreflect(w.getMethod("stringify",element,boolean.class,String.class)).bindTo(w.getField("INSTANCE").get(null));
                    nativeWriter=MethodHandles.insertArguments(h,1,false,"").asType(MethodType.methodType(String.class,Object.class));
                }
            }
            initialized=true;
        }
        boolean writing(){return name.contains("Write");}
        Object prepare(String dataset,long id) throws Throwable {
            if(!writing())return null;initialize();
            if(name.startsWith("zson")||name.startsWith("cason")){String text=input(dataset,id);return (Object)nativeParser.invokeExact(text);}
            Object value=expected(dataset,id);
            return name.startsWith("mapper")?document(value):value;
        }
        Object run(String text,Object value) throws Throwable {
            if(name.equals("harnessOnly"))return null;
            initialize();
            if(name.equals("yomu"))return Yomu.parse(text);
            if(name.equals("yomuReader"))return Yomu.parse(new StringReader(text));
            if(name.equals("jackson"))return jackson.readValue(text,Object.class);
            if(name.equals("jsonIo"))return JsonIo.toMaps(text).asClass(Map.class);
            if(name.equals("zson"))return (Object)nativeParser.invokeExact(text);
            if(name.equals("zsonReader"))return (Object)nativeReader.invokeExact((Reader)new StringReader(text));
            if(name.equals("cason"))return (Object)nativeParser.invokeExact(text);
            if(name.equals("yomuTokens"))return thermoScan(Yomu.stream(new StringReader(text)));
            if(name.equals("jacksonTokens"))return jacksonScan(jackson.getFactory().createParser(text));
            if(name.equals("jsonIoTokens"))return ioScan(JsonIo.createTokenizer(text));
            if(name.equals("mapperRead"))return mapper.read(text,Document.class);
            if(name.equals("mapperReadStream"))return mapper.readStreaming(new StringReader(text),Document.class);
            if(name.equals("yomuWrite"))return writer.stringify(value);
            if(name.equals("jacksonWrite"))return jackson.writeValueAsString(value);
            if(name.equals("jsonIoWrite"))return JsonIo.toJson(value,ioOptions);
            if(name.equals("zsonWrite")||name.equals("casonWrite"))return (String)nativeWriter.invokeExact(value);
            if(name.equals("mapperWrite"))return mapper.write(value);
            CountingWriter out=new CountingWriter();
            if(name.equals("yomuWriteStream"))writer.writeStreaming(value,out);
            else if(name.equals("jacksonWriteStream"))jackson.writeValue(out,value);
            else if(name.equals("jsonIoWriteStream")){try(JsonGenerator generator=JsonIo.createGenerator(out,ioOptions)){generator.writeObject(value);}}
            else if(name.equals("zsonWriteStream"))nativeStream.invokeExact(value,(Appendable)out);
            else if(name.equals("mapperWriteStream"))mapper.writeStreaming(value,out);
            else throw new IllegalArgumentException(name);
            return out.count;
        }
        String render(Object value) throws Throwable {
            initialize();StringWriter out=new StringWriter();
            if(name.equals("yomuWriteStream"))writer.writeStreaming(value,out);
            else if(name.equals("jacksonWriteStream"))jackson.writeValue(out,value);
            else if(name.equals("jsonIoWriteStream")){try(JsonGenerator generator=JsonIo.createGenerator(out,ioOptions)){generator.writeObject(value);}}
            else if(name.equals("zsonWriteStream"))nativeStream.invokeExact(value,(Appendable)out);
            else if(name.equals("mapperWriteStream"))mapper.writeStreaming(value,out);
            else return (String)run(null,value);
            return out.toString();
        }
    }
    static String input(String dataset,long id){return LifecycleBenchmark.input(dataset,id);}
    static Map<String,Object> expected(String dataset,long id) {
        String serial=Long.toHexString(id);serial="0000000000000000".substring(serial.length())+serial;
        List<Object> rows=new ArrayList<Object>();
        for(int i=0;i<64;i++) {
            String suffix="/"+serial+(dataset.equals("distinct")?"/"+(i<10?"0":"")+i:"");
            Map<String,Object> row=new LinkedHashMap<String,Object>();row.put("text","escaped\nline\tvalueAB"+suffix);row.put("path","assets\\sumire"+suffix);row.put("label","日本語とEnglish"+suffix);rows.add(row);
        }
        return Collections.<String,Object>singletonMap("entries",rows);
    }
    static Document document(Object value) {
        Document doc=new Document();doc.entries=new ArrayList<Row>();
        for(Object element:(List<?>)((Map<?,?>)value).get("entries")){Map<?,?> item=(Map<?,?>)element;Row row=new Row();row.text=(String)item.get("text");row.path=(String)item.get("path");row.label=(String)item.get("label");doc.entries.add(row);}return doc;
    }
    static Object normalize(Object value) throws Exception {
        if(value==null)return null;String type=value.getClass().getName();
        if(type.equals("dev.nolij.zson.ZsonValue"))return normalize(value.getClass().getField("value").get(value));
        if(type.equals("com.github.cao.awa.cason.obj.JSONObject"))return normalize(GraphMeter.field(value,"map"));
        if(type.equals("com.github.cao.awa.cason.array.JSONArray"))return normalize(value.getClass().getMethod("component1").invoke(value));
        if(type.endsWith(".JSONString"))return value.getClass().getMethod("asString").invoke(value);
        if(type.endsWith(".JSONBoolean"))return value.getClass().getMethod("getValue").invoke(value);
        if(type.endsWith(".JSONNull"))return null;
        if(type.endsWith(".JSONNumber"))return ((BigDecimal)value.getClass().getMethod("asBigDecimal").invoke(value)).stripTrailingZeros();
        if(value instanceof Document){Map<String,Object> out=new LinkedHashMap<String,Object>();out.put("entries",normalize(((Document)value).entries));return out;}
        if(value instanceof Row){Row row=(Row)value;Map<String,Object> out=new LinkedHashMap<String,Object>();out.put("text",row.text);out.put("path",row.path);out.put("label",row.label);return out;}
        if(value instanceof Map){Map<Object,Object> out=new LinkedHashMap<Object,Object>();for(Map.Entry<?,?> e:((Map<?,?>)value).entrySet())out.put(e.getKey(),normalize(e.getValue()));return out;}
        if(value instanceof Iterable){List<Object> out=new ArrayList<Object>();for(Object v:(Iterable<?>)value)out.add(normalize(v));return out;}
        if(value.getClass().isArray()){List<Object> out=new ArrayList<Object>();for(int i=0;i<Array.getLength(value);i++)out.add(normalize(Array.get(value,i)));return out;}
        if(value instanceof Number)return new BigDecimal(value.toString()).stripTrailingZeros();return value;
    }
    static long mix(long hash,int tag,Object text){return (hash*31+tag)*31+(text==null?0:text.hashCode());}
    static long digest(Object value,long hash) {
        if(value instanceof Map){hash=mix(hash,1,null);for(Map.Entry<?,?> e:((Map<?,?>)value).entrySet()){hash=mix(hash,5,e.getKey());hash=digest(e.getValue(),hash);}return mix(hash,2,null);}
        if(value instanceof List){hash=mix(hash,3,null);for(Object item:(List<?>)value)hash=digest(item,hash);return mix(hash,4,null);}
        return mix(hash,value instanceof String?6:value instanceof Number?7:value instanceof Boolean?8:9,value);
    }
    static long thermoScan(Json5StreamReader reader)throws IOException {
        long hash=1;try(Json5StreamReader r=reader){while(true){String token=r.peek().name();
            switch(token){case "BEGIN_OBJECT":r.beginObject();hash=mix(hash,1,null);break;case "END_OBJECT":r.endObject();hash=mix(hash,2,null);break;
                case "BEGIN_ARRAY":r.beginArray();hash=mix(hash,3,null);break;case "END_ARRAY":r.endArray();hash=mix(hash,4,null);break;
                case "NAME":hash=mix(hash,5,r.nextName());break;case "STRING":hash=mix(hash,6,r.nextString());break;case "NUMBER":hash=mix(hash,7,r.nextNumber());break;
                case "BOOLEAN":hash=mix(hash,8,r.nextBoolean());break;case "NULL":r.nextNull();hash=mix(hash,9,null);break;case "END_DOCUMENT":r.requireEnd();return hash;default:throw new AssertionError(token);}
        }}
    }
    static long jacksonScan(com.fasterxml.jackson.core.JsonParser parser)throws IOException {
        long hash=1;try(com.fasterxml.jackson.core.JsonParser p=parser){while(p.nextToken()!=null){String token=p.currentToken().name();
            switch(token){case "START_OBJECT":hash=mix(hash,1,null);break;case "END_OBJECT":hash=mix(hash,2,null);break;case "START_ARRAY":hash=mix(hash,3,null);break;case "END_ARRAY":hash=mix(hash,4,null);break;
                case "FIELD_NAME":hash=mix(hash,5,p.currentName());break;case "VALUE_STRING":hash=mix(hash,6,p.getText());break;case "VALUE_NUMBER_INT":case "VALUE_NUMBER_FLOAT":hash=mix(hash,7,p.getNumberValue());break;
                case "VALUE_TRUE":hash=mix(hash,8,true);break;case "VALUE_FALSE":hash=mix(hash,8,false);break;case "VALUE_NULL":hash=mix(hash,9,null);break;default:throw new AssertionError(token);}
        }}return hash;
    }
    static long ioScan(JsonTokenizer tokenizer)throws IOException {
        long hash=1;try(JsonTokenizer p=tokenizer){while(p.nextToken()!=null){String token=p.currentToken().name();
            switch(token){case "START_OBJECT":hash=mix(hash,1,null);break;case "END_OBJECT":hash=mix(hash,2,null);break;case "START_ARRAY":hash=mix(hash,3,null);break;case "END_ARRAY":hash=mix(hash,4,null);break;
                case "FIELD_NAME":hash=mix(hash,5,p.currentName());break;case "VALUE_STRING":hash=mix(hash,6,p.getText());break;case "VALUE_NUMBER_INT":case "VALUE_NUMBER_FLOAT":hash=mix(hash,7,p.getNumberValue());break;
                case "VALUE_TRUE":hash=mix(hash,8,true);break;case "VALUE_FALSE":hash=mix(hash,8,false);break;case "VALUE_NULL":hash=mix(hash,9,null);break;default:throw new AssertionError(token);}
        }}return hash;
    }
    static void validate(Adapter adapter,Object result,String dataset,long id,Object prepared)throws Throwable {
        if(adapter.writing()) {
            String rendered=result instanceof String?(String)result:adapter.render(prepared);
            if(!expected(dataset,id).equals(normalize(Yomu.parse(rendered))))throw new AssertionError("Write mismatch "+adapter.name+": "+rendered.substring(0,Math.min(200,rendered.length())));
            if(!(result instanceof String)&&((Number)result).longValue()!=rendered.length())throw new AssertionError("Write length mismatch");
        }else if(adapter.name.endsWith("Tokens")) {if(((Number)result).longValue()!=digest(expected(dataset,id),1))throw new AssertionError("Token mismatch "+adapter.name);}
        else if(!expected(dataset,id).equals(normalize(result)))throw new AssertionError("Read mismatch "+adapter.name);
    }
    public static void main(String[] args)throws Throwable {
        if(args[0].equals("--check")) {
            Map<String,Object> report=new LinkedHashMap<String,Object>();
            for(String name:variants())try {
                Adapter a=new Adapter(name);
                for(String dataset:new String[]{"repeated","distinct"})for(long id:new long[]{0,1,255,65536}){Object value=a.prepare(dataset,id);validate(a,a.run(input(dataset,id),value),dataset,id,value);}
                report.put(name,"equivalent");
            }catch(Throwable e){report.put(name,e.getClass().getSimpleName()+": "+e.getMessage());}
            save(args[1],report);return;
        }
        String name=args[0],scenario=args[1],dataset=args[2];Adapter adapter=new Adapter(name);
        Adapter harness=new Adapter("harnessOnly");harness.initialize();harness.run(null,null);
        boolean cold=scenario.equals("cold"),changing=scenario.equals("changing");int operations=name.equals("mapperReadStream")?32:OPS,batch=Math.min(32,operations);
        com.sun.management.ThreadMXBean meter=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();meter.setThreadAllocatedMemoryEnabled(true);long thread=Thread.currentThread().getId();meter.getThreadAllocatedBytes(thread);
        String repeated=input(dataset,0);long next=1,last=0;Object prepared=null;
        List<Object> samples=new ArrayList<Object>();
        if(cold) {
            if(adapter.writing())throw new IllegalArgumentException("Cold is defined for read/scan only");
            long bytes=meter.getThreadAllocatedBytes(thread),start=System.nanoTime();sink=adapter.run(repeated,null);long nanos=System.nanoTime()-start;
            samples.add(sample(nanos,meter.getThreadAllocatedBytes(thread)-bytes,1));
        }else {
            long end=System.nanoTime()+WARMUP;
            while(System.nanoTime()<end){last=changing?next++:0;String text=last==0?repeated:input(dataset,last);prepared=adapter.prepare(dataset,last);sink=adapter.run(text,prepared);}
            for(int sample=0;sample<SAMPLES;sample++) {
                long nanos=0,bytes=0;
                for(int done=0;done<operations;done+=batch) {
                    String[] texts=new String[batch];Object[] values=new Object[batch];
                    for(int i=0;i<batch;i++){last=changing?next++:0;texts[i]=last==0?repeated:input(dataset,last);values[i]=adapter.prepare(dataset,last);}
                    long initial=meter.getThreadAllocatedBytes(thread),start=System.nanoTime();for(int i=0;i<batch;i++)sink=adapter.run(texts[i],values[i]);nanos+=System.nanoTime()-start;bytes+=meter.getThreadAllocatedBytes(thread)-initial;prepared=values[batch-1];
                }samples.add(sample(nanos,bytes,operations));
            }
        }
        long graph=new GraphMeter().add(sink);validate(adapter,sink,dataset,last,prepared);
        Map<String,Object> report=new LinkedHashMap<String,Object>();report.put("variant",name);report.put("scenario",scenario);report.put("dataset",dataset);report.put("java",System.getProperty("java.version"));
        report.put("samples",samples);report.put("resultGraphBytes",graph);report.put("lastDocumentSerial",last);report.put("inputChars",input(dataset,last).length());report.put("valueCheck","passed");
        report.put("warmupMillis",cold?0:WARMUP/1000000);report.put("resultKind",adapter.writing()?(name.endsWith("Stream")?"output character count":"complete output String"):name.endsWith("Tokens")?"whole-document digest":"complete result graph");
        save(args[3],report);
    }
    static Map<String,Object> sample(long nanos,long allocated,int operations){Map<String,Object> sample=new LinkedHashMap<String,Object>();sample.put("operations",operations);sample.put("nanos",nanos);sample.put("allocatedBytes",allocated);return sample;}
    static String[] variants(){return new String[]{"yomu","yomuReader","jackson","jsonIo","zson","zsonReader","cason","yomuTokens","jacksonTokens","jsonIoTokens",
            "yomuWrite","yomuWriteStream","jacksonWrite","jacksonWriteStream","jsonIoWrite","jsonIoWriteStream","zsonWrite","zsonWriteStream","casonWrite","mapperRead","mapperReadStream","mapperWrite","mapperWriteStream"};}
    static void save(String path,Object value)throws IOException{Path output=Paths.get(path);if(output.getParent()!=null)Files.createDirectories(output.getParent());Files.write(output,Yomu.stringify(value).getBytes(StandardCharsets.UTF_8));}
}
