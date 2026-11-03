package com.sumirelabs.yomu.mapper;

import com.sumirelabs.yomu.CommentedValue;
import com.sumirelabs.yomu.Yomu;
import java.lang.reflect.*;
import java.util.*;

import static com.sumirelabs.yomu.mapper.MappingTypes.*;
import static com.sumirelabs.yomu.mapper.MappingValues.*;

/** Per-operation object identity, graph envelopes, and graph safety checks. */
final class MappingGraph {
    private final MappingSession session;

    MappingGraph(MappingSession session) { this.session=session; }

    final IdentityHashMap<Object,Integer> ids=new IdentityHashMap<Object,Integer>();
    final List<Object> nodes=new ArrayList<Object>();
    final List<Codec<?>> adapters=new ArrayList<Codec<?>>();
    final IdentityHashMap<Object,Integer> payloadIds=new IdentityHashMap<Object,Integer>();
    Object[] objects;
    Type[] types;
    Codec<?>[] readers;
    byte[] states;

    Map<String,Object> reference(int id) {
        Map<String,Object> ref=new LinkedHashMap<String,Object>(2);ref.put("$ref",id);
        if(session.observer!=null)session.observer.onReference(ref,id);
        return ref;
    }
    Map<String,Object> envelope(Object root) {
        Map<String,Object> out=new LinkedHashMap<String,Object>(4);
        out.put("$thermometer","graph/1");out.put("root",root);out.put("nodes",nodes);return out;
    }
    boolean scalar(Object value) {
        return value==null || value instanceof String || value instanceof Boolean || value instanceof Number
                || value instanceof Character || value instanceof Enum;
    }
    Object write(Object value,Type target,Map<TypeVariable<?>,Type> variables,String path,int depth,Codec<?> adapter) {
        if(value instanceof CommentedValue) {
            CommentedValue cv=(CommentedValue)value;
            session.enter(value,path,depth);
            try{return new CommentedValue(cv.getComment(),write(cv.getValue(),target,variables,path,depth+1,adapter));}
            finally{session.active.remove(value);}
        }
        RegisteredTypes.Entry registered=adapter==null?RegisteredTypeMapping.outputType(session.mapper,value,target,variables,path):null;
        if(registered!=null)target=registered.type;
        if(value!=null) {
            target=writeType(target,variables,value.getClass(),path);
            if(!boxed(raw(target,variables,path)).isInstance(value))throw error(path,"Value does not match declared type "+target);
            if(adapter==null)adapter=session.mapper.writeCodec(target,value.getClass(),variables,path);
        }
        if(value==null || scalar(value)&&adapter==null&&registered==null)return session.writeBody(value,target,variables,path,depth);
        if(adapter==null && value instanceof IdentityHashMap)throw error(path,"Object graphs do not support IdentityHashMap");
        Integer previous=ids.get(value);
        if(previous!=null) {
            if(adapters.get(previous)!=adapter)throw error(path,"Shared object uses incompatible codecs");
            if(registered!=null) {
                @SuppressWarnings("unchecked") Map<String,Object> node=(Map<String,Object>)nodes.get(previous);
                node.put("type",registered.id);
            }
            return reference(previous);
        }
        if(depth>=Yomu.DEFAULT_MAX_DEPTH)throw error(path,"Maximum mapping depth exceeded");
        int id=nodes.size();ids.put(value,id);adapters.add(adapter);
        Map<String,Object> node=new LinkedHashMap<String,Object>(4);nodes.add(node);
        String kind=adapter!=null?"codec":scalar(value)?"value":value instanceof Map?(session.mapper.mapEncoding==MapEncoding.ENTRY_ARRAY?"entries":"map")
                :value.getClass().isArray()?"array":value instanceof Collection?"list":"object";
        if(adapter==null && value instanceof Iterable && !(value instanceof Collection))throw error(path,"Graph iterables must implement Collection");
        node.put("kind",kind);
        if(registered!=null)node.put("type",registered.id);
        if(session.streamingOutput) {
            if(session.pendingNodes==null)session.pendingNodes=new ArrayList<MappingSession.StreamNode>();
            session.pendingNodes.add(session.new StreamNode(value,target,path,depth,adapter));
        }
        else node.put("value",adapter==null?session.writeBody(value,target,variables,path,depth):session.encodeBody(adapter,value,target,variables,path,depth));
        return reference(id);
    }
    Object open(Object value) {
        if(!(value instanceof Map))throw error("$","Expected object graph envelope");
        Map<?,?> envelope=(Map<?,?>)value;
        if(envelope.size()!=3 || !"graph/1".equals(envelope.get("$thermometer")) || !envelope.containsKey("root") || !(envelope.get("nodes") instanceof List))
            throw error("$","Invalid object graph envelope or version");
        nodes.addAll((List<?>)envelope.get("nodes"));
        objects=new Object[nodes.size()];types=new Type[nodes.size()];readers=new Codec<?>[nodes.size()];states=new byte[nodes.size()];
        for(int i=0;i<nodes.size();i++) {
            String path=index("$[\"nodes\"]",i);
            if(!(nodes.get(i) instanceof Map))throw error(path,"Expected graph node");
            Map<?,?> node=(Map<?,?>)nodes.get(i);Object kind=node.get("kind"),payload=node.get("value");
            if((node.size()!=2 && !(node.size()==3 && node.containsKey("type"))) || !node.containsKey("value") || !(kind instanceof String))throw error(path,"Invalid graph node");
            if(node.containsKey("type"))RegisteredTypeMapping.registeredType(session.mapper,node.get("type"),Object.class,Collections.<TypeVariable<?>,Type>emptyMap(),path);
            if("object".equals(kind)||"map".equals(kind)) {
                if(!(payload instanceof Map))throw error(path,"Expected object node payload");
                for(Map.Entry<?,?> e:((Map<?,?>)payload).entrySet()) {
                    if(!(e.getKey() instanceof String))throw error(path,"Expected string property name");
                    checkValue(e.getValue(),path);
                }
            } else if("list".equals(kind)||"array".equals(kind)||"entries".equals(kind)) {
                if(!(payload instanceof List))throw error(path,"Expected array node payload");
                for(Object item:(List<?>)payload) {
                    if("entries".equals(kind)) {
                        if(!(item instanceof List)||((List<?>)item).size()!=2)throw error(path,"Expected graph map entry pair");
                        for(Object part:(List<?>)item)checkValue(part,path);
                    } else checkValue(item,path);
                }
            } else if("value".equals(kind)) {
                if(!node.containsKey("type") || payload!=null && !(payload instanceof String) && !(payload instanceof Boolean) && !(payload instanceof Number))
                    throw error(path,"Expected registered scalar node payload");
            } else if("codec".equals(kind)) {
                session.validateEncoded(payload,path,0);
            } else throw error(path,"Unknown graph node kind");
            // Payload objects are unique shells, including when convert() receives a caller-owned tree.
            if(payload instanceof Map || payload instanceof List) {
                StreamingValues.pin(payload);
                if(payloadIds.put(payload,i)!=null)throw error(path,"Graph node payloads must be distinct");
            }
        }
        checkValue(envelope.get("root"),"$[\"root\"]");return envelope.get("root");
    }
    int refId(Object value,String path) {
        value=uncomment(value);
        if(!(value instanceof Map))throw error(path,"Expected graph reference");
        Map<?,?> ref=(Map<?,?>)value;
        if(ref.size()!=1 || !(ref.get("$ref") instanceof Number))throw error(path,"Invalid graph reference");
        int id=(Integer)number((Number)ref.get("$ref"),Integer.class,path);
        if(id<0||id>=nodes.size())throw error(path,"Dangling graph reference");return id;
    }
    void checkValue(Object value,String path) {
        value=uncomment(value);
        if(value!=null && !(value instanceof String) && !(value instanceof Boolean) && !(value instanceof Number))refId(value,path);
    }
    Object uncomment(Object value) {
        int depth=0;
        while(value instanceof CommentedValue) {
            if(depth++>=Yomu.DEFAULT_MAX_DEPTH)throw error("$","Maximum comment depth exceeded");
            value=((CommentedValue)value).getValue();
        }
        return value;
    }
    void bind(Object payload,Object result) {
        Integer id=payloadIds.get(payload);
        if(id!=null && states[id]==1 && !"codec".equals(((Map<?,?>)nodes.get(id)).get("kind")))objects[id]=result;
    }
    Object read(Object value,Type target,Map<TypeVariable<?>,Type> variables,String path,int depth,Codec<?> adapter) {
        value=uncomment(value);
        target=resolve(target,variables,path);Class<?> raw=raw(target,variables,path);
        if(!(value instanceof Map)) {
            if(adapter==null)adapter=session.mapper.readCodec(target,raw,variables,path);
            checkValue(value,path);
            return value==null || adapter==null?session.readBody(value,target,variables,path,depth):session.decode(adapter,value,target,raw,variables,path,depth);
        }
        int id=refId(value,path);
        Map<?,?> node=(Map<?,?>)nodes.get(id);
        if(node.containsKey("type")) {
            RegisteredTypes.Entry entry=RegisteredTypeMapping.registeredType(session.mapper,node.get("type"),target,variables,path);
            target=entry.type;raw=entry.raw;
        }
        if(adapter==null)adapter=node.containsKey("type")?session.mapper.writeCodec(target,raw,variables,path):session.mapper.readCodec(target,raw,variables,path);
        if(states[id]!=0) {
            if(objects[id]==null && states[id]==1)throw error(path,"Cycle through creator, record or codec cannot be constructed");
            if((!(raw==Object.class && adapter==null) && readers[id]!=adapter)
                    || (objects[id]!=null && !boxed(raw).isInstance(objects[id]))
                    || (raw!=Object.class && !types[id].equals(TypeKeys.of(substitute(target,variables,path)))))
                throw error(path,"Graph reference has incompatible target type or codec");
            return objects[id];
        }
        if(depth>=Yomu.DEFAULT_MAX_DEPTH)throw error(path,"Maximum mapping depth exceeded");
        String kind=(String)node.get("kind");Object payload=node.get("value");
        if("codec".equals(kind)!=(adapter!=null))throw error(path,"Graph codec node requires its original codec");
        if(adapter==null) {
            if(raw==Object.class) {
                target="array".equals(kind)?Object[].class:"list".equals(kind)?List.class:Map.class;
                raw=raw(target,variables,path);
            }
            boolean matches="value".equals(kind)?(numeric(raw)||raw==String.class||raw==Boolean.class||raw==Character.class||raw.isEnum())
                    :"array".equals(kind)?raw.isArray():"list".equals(kind)?Collection.class.isAssignableFrom(raw)
                    :"map".equals(kind)?Map.class.isAssignableFrom(raw)&&session.mapper.mapEncoding==MapEncoding.OBJECT
                    :"entries".equals(kind)?Map.class.isAssignableFrom(raw)&&session.mapper.mapEncoding==MapEncoding.ENTRY_ARRAY
                    :!raw.isArray()&&!Collection.class.isAssignableFrom(raw)&&!numeric(raw)&&raw!=String.class&&raw!=Boolean.class;
            if(!matches)throw error(path,"Graph node kind does not match target type or Map encoding");
        }
        states[id]=1;types[id]=TypeKeys.of(substitute(target,variables,path));readers[id]=adapter;
        Object result=adapter==null?session.readBody(payload,target,variables,path,depth):session.decode(adapter,payload,target,raw,variables,path,depth);
        objects[id]=result;states[id]=2;return result;
    }
    // Hash/sorted containers cannot safely hold keys whose graph is still being populated or cyclic.
    void checkKey(Object wire,String path) { checkKey(wire,path,new HashSet<Integer>(),new HashSet<Integer>()); }
    void checkKey(Object wire,String path,Set<Integer> visiting,Set<Integer> checked) {
        wire=uncomment(wire);
        if(!(wire instanceof Map))return;
        int id=refId(wire,path);
        if(checked.contains(id))return;
        if(visiting.size()>=Yomu.DEFAULT_MAX_DEPTH)throw error(path,"Maximum graph key depth exceeded");
        if(states[id]==1 || !visiting.add(id))throw error(path,"Cyclic or incomplete graph key/set element is unsupported");
        Map<?,?> node=(Map<?,?>)nodes.get(id);Object payload=node.get("value");
        if(!"codec".equals(node.get("kind")) && !"value".equals(node.get("kind"))) {
            Iterable<?> children=payload instanceof Map?((Map<?,?>)payload).values():(List<?>)payload;
            for(Object item:children) {
                if("entries".equals(node.get("kind")))for(Object part:(List<?>)item)checkKey(part,path,visiting,checked);
                else checkKey(item,path,visiting,checked);
            }
        }
        visiting.remove(id);
        checked.add(id);
    }

}
