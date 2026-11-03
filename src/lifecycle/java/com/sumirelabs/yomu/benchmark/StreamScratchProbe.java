package com.sumirelabs.yomu.benchmark;

import com.sumirelabs.yomu.*;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;

/** Measures only the known cleared spare block, never total library residency. */
public final class StreamScratchProbe {
    public static void main(String[] args)throws Exception {
        String example=args.length>1?args[1]:"single";
        String text="{secret:'private\\nvalue'}";
        if(example.equals("repeated")||example.equals("distinct"))text=CurrentBenchmark.input(example,0);
        if(example.equals("full")) {
            StringBuilder all=new StringBuilder("[");
            for(int i=0;i<32;i++){if(i!=0)all.append(',');all.append('"').append('a').append((char)('0'+i)).append('"');}text=all.append(']').toString();
        }
        try(Json5StreamReader r=Yomu.stream(new StringReader(text))){r.skipValue();r.requireEnd();}
        Object spare=GraphMeter.field(Class.forName("com.sumirelabs.yomu.StreamBuffers"),"SPARE");
        Map<String,Object> report=new LinkedHashMap<String,Object>();
        report.put("java",System.getProperty("java.version"));
        report.put("example",example);
        report.put("knownSpareGraphBytes",new GraphMeter().add(spare));
        report.put("scope","one cleared scratch block plus its AtomicReference; not total library residency, peak heap or RSS");
        CurrentBenchmark.save(args[0],report);
    }
}
