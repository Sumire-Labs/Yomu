package com.sumirelabs.yomu.benchmark;

import com.sumirelabs.yomu.*;
import com.cedarsoftware.io.*;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.util.*;

/** Explicit token methods on all three libraries; no databind/writeObject bridge. */
public final class TokenWriterBenchmark {
    static final class Adapter {
        final String name;final CurrentBenchmark.Adapter settings;
        Adapter(String name){this.name=name;settings=new CurrentBenchmark.Adapter(name);}
        void write(Writer output,Object value)throws Throwable {
            settings.initialize();
            if(name.startsWith("yomu")){Json5StreamWriter writer=settings.writer.stream(output);thermo(writer,value);writer.finish();}
            else if(name.startsWith("jackson")){try(com.fasterxml.jackson.core.JsonGenerator writer=settings.jackson.getFactory().createGenerator(output)){jackson(writer,value);}}
            else if(name.startsWith("jsonIo")){try(JsonGenerator writer=JsonIo.createGenerator(output,settings.ioOptions)){io(writer,value);}}
            else throw new IllegalArgumentException(name);
        }
        long run(Object value)throws Throwable {CurrentBenchmark.CountingWriter out=new CurrentBenchmark.CountingWriter();write(out,value);return out.count;}
    }
    static void thermo(Json5StreamWriter w,Object v)throws IOException {
        if(v instanceof Map){w.beginObject();for(Map.Entry<?,?> e:((Map<?,?>)v).entrySet()){w.name((String)e.getKey());thermo(w,e.getValue());}w.endObject();}
        else if(v instanceof List){w.beginArray();for(Object item:(List<?>)v)thermo(w,item);w.endArray();}
        else if(v instanceof String)w.value((String)v);else if(v==null)w.nullValue();else throw new IllegalArgumentException();
    }
    static void jackson(com.fasterxml.jackson.core.JsonGenerator w,Object v)throws IOException {
        if(v instanceof Map){w.writeStartObject();for(Map.Entry<?,?> e:((Map<?,?>)v).entrySet()){w.writeFieldName((String)e.getKey());jackson(w,e.getValue());}w.writeEndObject();}
        else if(v instanceof List){w.writeStartArray();for(Object item:(List<?>)v)jackson(w,item);w.writeEndArray();}
        else if(v instanceof String)w.writeString((String)v);else if(v==null)w.writeNull();else throw new IllegalArgumentException();
    }
    static void io(JsonGenerator w,Object v)throws IOException {
        if(v instanceof Map){w.writeStartObject();for(Map.Entry<?,?> e:((Map<?,?>)v).entrySet()){w.writeFieldName((String)e.getKey());io(w,e.getValue());}w.writeEndObject();}
        else if(v instanceof List){w.writeStartArray();for(Object item:(List<?>)v)io(w,item);w.writeEndArray();}
        else if(v instanceof String)w.writeString((String)v);else if(v==null)w.writeNull();else throw new IllegalArgumentException();
    }
    public static void main(String[] args)throws Throwable {
        String name=args[0],scenario=args[1],dataset=args[2];boolean changing=scenario.equals("changing");Adapter adapter=new Adapter(name);
        com.sun.management.ThreadMXBean meter=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();meter.setThreadAllocatedMemoryEnabled(true);long thread=Thread.currentThread().getId();meter.getThreadAllocatedBytes(thread);
        long next=1,last=0,end=System.nanoTime()+CurrentBenchmark.WARMUP;Object prepared=null;
        while(System.nanoTime()<end){last=changing?next++:0;prepared=CurrentBenchmark.expected(dataset,last);CurrentBenchmark.sink=adapter.run(prepared);}
        List<Object> samples=new ArrayList<Object>();
        for(int sample=0;sample<CurrentBenchmark.SAMPLES;sample++) {
            long nanos=0,bytes=0;
            for(int done=0;done<CurrentBenchmark.OPS;done+=32) {
                Object[] values=new Object[32];for(int i=0;i<values.length;i++){last=changing?next++:0;values[i]=CurrentBenchmark.expected(dataset,last);}
                long initial=meter.getThreadAllocatedBytes(thread),start=System.nanoTime();for(Object value:values)CurrentBenchmark.sink=adapter.run(value);
                nanos+=System.nanoTime()-start;bytes+=meter.getThreadAllocatedBytes(thread)-initial;prepared=values[31];
            }samples.add(CurrentBenchmark.sample(nanos,bytes,CurrentBenchmark.OPS));
        }
        long graph=new GraphMeter().add(CurrentBenchmark.sink);StringWriter output=new StringWriter();adapter.write(output,prepared);
        if(!CurrentBenchmark.expected(dataset,last).equals(CurrentBenchmark.normalize(Yomu.parse(output.toString()))) || ((Number)CurrentBenchmark.sink).longValue()!=output.toString().length())throw new AssertionError("Token writer mismatch");
        Map<String,Object> report=new LinkedHashMap<String,Object>();report.put("variant",name);report.put("scenario",scenario);report.put("dataset",dataset);report.put("java",System.getProperty("java.version"));
        report.put("samples",samples);report.put("resultGraphBytes",graph);report.put("lastDocumentSerial",last);report.put("inputChars",CurrentBenchmark.input(dataset,last).length());report.put("valueCheck","passed");
        report.put("warmupMillis",CurrentBenchmark.WARMUP/1000000);report.put("resultKind","output character count");CurrentBenchmark.save(args[3],report);
    }
}
