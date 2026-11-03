package com.sumirelabs.yomu.mapper;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.Test;
import static org.junit.Assert.*;

public class MapperSessionIsolationTest {
    public static final class Node {
        public int id;
        public Node next;
        public Object payload;
    }

    @Test public void sharedMapperKeepsOrdinaryGraphAndStreamingSessionsIsolated() throws Exception {
        ExecutorService workers=Executors.newFixedThreadPool(4);
        try {
            for(boolean strict:new boolean[]{false,true})for(boolean graph:new boolean[]{false,true}) {
                final boolean useGraph=graph;
                final Json5Mapper mapper=new Json5Mapper().withStrictJson(strict).withObjectGraph(graph).withType("node",Node.class);
                List<Callable<Void>> jobs=new ArrayList<Callable<Void>>();
                for(int task=0;task<8;task++) {
                    final int id=task;
                    jobs.add(new Callable<Void>() {
                        public Void call() throws Exception {
                            for(int iteration=0;iteration<8;iteration++) {
                                Node source=new Node();source.id=id*100+iteration;
                                if(useGraph){source.next=source;source.payload=source;}
                                String text;
                                if((iteration&1)==0)text=mapper.write(source);
                                else {StringBuilder out=new StringBuilder();mapper.writeStreamingTo(out,source);text=out.toString();}
                                Node copy=(iteration&2)==0?mapper.read(text,Node.class):mapper.readStreaming(new StringReader(text),Node.class);
                                assertEquals(source.id,copy.id);
                                if(useGraph){assertSame(copy,copy.next);assertSame(copy,copy.payload);}
                                else {assertNull(copy.next);assertNull(copy.payload);}
                            }
                            return null;
                        }
                    });
                }
                for(Future<Void> result:workers.invokeAll(jobs))result.get();
            }
        } finally {workers.shutdownNow();}
    }
}
