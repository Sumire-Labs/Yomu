package com.sumirelabs.yomu.mapper;

import com.sumirelabs.yomu.Json5StreamReader;
import com.sumirelabs.yomu.Json5StreamWriter;
import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;

/** Incremental input ownership/spooling and direct graph output orchestration. */
final class StreamingMapping {
    private StreamingMapping() { }

    static <T> T read(Json5Mapper mapper,Json5StreamReader input,Type type,boolean requireEnd) throws IOException {
        Objects.requireNonNull(input,"input");
        if(input.getReadOptions().isStrictJson()!=mapper.readOptions.isStrictJson() || input.getReadOptions().isBigDecimals()!=mapper.readOptions.isBigDecimals())
            throw new IllegalArgumentException("Stream reader syntax and numeric modes must match mapper");
        if(requireEnd)try(Json5StreamReader complete=input){return readSpool(mapper,complete,type,true);}
        return readSpool(mapper,input,type,false);
    }

    @SuppressWarnings("unchecked")
    private static <T> T readSpool(Json5Mapper mapper,Json5StreamReader input,Type type,boolean requireEnd) throws IOException {
        try(StreamingValues values=StreamingValues.spool(input,requireEnd)) {return (T)mapper.readRoot(values.root(),type);}
        catch(RuntimeException e) {
            for(Throwable cause=e;cause!=null;cause=cause.getCause())if(cause instanceof StreamingValues.Failure)throw (IOException)cause.getCause();
            throw e;
        }
    }

    static void write(Json5Mapper mapper,Json5StreamWriter output,Object value,Type type) throws IOException {
        Objects.requireNonNull(output,"output");
        if(output.isStrictJson()!=mapper.writer.isStrictJson())throw new IllegalArgumentException("Stream writer strict JSON mode must match mapper");
        MappingSession conversion=new MappingSession(mapper);conversion.streamingOutput=true;
        Map<TypeVariable<?>,Type> variables=Collections.emptyMap();
        if(!mapper.objectGraph){conversion.streamWrite(output,value,type,variables,"$",0,null);return;}
        Object root=conversion.write(value,type,variables,"$",0);
        output.beginObject().name("$thermometer").value("graph/1").name("root").value(root).name("nodes").beginArray();
        for(int i=0;i<conversion.graph.nodes.size();i++) {
            Map<?,?> node=(Map<?,?>)conversion.graph.nodes.get(i);MappingSession.StreamNode pending=conversion.pendingNodes.get(i);
            output.beginObject().name("kind").value((String)node.get("kind"));
            if(node.containsKey("type"))output.name("type").value((String)node.get("type"));
            output.name("value");conversion.streamBody(output,pending.value,pending.type,variables,pending.path,pending.depth,pending.codec,true);output.endObject();
        }
        output.endArray().endObject();
    }
}
