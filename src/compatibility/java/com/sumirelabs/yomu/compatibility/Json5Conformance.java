package com.sumirelabs.yomu.compatibility;

import com.sumirelabs.yomu.Json5Exception;
import com.sumirelabs.yomu.Json5ReadOptions;
import com.sumirelabs.yomu.Json5Writer;
import com.sumirelabs.yomu.Yomu;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Development-only differential runner. Numeric comparison uses IEEE 754 bits. */
public final class Json5Conformance {
    private Json5Conformance() { }
    public static void main(String[] args) throws Exception {
        Map<?,?> corpus=(Map<?,?>)Yomu.parse(Paths.get(args[0]));
        List<?> cases=(List<?>)corpus.get("cases");
        boolean bigDecimals=false,strictJson=false,streaming=false;
        String writerOutput=null;
        for(int i=2;i<args.length;i++) {
            if("--big-decimals".equals(args[i]))bigDecimals=true;
            else if("--streaming".equals(args[i]))streaming=true;
            else if("--strict-json".equals(args[i]))strictJson=true;
            else if("--writer-output".equals(args[i]) && i+1<args.length)writerOutput=args[++i];
            else throw new IllegalArgumentException("Unknown option: "+args[i]);
        }
        Json5ReadOptions options=Json5ReadOptions.DEFAULT.withBigDecimals(bigDecimals).withStrictJson(strictJson);
        Json5Writer jsonWriter=new Json5Writer().withStrictJson(true).withQuoteKeys(false);
        List<Object> writerCases=writerOutput==null ? null : new ArrayList<Object>();
        int skippedNonFinite=0;
        List<Object> differences=new ArrayList<Object>();
        int accepted=0,rejected=0;
        for(Object item:cases) {
            Map<?,?> entry=(Map<?,?>)item;
            String source=(String)entry.get("source");
            boolean valid=(Boolean)entry.get("valid");
            try {
                Object parsed=streaming?Yomu.parse(new java.io.StringReader(source),options):Yomu.parse(source,options);
                Object actual=canonical(parsed);
                if(!valid || !actual.equals(entry.get("expected"))) differences.add(difference(entry,"accepted",actual));
                accepted++;
                if(writerCases!=null && valid) {
                    if(nonFinite(parsed))skippedNonFinite++;
                    else {
                        Map<String,Object> itemCase=new LinkedHashMap<String,Object>();
                        String encoded;
                        if(streaming){java.io.StringWriter output=new java.io.StringWriter();jsonWriter.writeStreaming(parsed,output);encoded=output.toString();}
                        else encoded=jsonWriter.stringify(parsed);
                        itemCase.put("source",encoded);itemCase.put("expected",actual);
                        writerCases.add(itemCase);
                    }
                }
            } catch(Json5Exception error) {
                if(valid) differences.add(difference(entry,"rejected",error.getMessage()));
                rejected++;
            }
        }
        Map<String,Object> report=new LinkedHashMap<String,Object>();
        report.put("reference",corpus.get("reference"));report.put("version",corpus.get("version"));
        report.put("java",System.getProperty("java.version"));report.put("cases",cases.size());
        report.put("bigDecimals",bigDecimals);
        report.put("strictJson",strictJson);
        report.put("streaming",streaming);
        report.put("accepted",accepted);report.put("rejected",rejected);report.put("differences",differences);
        String text=Yomu.stringify(report);
        if(args.length>1)Files.write(Paths.get(args[1]),text.getBytes(StandardCharsets.UTF_8));
        if(writerCases!=null) {
            Map<String,Object> output=new LinkedHashMap<String,Object>();
            output.put("java",System.getProperty("java.version"));output.put("bigDecimals",bigDecimals);
            output.put("skippedNonFinite",skippedNonFinite);output.put("cases",writerCases);
            Files.write(Paths.get(writerOutput),Yomu.stringify(output).getBytes(StandardCharsets.UTF_8));
        }
        System.out.println(corpus.get("reference")+" " + corpus.get("version")+": "+cases.size()+" cases, "+differences.size()+" differences on Java "+System.getProperty("java.version")+", bigDecimals="+bigDecimals+", strictJson="+strictJson);
        if(!differences.isEmpty())System.exit(1);
    }
    private static boolean nonFinite(Object value) {
        if(value instanceof Double || value instanceof Float)return Double.isNaN(((Number)value).doubleValue()) || Double.isInfinite(((Number)value).doubleValue());
        if(value instanceof Map) { for(Object v:((Map<?,?>)value).values())if(nonFinite(v))return true; }
        else if(value instanceof List) {for(Object v:(List<?>)value)if(nonFinite(v))return true;}
        return false;
    }
    private static Map<String,Object> difference(Map<?,?> entry,String result,Object actual) {
        Map<String,Object> value=new LinkedHashMap<String,Object>();
        value.put("source",entry.get("source"));value.put("category",entry.get("category"));
        value.put("result",result);value.put("actual",actual);value.put("expected",entry.get("expected"));
        return value;
    }
    private static Object canonical(Object value) {
        Map<String,Object> result=new LinkedHashMap<String,Object>();
        if(value==null)result.put("kind","null");
        else if(value instanceof Number) {
            result.put("kind","number");double n=((Number)value).doubleValue();
            String bits=Double.isNaN(n)?"NaN":Long.toHexString(Double.doubleToRawLongBits(n));
            if(!Double.isNaN(n))bits="0000000000000000".substring(bits.length())+bits;
            result.put("bits",bits);
        } else if(value instanceof String || value instanceof Boolean) {
            result.put("kind",value instanceof String?"string":"boolean");result.put("value",value);
        } else if(value instanceof List) {
            result.put("kind","array");List<Object> items=new ArrayList<Object>();
            for(Object element:(List<?>)value)items.add(canonical(element));result.put("values",items);
        } else {
            result.put("kind","object");List<Object> entries=new ArrayList<Object>();
            Map<?,?> map=(Map<?,?>)value;
            List<String> keys=new ArrayList<String>();for(Object key:map.keySet())keys.add((String)key);Collections.sort(keys);
            for(String key:keys)entries.add(Arrays.asList(key,canonical(map.get(key))));
            result.put("entries",entries);
        }
        return result;
    }
}
