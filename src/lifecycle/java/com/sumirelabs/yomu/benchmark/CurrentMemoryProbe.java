package com.sumirelabs.yomu.benchmark;

import com.sumirelabs.yomu.*;
import com.cedarsoftware.io.JsonIo;
import java.io.*;
import java.util.*;

/** Generated input, no full source String. This is a bounded-heap outcome test, not a timing ranking. */
public final class CurrentMemoryProbe {
    static final int ROWS=1000000;
    static final String PREFIX="{entries:[",BODY="\"abcdefghijklmnopqrstuvwxyz0000000000\",",SUFFIX="]}";
    static final class Source extends Reader {
        long pos;final long end=PREFIX.length()+(long)BODY.length()*ROWS;
        char character(long at){if(at<PREFIX.length())return PREFIX.charAt((int)at);if(at>=end)return SUFFIX.charAt((int)(at-end));long relative=at-PREFIX.length();int index=(int)(relative%BODY.length());
            if(index>=27&&index<37){long id=relative/BODY.length();for(int i=0;i<36-index;i++)id/=10;return (char)('0'+id%10);}return BODY.charAt(index);}
        public int read(char[] out,int off,int len){if(pos>=end+SUFFIX.length())return -1;int n=(int)Math.min(len,end+SUFFIX.length()-pos);for(int i=0;i<n;i++)out[off+i]=character(pos++);return n;}
        public void close(){}
    }
    static final class Bytes extends InputStream {
        final Source source=new Source();final char[] one=new char[1];
        public int read(){return source.read(one,0,1)<0?-1:one[0];}
        public int read(byte[] out,int off,int len){int n=(int)Math.min(len,source.end+SUFFIX.length()-source.pos);if(n==0)return -1;for(int i=0;i<n;i++)out[off+i]=(byte)source.character(source.pos++);return n;}
    }
    public static void main(String[] args)throws Throwable {
        String variant=args[0];Map<String,Object> report=new LinkedHashMap<String,Object>();report.put("variant",variant);report.put("java",System.getProperty("java.version"));report.put("rows",ROWS);
        report.put("inputAsciiBytes",new Source().end+SUFFIX.length());report.put("heapMaxBytes",Runtime.getRuntime().maxMemory());
        CurrentBenchmark.Adapter a=new CurrentBenchmark.Adapter(variant);String outcome="passed";Object result=null;
        try {
            a.initialize();
            if(variant.equals("yomu"))result=Yomu.parse(new Source());
            else if(variant.equals("jackson"))result=a.jackson.readValue(new Source(),Object.class);
            else if(variant.equals("jsonIo"))result=JsonIo.toMaps(new Bytes()).asClass(Map.class);
            else if(variant.equals("zson"))result=(Object)a.nativeReader.invokeExact((Reader)new Source());
            else if(variant.equals("yomuTokens"))result=CurrentBenchmark.thermoScan(Yomu.stream(new Source()));
            else if(variant.equals("jacksonTokens"))result=CurrentBenchmark.jacksonScan(a.jackson.getFactory().createParser(new Source()));
            else if(variant.equals("jsonIoTokens"))result=CurrentBenchmark.ioScan(JsonIo.createTokenizer(new Bytes()));
            else throw new IllegalArgumentException(variant);
            if(result instanceof Map && ((List<?>)((Map<?,?>)result).get("entries")).size()!=ROWS)throw new AssertionError("Row count");
            if(variant.endsWith("Tokens") && ((Number)result).longValue()!=expectedDigest())throw new AssertionError("Digest mismatch");
            CurrentBenchmark.sink=result;
        }catch(OutOfMemoryError expected){outcome="OutOfMemoryError";result=null;CurrentBenchmark.sink=null;System.gc();}
        report.put("outcome",outcome);report.put("resultKind",variant.endsWith("Tokens")?"digest only":"complete tree");CurrentBenchmark.save(args[1],report);
    }
    static long expectedDigest() {
        long hash=CurrentBenchmark.mix(CurrentBenchmark.mix(CurrentBenchmark.mix(1,1,null),5,"entries"),3,null);
        for(int row=0;row<ROWS;row++) {
            int text="abcdefghijklmnopqrstuvwxyz".hashCode();long divisor=1000000000L;
            for(int digit=0;digit<10;digit++,divisor/=10)text=text*31+'0'+(int)(row/divisor%10);
            hash=(hash*31+6)*31+text;
        }
        return CurrentBenchmark.mix(CurrentBenchmark.mix(hash,4,null),2,null);
    }
}
