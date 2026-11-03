package com.sumirelabs.yomu.benchmark;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Outside timing: serialized lengths and semantic validation for the writer workload. */
public final class CurrentOutputSizes {
    public static void main(String[] args)throws Throwable {
        List<Object> report=new ArrayList<Object>();
        for(String variant:CurrentBenchmark.variants())if(variant.contains("Write"))for(String dataset:new String[]{"repeated","distinct"}) {
            CurrentBenchmark.Adapter adapter=new CurrentBenchmark.Adapter(variant);Object value=adapter.prepare(dataset,0);
            String output=adapter.render(value);CurrentBenchmark.validate(adapter,output,dataset,0,value);
            Map<String,Object> row=new LinkedHashMap<String,Object>();row.put("variant",variant);row.put("dataset",dataset);
            row.put("chars",output.length());row.put("utf8Bytes",output.getBytes(StandardCharsets.UTF_8).length);row.put("valueCheck","passed");report.add(row);
        }
        CurrentBenchmark.save(args[0],report);
    }
}
