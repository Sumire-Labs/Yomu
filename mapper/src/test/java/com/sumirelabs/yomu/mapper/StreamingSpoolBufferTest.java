package com.sumirelabs.yomu.mapper;

import com.sumirelabs.yomu.Json5ReadOptions;
import com.sumirelabs.yomu.Yomu;
import java.io.StringReader;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class StreamingSpoolBufferTest {
    @Test public void nestedHeadersAcrossBufferEdgesPreserveValuesAndDuplicateKeyOrder() throws Exception {
        for(int length:new int[]{0,1,4068,4077,4082,4090,4096,8192,32768})for(boolean strict:new boolean[]{false,true}) {
            char[] chars=new char[length];Arrays.fill(chars,'x');
            String text="{\"prefix\":\""+new String(chars)+"\",\"empty\":{},\"items\":[[],{\"a\":1,\"b\":2,\"a\":3},[true,null,\"日本語\\n\"]],\"exact\":1e2147483648,\"zero\":-0}";
            Json5ReadOptions options=Json5ReadOptions.DEFAULT.withStrictJson(strict).withBigDecimals(true);
            try(StreamingValues values=StreamingValues.spool(Yomu.stream(new StringReader(text),options),true)) {
                Map<?,?> root=(Map<?,?>)values.root();List<?> items=(List<?>)root.get("items");
                assertEquals(Arrays.asList(true,null,"日本語\n"),StreamingValues.materialize(items.get(2)));
                Map<?,?> duplicate=(Map<?,?>)items.get(1);
                assertEquals(Arrays.asList("a","b"),new ArrayList<Object>(duplicate.keySet()));
                assertEquals(3L,duplicate.get("a"));assertEquals(Collections.emptyList(),items.get(0));
                assertEquals(Yomu.parse(text,options),StreamingValues.materialize(root));
            }
        }
    }
}
