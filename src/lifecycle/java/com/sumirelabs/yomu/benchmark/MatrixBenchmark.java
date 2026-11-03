package com.sumirelabs.yomu.benchmark;

import com.sumirelabs.yomu.*;
import com.cedarsoftware.io.*;
import java.io.*;
import java.lang.invoke.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Multi-fixture benchmark only. Production classes and dependencies are unchanged. */
public final class MatrixBenchmark {
    static volatile Object sink;
    static final int SAMPLES=Integer.getInteger("matrix.samples",5);
    static final long WARMUP=Long.getLong("matrix.warmupMillis",2000L)*1000000L;
    static final String[] LIBS={"yomu","jackson","jsonIo","zson"};
    static final String[] OPS={"treeString","treeUtf8","tokens","writeString","writeWriter","pojoRead","pojoWrite","pojoSpoolRead","pojoWriter"};
    static final class Input {final String text;final byte[] bytes;Input(String text,boolean utf8){this.text=text;bytes=utf8?text.getBytes(StandardCharsets.UTF_8):null;}}
    static boolean typed(String op){return op.startsWith("pojo");}
    static boolean writing(String op){return op.startsWith("write")||op.equals("pojoWrite")||op.equals("pojoWriter");}
    static boolean supported(String lib,String op) {
        if(op.equals("tokens"))return !lib.equals("zson");
        return !(op.equals("pojoSpoolRead")||op.equals("pojoWriter"))||lib.equals("yomu");
    }
    static final class Adapter {
        final String lib,op;final CurrentBenchmark.Adapter base;MethodHandle mapToObject,objectToMap;
        Adapter(String lib,String op) {
            this.lib=lib;this.op=op;String name=lib;
            if(typed(op)&&lib.equals("yomu"))name=writing(op)?"mapperWrite":"mapperRead";
            else if(writing(op))name=lib+"Write";
            else if(op.equals("tokens"))name=lib+"Tokens";
            base=new CurrentBenchmark.Adapter(name);
        }
        void initialize()throws Throwable {
            base.initialize();
            if(typed(op)&&lib.equals("zson")&&mapToObject==null) {
                Class<?> z=Class.forName("dev.nolij.zson.Zson");
                mapToObject=MethodHandles.lookup().unreflect(z.getMethod("map2Obj",Map.class,Class.class)).asType(MethodType.methodType(Object.class,Map.class,Class.class));
                objectToMap=MethodHandles.lookup().unreflect(z.getMethod("obj2Map",Object.class)).asType(MethodType.methodType(Map.class,Object.class));
            }
        }
        Object prepare(String fixture,long id)throws Throwable {
            if(!writing(op))return null;initialize();
            if(typed(op))return MatrixFixtures.pojo(fixture,id);
            if(lib.equals("zson"))return (Object)base.nativeParser.invokeExact(MatrixFixtures.source(fixture,id));
            return MatrixFixtures.values(fixture,id);
        }
        @SuppressWarnings({"unchecked","rawtypes"}) Object run(Input input,Object value,Class<?> model)throws Throwable {
            initialize();
            if(op.equals("treeString"))return base.run(input.text,null);
            if(op.equals("treeUtf8")) {
                ByteArrayInputStream stream=new ByteArrayInputStream(input.bytes);
                if(lib.equals("yomu"))return Yomu.parse(stream);
                if(lib.equals("jackson"))return base.jackson.readValue(stream,Object.class);
                if(lib.equals("jsonIo"))return JsonIo.toMaps(stream).asClass(Map.class);
                return (Object)base.nativeReader.invokeExact((Reader)new InputStreamReader(stream,StandardCharsets.UTF_8));
            }
            if(op.equals("tokens"))return scan(lib,input.text,base);
            if(op.equals("pojoRead")||op.equals("pojoSpoolRead")) {
                if(lib.equals("yomu"))return op.equals("pojoSpoolRead")?base.mapper.readStreaming(new StringReader(input.text),model):base.mapper.read(input.text,model);
                if(lib.equals("jackson"))return base.jackson.readValue(input.text,model);
                if(lib.equals("jsonIo"))return JsonIo.toJava(input.text).asClass((Class)model);
                Map map=(Map)(Object)base.nativeParser.invokeExact(input.text);return (Object)mapToObject.invokeExact(map,(Class)model);
            }
            if(op.equals("writeString")||op.equals("pojoWrite")) {
                if(typed(op)&&lib.equals("zson")){Map map=(Map)objectToMap.invokeExact(value);return (String)base.nativeWriter.invokeExact((Object)map);}
                return base.run(null,value);
            }
            CurrentBenchmark.CountingWriter out=new CurrentBenchmark.CountingWriter();write(out,value);return out.count;
        }
        void write(Writer out,Object value)throws Throwable {
            if(op.equals("pojoWriter")){base.mapper.writeStreaming(value,out);return;}
            if(lib.equals("yomu")){base.writer.writeStreaming(value,out);return;}
            if(lib.equals("jackson")){base.jackson.writeValue(out,value);return;}
            if(lib.equals("jsonIo")){try(JsonGenerator generator=JsonIo.createGenerator(out,base.ioOptions)){generator.writeObject(value);}return;}
            base.nativeStream.invokeExact(value,(Appendable)out);
        }
        String render(Object value)throws Throwable {StringWriter out=new StringWriter();write(out,value);return out.toString();}
    }
    static long mix(long h,int tag,Object value){return CurrentBenchmark.mix(h,tag,value);}
    static long number(long h,Number value){long bits=Double.doubleToLongBits(value.doubleValue());return (h*31+7)*31+(int)(bits^(bits>>>32));}
    static long digest(Object value,long h) {
        if(value instanceof Map){h=mix(h,1,null);for(Map.Entry<?,?> e:((Map<?,?>)value).entrySet()){h=mix(h,5,e.getKey());h=digest(e.getValue(),h);}return mix(h,2,null);}
        if(value instanceof List){h=mix(h,3,null);for(Object v:(List<?>)value)h=digest(v,h);return mix(h,4,null);}
        if(value instanceof Number)return number(h,(Number)value);
        return mix(h,value instanceof String?6:value instanceof Boolean?8:9,value);
    }
    static long scan(String lib,String text,CurrentBenchmark.Adapter base)throws Throwable {
        long h=1;
        if(lib.equals("yomu"))try(Json5StreamReader r=Yomu.stream(new StringReader(text))) {
            while(true)switch(r.peek().name()) {
                case "BEGIN_OBJECT":r.beginObject();h=mix(h,1,null);break;case "END_OBJECT":r.endObject();h=mix(h,2,null);break;
                case "BEGIN_ARRAY":r.beginArray();h=mix(h,3,null);break;case "END_ARRAY":r.endArray();h=mix(h,4,null);break;
                case "NAME":h=mix(h,5,r.nextName());break;case "STRING":h=mix(h,6,r.nextString());break;
                case "NUMBER":h=number(h,r.nextNumber());break;case "BOOLEAN":h=mix(h,8,r.nextBoolean());break;case "NULL":r.nextNull();h=mix(h,9,null);break;
                case "END_DOCUMENT":r.requireEnd();return h;default:throw new AssertionError();
            }
        }
        if(lib.equals("jackson"))try(com.fasterxml.jackson.core.JsonParser p=base.jackson.getFactory().createParser(text)) {
            while(p.nextToken()!=null)switch(p.currentToken().name()) {
                case "START_OBJECT":h=mix(h,1,null);break;case "END_OBJECT":h=mix(h,2,null);break;case "START_ARRAY":h=mix(h,3,null);break;case "END_ARRAY":h=mix(h,4,null);break;
                case "FIELD_NAME":h=mix(h,5,p.currentName());break;case "VALUE_STRING":h=mix(h,6,p.getText());break;
                case "VALUE_NUMBER_INT":case "VALUE_NUMBER_FLOAT":h=number(h,p.getNumberValue());break;
                case "VALUE_TRUE":h=mix(h,8,true);break;case "VALUE_FALSE":h=mix(h,8,false);break;case "VALUE_NULL":h=mix(h,9,null);break;default:throw new AssertionError();
            }return h;
        }
        try(JsonTokenizer p=JsonIo.createTokenizer(text)) {
            while(p.nextToken()!=null)switch(p.currentToken().name()) {
                case "START_OBJECT":h=mix(h,1,null);break;case "END_OBJECT":h=mix(h,2,null);break;case "START_ARRAY":h=mix(h,3,null);break;case "END_ARRAY":h=mix(h,4,null);break;
                case "FIELD_NAME":h=mix(h,5,p.currentName());break;case "VALUE_STRING":h=mix(h,6,p.getText());break;
                case "VALUE_NUMBER_INT":case "VALUE_NUMBER_FLOAT":h=number(h,p.getNumberValue());break;
                case "VALUE_TRUE":h=mix(h,8,true);break;case "VALUE_FALSE":h=mix(h,8,false);break;case "VALUE_NULL":h=mix(h,9,null);break;default:throw new AssertionError();
            }return h;
        }
    }
    static Object normalized(Object value)throws Exception {return CurrentBenchmark.normalize(MatrixFixtures.plainPojo(value));}
    static void validate(Adapter a,Object result,String fixture,long id,Object prepared)throws Throwable {
        if(typed(a.op))MatrixFixtures.assertModel(fixture,writing(a.op)?prepared:result);
        Object expected=normalized(MatrixFixtures.values(fixture,id));
        if(writing(a.op)) {
            String text=result instanceof String?(String)result:a.render(prepared);
            if(!expected.equals(normalized(Yomu.parse(text))))throw new AssertionError("Output mismatch");
            if(!(result instanceof String)&&((Number)result).longValue()!=text.length())throw new AssertionError("Output count mismatch");
        }else if(a.op.equals("tokens")) {
            if(((Number)result).longValue()!=digest(MatrixFixtures.values(fixture,id),1))throw new AssertionError("Token digest mismatch");
        }else if(!expected.equals(normalized(result)))throw new AssertionError("Read/model mismatch");
    }
    static List<String> fixtures(String op){List<String> names=new ArrayList<String>();for(String f:MatrixFixtures.NAMES)if(!typed(op)||MatrixFixtures.typed(f))names.add(f);return names;}
    static void check(String path)throws Throwable {
        List<Object> results=new ArrayList<Object>();
        for(String lib:LIBS)for(String op:OPS)for(String fixture:fixtures(op)) {
            Map<String,Object> r=new LinkedHashMap<String,Object>();r.put("library",lib);r.put("operation",op);r.put("fixture",fixture);
            try {
                if(!supported(lib,op))throw new UnsupportedOperationException("No compared API path");
                Adapter a=new Adapter(lib,op);
                for(long id:new long[]{0,1,255}){Input input=new Input(MatrixFixtures.source(fixture,id),op.equals("treeUtf8"));Object prepared=a.prepare(fixture,id);validate(a,a.run(input,prepared,MatrixFixtures.model(fixture)),fixture,id,prepared);}
                r.put("status","equivalent");
            }catch(Throwable e){r.put("status",e.getClass().getSimpleName());r.put("detail",String.valueOf(e.getMessage()));}
            results.add(r);
        }
        CurrentBenchmark.save(path,results);
    }
    public static void main(String[] args)throws Throwable {
        if(args[0].equals("check")){check(args[1]);return;}
        String lib=args[1],op=args[2];int fork=Integer.parseInt(args[3]);Adapter a=new Adapter(lib,op);
        List<String> fixtures=new ArrayList<String>(Arrays.asList(args[4].split(",")));Collections.rotate(fixtures,fork%fixtures.size());
        com.sun.management.ThreadMXBean meter=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();meter.setThreadAllocatedMemoryEnabled(true);long thread=Thread.currentThread().getId();meter.getThreadAllocatedBytes(thread);
        List<Object> reports=new ArrayList<Object>();
        if(args[0].equals("cold")) {
            String fixture=fixtures.get(0);Input input=new Input(MatrixFixtures.source(fixture,0),op.equals("treeUtf8"));
            long bytes=meter.getThreadAllocatedBytes(thread),start=System.nanoTime();sink=a.run(input,null,MatrixFixtures.model(fixture));long time=System.nanoTime()-start;bytes=meter.getThreadAllocatedBytes(thread)-bytes;
            validate(a,sink,fixture,0,null);reports.add(record(lib,op,fixture,"cold",fork,0,input,Collections.<Object>singletonList(CurrentBenchmark.sample(time,bytes,1)),new GraphMeter().add(sink)));
        }else {
            Map<String,Input> repeated=new LinkedHashMap<String,Input>();for(String f:fixtures)repeated.put(f,new Input(MatrixFixtures.source(f,0),op.equals("treeUtf8")));
            long end=System.nanoTime()+WARMUP,serial=1;
            do{for(String f:fixtures){Object value=a.prepare(f,serial);sink=a.run(repeated.get(f),value,MatrixFixtures.model(f));}serial++;}while(System.nanoTime()<end);
            for(String fixture:fixtures)for(String scenario:fork%2==0?new String[]{"repeat","changing"}:new String[]{"changing","repeat"}) {
                serial=1; // Identical measured document sequence across libraries, independent of warmup speed.
                int operations=op.equals("pojoSpoolRead")?16:MatrixFixtures.operations(fixture),batch=Math.min(32,operations);long last=0;Object prepared=null;Input lastInput=null;List<Object> samples=new ArrayList<Object>();
                for(int sample=0;sample<SAMPLES;sample++) {
                    long nanos=0,bytes=0;
                    for(int done=0;done<operations;done+=batch) {
                        Input[] inputs=new Input[batch];Object[] values=new Object[batch];
                        for(int i=0;i<batch;i++){last=scenario.equals("changing")?serial++:0;inputs[i]=last==0?repeated.get(fixture):new Input(MatrixFixtures.source(fixture,last),op.equals("treeUtf8"));values[i]=a.prepare(fixture,last);}
                        long startBytes=meter.getThreadAllocatedBytes(thread),start=System.nanoTime();for(int i=0;i<batch;i++)sink=a.run(inputs[i],values[i],MatrixFixtures.model(fixture));nanos+=System.nanoTime()-start;bytes+=meter.getThreadAllocatedBytes(thread)-startBytes;prepared=values[batch-1];lastInput=inputs[batch-1];
                    }samples.add(CurrentBenchmark.sample(nanos,bytes,operations));
                }
                validate(a,sink,fixture,last,prepared);reports.add(record(lib,op,fixture,scenario,fork,last,lastInput,samples,new GraphMeter().add(sink)));
            }
        }
        CurrentBenchmark.save(args[5],reports);
    }
    static Map<String,Object> record(String lib,String op,String fixture,String scenario,int fork,long serial,Input input,List<Object> samples,long graph) {
        Map<String,Object> r=new LinkedHashMap<String,Object>();r.put("library",lib);r.put("operation",op);r.put("fixture",fixture);r.put("scenario",scenario);r.put("fork",fork);r.put("java",System.getProperty("java.version"));r.put("inputChars",input.text.length());r.put("inputUtf8Bytes",input.text.getBytes(StandardCharsets.UTF_8).length);r.put("lastSerial",serial);r.put("samples",samples);r.put("resultGraphBytes",graph);r.put("valueCheck","passed");r.put("warmupMillis",scenario.equals("cold")?0:WARMUP/1000000);return r;
    }
}
