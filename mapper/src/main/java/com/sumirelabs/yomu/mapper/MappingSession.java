package com.sumirelabs.yomu.mapper;

import com.sumirelabs.yomu.CommentedValue;
import com.sumirelabs.yomu.Yomu;
import com.sumirelabs.yomu.Json5StreamWriter;
import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;

import static com.sumirelabs.yomu.mapper.MappingTypes.*;
import static com.sumirelabs.yomu.mapper.MappingValues.*;

/** One read/write operation: ordinary value conversion and codec recursion state. */
final class MappingSession {
    final Json5Mapper mapper;
    final MappingObserver observer;

    MappingSession(Json5Mapper mapper) {
        this(mapper,null);
    }
    MappingSession(Json5Mapper mapper,MappingObserver observer) {
        this.mapper=mapper;
        this.observer=observer;
        this.graph=mapper.objectGraph?new MappingGraph(this):null;
    }

    final IdentityHashMap<Object, Boolean> active = new IdentityHashMap<Object, Boolean>();
    List<DecodeFrame> codecReads;
    final MappingGraph graph;
    boolean streamingOutput;
    List<StreamNode> pendingNodes;
    final class StreamNode {
        final Object value;final Type type;final String path;final int depth;final Codec<?> codec;
        StreamNode(Object value,Type type,String path,int depth,Codec<?> codec){this.value=value;this.type=type;this.path=path;this.depth=depth;this.codec=codec;}
    }

    void enter(Object value, String path, int depth) {
        if (depth >= Yomu.DEFAULT_MAX_DEPTH) throw error(path, "Maximum mapping depth exceeded");
        if (active.put(value, Boolean.TRUE) != null) throw error(path, "Cyclic value graph");
    }

    Object read(Object value, Type target, Map<TypeVariable<?>, Type> variables, String path, int depth) {
        if(graph!=null)return graph.read(value,target,variables,path,depth,null);
        if(target==String.class && value instanceof String && mapper.codecs.isEmpty())return value;
        if(!mapper.registeredTypes.byId.isEmpty()) {
            value=uncomment(value,path);
            String marker=typeMarker(value);
            if(marker!=null) {
                if(depth+1>=Yomu.DEFAULT_MAX_DEPTH)throw error(path,"Maximum polymorphic depth exceeded");
                Map<?,?> wrapper=(Map<?,?>)value;
                if("type/1".equals(marker)) {
                    if(wrapper.size()!=3 || !wrapper.containsKey("value"))throw error(path,"Invalid registered type envelope");
                    RegisteredTypes.Entry entry=RegisteredTypeMapping.registeredType(mapper,wrapper.get("type"),target,variables,path);
                    Codec<?> codec=mapper.writeCodec(entry.type,entry.raw,variables,path);
                    return codec==null?readBody(wrapper.get("value"),entry.type,variables,path,depth+1)
                            :decode(codec,wrapper.get("value"),entry.type,entry.raw,variables,path,depth+1);
                }
                if(!"literal/1".equals(marker) || wrapper.size()!=2 || !wrapper.containsKey("value"))throw error(path,"Invalid type envelope or version");
                return readBody(wrapper.get("value"),target,variables,path,depth+1);
            }
        }
        return readBody(value,target,variables,path,depth);
    }
    private Object uncomment(Object value,String path) {
        int depth=0;
        while(value instanceof CommentedValue) {
            if(depth++>=Yomu.DEFAULT_MAX_DEPTH)throw error(path,"Maximum comment depth exceeded");
            value=((CommentedValue)value).getValue();
        }
        return value;
    }
    private String typeMarker(Object value) {
        if(!(value instanceof Map))return null;
        Object marker=uncomment(((Map<?,?>)value).get("$thermometer"),"$");
        return marker instanceof String && (((String)marker).startsWith("type/") || ((String)marker).startsWith("literal/"))?(String)marker:null;
    }
    Map<String,Object> escapeLiteral(Map<String,Object> value) {
        if(mapper.registeredTypes.byId.isEmpty() || typeMarker(value)==null)return value;
        Map<String,Object> wrapper=new LinkedHashMap<String,Object>(4);
        wrapper.put("$thermometer","literal/1");wrapper.put("value",value);return wrapper;
    }
    Object readBody(Object value, Type target, Map<TypeVariable<?>, Type> variables, String path, int depth) {
        Object result=readBody(value,target,variables,path,depth,null);
        return observer==null?result:observer.onRead(value,result,false);
    }

    private Object readBody(Object value,Type target,Map<TypeVariable<?>,Type> variables,String path,int depth,Json5Mapper.Model model) {
        Class<?> raw=null;
        if(model==null) {
            target = resolve(target, variables, path);
            raw = raw(target, variables, path);
            if (value == null) {
                if (raw.isPrimitive()) throw error(path, "null cannot be assigned to " + raw.getName());
                return null;
            }
            Codec<?> codec = mapper.readCodec(target, raw, variables, path);
            if (codec != null) return decode(codec, value, target, raw, variables, path, depth);
            if (raw == Object.class) {
                if(!mapper.registeredTypes.byId.isEmpty() && (value instanceof Map || value instanceof List))
                    return readBody(value,value instanceof Map?Map.class:List.class,variables,path,depth);
                return StreamingValues.isView(value)?StreamingValues.materialize(value):value;
            }
            if (raw == String.class) {
                if (!(value instanceof String)) throw expected(path, "string", value);
                return value;
            }
            if (raw == boolean.class || raw == Boolean.class) {
                if (!(value instanceof Boolean)) throw expected(path, "boolean", value);
                return value;
            }
            if (raw == char.class || raw == Character.class) {
                if (!(value instanceof String) || ((String) value).length() != 1) throw expected(path, "one UTF-16 character", value);
                return ((String) value).charAt(0);
            }
            if (raw.isEnum()) {
                if (!(value instanceof String)) throw expected(path, "enum name", value);
                for (Object item : raw.getEnumConstants()) if (((Enum<?>) item).name().equals(value)) return item;
                throw error(path, "Unknown " + raw.getSimpleName() + " value: " + value);
            }
            if (numeric(raw)) {
                if (!(value instanceof Number)) throw expected(path, "number", value);
                return number((Number) value, raw, path);
            }
        }
        enter(value, path, depth);
        try {
            if(model==null) {
                if (raw.isArray()) {
                    if (!(value instanceof List)) throw expected(path, "array", value);
                    Type element = target instanceof GenericArrayType ? ((GenericArrayType) target).getGenericComponentType() : raw.getComponentType();
                    List<?> values = (List<?>) value;
                    Object result = Array.newInstance(raw.getComponentType(), values.size());
                    if(graph!=null)graph.bind(value,result);
                    int arrayIndex=0;
                    for(Object item:values)Array.set(result,arrayIndex,read(item,element,variables,index(path,arrayIndex++),depth+1));
                    return result;
                }
                if (Collection.class.isAssignableFrom(raw)) {
                    if (!(value instanceof List)) throw expected(path, "array", value);
                    Collection<Object> result = collection(raw, path, ((List<?>)value).size());
                    if(graph!=null)graph.bind(value,result);
                    Type element = containerArguments(target, Collection.class, variables, path)[0];
                boolean reuseModel=observer==null && graph==null && mapper.codecs.isEmpty() && mapper.registeredTypes.byId.isEmpty() && element instanceof Class;
                    Json5Mapper.Model elementModel=null;
                    int i = 0;
                    for (Object item : (List<?>) value) {
                        String location = index(path, i++);
                        Object converted;
                        if(elementModel!=null && item instanceof Map) {
                            converted=readBody(item,element,variables,location,depth+1,elementModel);
                        } else {
                            converted=read(item,element,variables,location,depth+1);
                            // A successful ordinary conversion establishes the exact POJO model.
                            // Keep container, codec and registered-type dispatch on its normal path.
                            if(reuseModel && item instanceof Map && converted!=null && converted.getClass()==element
                                    && !(converted instanceof Map) && !(converted instanceof Collection))
                                elementModel=mapper.model((Class<?>)element,location);
                        }
                        if(graph!=null && result instanceof Set)graph.checkKey(item,location);
                        try { result.add(converted); }
                        catch (RuntimeException e) { throw new MappingException(location, "Collection rejected value", e); }
                    }
                    return result;
                }
                if (Map.class.isAssignableFrom(raw) && mapper.mapEncoding==MapEncoding.ENTRY_ARRAY) {
                    if(IdentityHashMap.class.isAssignableFrom(raw))throw error(path,"Entry-array encoding does not preserve IdentityHashMap key identity");
                    if(value instanceof List) {
                        Type[] mapTypes=containerArguments(target,Map.class,variables,path);
                        return readEntries((List<?>)value,raw,mapTypes,variables,path,depth);
                    }
                    if(!(value instanceof Map))throw expected(path,"entry array or String-key object",value);
                }
                if (!(value instanceof Map)) throw expected(path, "object", value);
                Map<?, ?> values = (Map<?, ?>) value;
                if (Map.class.isAssignableFrom(raw)) {
                    Type[] args = containerArguments(target, Map.class, variables, path);
                    Type keyType = args.length == 2 ? resolve(args[0], variables, path) : String.class;
                    if (keyType != String.class && keyType != Object.class) throw error(path, "Map keys must be String");
                    Map<String, Object> result = map(raw, path);
                    if(graph!=null)graph.bind(value,result);
                    for (Map.Entry<?, ?> entry : values.entrySet()) {
                        if (!(entry.getKey() instanceof String)) throw error(path, "Map keys must be String");
                        String key = (String) entry.getKey();
                        result.put(key, read(entry.getValue(), args.length == 2 ? args[1] : Object.class, variables, child(path, key), depth + 1));
                    }
                    return result;
                }
                model = mapper.model(raw, path);
            }
            Map<?,?> values=(Map<?,?>)value;
            Object result = model.creator==null ? model.newInstance(path) : null;
            if(graph!=null && result!=null)graph.bind(value,result);
            Map<TypeVariable<?>, Type> bindings = model.simpleBindings && target instanceof Class
                    && variables.isEmpty() && !(model.creator instanceof Method)
                    ? variables : bindings(target, variables, path);
            if (model.creator instanceof Method) inferFactoryBindings((Method)model.creator, target, bindings, path);
            Set<ObjectModel.Property> assigned = model.hasAliases ? new HashSet<ObjectModel.Property>() : null;
            Object[] arguments = model.creator==null ? null : new Object[model.parameters.length];
            boolean[] supplied = model.creator==null ? null : new boolean[arguments.length];
            List<Assignment> pending = model.creator==null ? null : new ArrayList<Assignment>();
            boolean directFields=arguments==null && graph==null && mapper.codecs.isEmpty();
            if (arguments!=null) for (int i=0;i<arguments.length;i++) arguments[i]=ObjectModel.defaultValue(model.parameters[i].parameterClass);
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                if (!(entry.getKey() instanceof String)) throw error(path, "Object keys must be String");
                String key = (String) entry.getKey();
                ObjectModel.Property field = model.inputs.get(key);
                if (field == null) {
                    if (mapper.failOnUnknownFields && !model.ignored.contains(key)) throw error(child(path,key), "Unknown field");
                    continue;
                }
                if (!field.read) continue;
                if (assigned != null && !assigned.add(field)) throw error(child(path,key), "Multiple names supplied for field " + field.name);
                if (field.parameterIndex<0 && !field.canSet()) throw error(child(path,key), "Final fields and getter-only properties are output-only");
                Object input = entry.getValue();
                Type fieldType = field.inputType();
                if(directFields && field.codec==null && field.setter==null && field.field!=null) {
                    if(fieldType==String.class && input instanceof String
                            || (fieldType==boolean.class || fieldType==Boolean.class) && input instanceof Boolean
                            || (fieldType==double.class || fieldType==Double.class) && input instanceof Double
                            || (fieldType==long.class || fieldType==Long.class) && input instanceof Long
                            || (fieldType==int.class || fieldType==Integer.class) && input instanceof Integer) {
                        field.setDirect(result,input,path,key);continue;
                    }
                    if((fieldType==int.class || fieldType==Integer.class) && input instanceof Long) {
                        long number=((Long)input).longValue();
                        if(number>=Integer.MIN_VALUE && number<=Integer.MAX_VALUE) {
                            field.setDirect(result,Integer.valueOf((int)number),path,key);continue;
                        }
                    }
                }
                String location = child(path, key);
                Object converted = field.codec == null || input == null ? read(input, fieldType, bindings, location, depth + 1)
                        : graph!=null ? graph.read(input,fieldType,bindings,location,depth+1,field.codec)
                        : decode(field.codec, input, resolve(fieldType,bindings,location), raw(fieldType,bindings,location), bindings, location, depth + 1);
                if (arguments==null) field.set(result,converted,location);
                else if (field.parameterIndex>=0) { arguments[field.parameterIndex]=converted; supplied[field.parameterIndex]=true; }
                else pending.add(new Assignment(field,converted,location));
            }
            if (arguments!=null) {
                for (int i=0;i<arguments.length;i++) {
                    ObjectModel.Property p=model.parameters[i];
                    if (p.read && !p.options.ignore && p.options.required && !supplied[i]) throw error(child(path,p.name),"Missing creator parameter");
                }
                result=model.create(arguments,path);
                for (Assignment assignment:pending) assignment.property.set(result,assignment.value,assignment.path);
            }
            return result;
        } finally { active.remove(value); }
    }

    Object write(Object value, String path, int depth) {
        return write(value, value == null ? Object.class : value.getClass(), Collections.<TypeVariable<?>, Type>emptyMap(), path, depth);
    }

    void streamWrite(Json5StreamWriter out,Object value,Type declared,Map<TypeVariable<?>,Type> variables,String path,int depth,Codec<?> fieldCodec) throws IOException {
        if(graph!=null){out.value(graph.write(value,declared,variables,path,depth,fieldCodec));return;}
        if(value instanceof CommentedValue) {
            enter(value,path,depth);
            try{CommentedValue comment=(CommentedValue)value;out.comment(comment.getComment());Object inner=comment.getValue();streamWrite(out,inner,inner==null?Object.class:inner.getClass(),variables,path,depth+1,fieldCodec);}
            finally{active.remove(value);}return;
        }
        RegisteredTypes.Entry registered=fieldCodec==null?RegisteredTypeMapping.outputType(mapper,value,declared,variables,path):null;
        if(registered!=null) {
            if(depth+1>=Yomu.DEFAULT_MAX_DEPTH)throw error(path,"Maximum polymorphic depth exceeded");
            out.beginObject().name("$thermometer").value("type/1").name("type").value(registered.id).name("value");
            streamBody(out,value,registered.type,variables,path,depth+1,null,true);out.endObject();
        }else streamBody(out,value,declared,variables,path,depth,fieldCodec,false);
    }
    void streamBody(Json5StreamWriter out,Object value,Type declared,Map<TypeVariable<?>,Type> variables,String path,int depth,Codec<?> adapter,boolean payload) throws IOException {
        if(value==null){out.nullValue();return;}
        declared=writeType(declared,variables,value.getClass(),path);
        if(!boxed(raw(declared,variables,path)).isInstance(value))throw error(path,"Value does not match declared type "+declared);
        if(adapter==null)adapter=mapper.writeCodec(declared,value.getClass(),variables,path);
        if(adapter!=null) {
            Object encoded=encodeBody(adapter,value,declared,variables,path,depth);
            if(!payload && encoded instanceof Map) {
                @SuppressWarnings("unchecked") Map<String,Object> object=(Map<String,Object>)encoded;encoded=escapeLiteral(object);
            }
            out.value(encoded);return;
        }
        if(value instanceof Number&&!numeric(value.getClass()))throw error(path,"Unsupported number type: "+value.getClass().getName());
        if(value instanceof String||value instanceof Boolean||value instanceof Number){out.value(value);return;}
        if(value instanceof Character){out.value(value.toString());return;}
        if(value instanceof Enum){out.value(((Enum<?>)value).name());return;}
        enter(value,path,depth);
        try {
            if(value instanceof Map) {
                Map<?,?> map=(Map<?,?>)value;
                Type mapType=Map.class.isAssignableFrom(raw(declared,variables,path))?declared:value.getClass();
                Type[] arguments=containerArguments(mapType,Map.class,variables,path);
                if(mapper.mapEncoding==MapEncoding.ENTRY_ARRAY) {
                    if(value instanceof IdentityHashMap)throw error(path,"Entry-array encoding does not preserve IdentityHashMap key identity");
                    out.beginArray();int i=0;
                    for(Map.Entry<?,?> entry:map.entrySet()) {
                        String location=index(path,i++);if(depth+1>=Yomu.DEFAULT_MAX_DEPTH)throw error(location,"Maximum mapping depth exceeded");
                        out.beginArray();streamWrite(out,entry.getKey(),arguments[0],variables,index(location,0),depth+2,null);
                        streamWrite(out,entry.getValue(),arguments[1],variables,index(location,1),depth+2,null);out.endArray();
                    }out.endArray();return;
                }
                boolean probe=!payload&&!mapper.registeredTypes.byId.isEmpty()&&map.containsKey("$thermometer")&&canProbe(map.get("$thermometer"),arguments[1],variables,path,null);
                Object marker=probe?write(map.get("$thermometer"),arguments[1],variables,child(path,"$thermometer"),depth+1):null;
                boolean literal=probe&&reserved(marker);
                if(literal)literalStart(out);
                out.beginObject();
                for(Map.Entry<?,?> entry:map.entrySet()) {
                    if(!(entry.getKey() instanceof String))throw error(path,"Map keys must be String");
                    String key=(String)entry.getKey();out.name(key);
                    if(probe&&key.equals("$thermometer"))out.value(marker);
                    else streamWrite(out,entry.getValue(),arguments[1],variables,child(path,key),depth+1,null);
                }out.endObject();if(literal)out.endObject();return;
            }
            if(value instanceof Iterable||value.getClass().isArray()) {
                out.beginArray();
                if(value instanceof Iterable) {
                    Type element=Object.class;
                    if(value instanceof Collection) {
                        Type container=Collection.class.isAssignableFrom(raw(declared,variables,path))?declared:value.getClass();
                        element=containerArguments(container,Collection.class,variables,path)[0];
                    }
                    int i=0;for(Object item:(Iterable<?>)value)streamWrite(out,item,element,variables,index(path,i++),depth+1,null);
                }else {
                    Type component=declared instanceof GenericArrayType?((GenericArrayType)declared).getGenericComponentType()
                            :raw(declared,variables,path).isArray()?raw(declared,variables,path).getComponentType():value.getClass().getComponentType();
                    for(int i=0;i<Array.getLength(value);i++)streamWrite(out,Array.get(value,i),component,variables,index(path,i),depth+1,null);
                }out.endArray();return;
            }
            Json5Mapper.Model model=mapper.model(value.getClass(),path);
            Map<TypeVariable<?>,Type> fields=modelBindings(model,declared,variables,path);
            ObjectModel.Property markerProperty=null;Object markerRaw=null,marker=null;boolean probe=false;
            if(!payload&&!mapper.registeredTypes.byId.isEmpty())for(ObjectModel.Property field:model.fields.values())if(field.write&&field.name.equals("$thermometer")) {
                markerProperty=field;markerRaw=field.get(value,child(path,field.name));
                probe=canProbe(markerRaw,field.outputType(),fields,path,field.codec);
                if(probe)marker=propertyValue(field,markerRaw,fields,child(path,field.name),depth+1);
            }
            boolean literal=probe&&reserved(marker);if(literal)literalStart(out);out.beginObject();
            for(ObjectModel.Property field:model.fields.values())if(field.write) {
                String location=child(path,field.name);out.name(field.name);
                if(field==markerProperty && probe){out.value(marker);continue;}
                Object rawValue=field==markerProperty?markerRaw:field.get(value,location);
                if(!field.comment.isEmpty())out.comment(field.comment);
                streamWrite(out,rawValue,field.outputType(),fields,location,depth+1,field.codec);
            }out.endObject();if(literal)out.endObject();
        }finally{active.remove(value);}
    }
    private boolean canProbe(Object value,Type type,Map<TypeVariable<?>,Type> variables,String path,Codec<?> adapter) {
        return value==null||value instanceof String||value instanceof Character||value instanceof Enum||adapter!=null
                || mapper.writeCodec(writeType(type,variables,value.getClass(),path),value.getClass(),variables,path)!=null;
    }
    private boolean reserved(Object value) {
        value=uncomment(value,"$");return value instanceof String&&(((String)value).startsWith("type/")||((String)value).startsWith("literal/"));
    }
    private void literalStart(Json5StreamWriter out) throws IOException {out.beginObject().name("$thermometer").value("literal/1").name("value");}
    private Object propertyValue(ObjectModel.Property field,Object value,Map<TypeVariable<?>,Type> variables,String path,int depth) {
        Object result=field.codec!=null&&value!=null?encodeBody(field.codec,value,writeType(field.outputType(),variables,value.getClass(),path),variables,path,depth)
                :write(value,field.outputType(),variables,path,depth);
        return field.comment.isEmpty()?result:new CommentedValue(field.comment,result);
    }

    Object write(Object value, Type declared, Map<TypeVariable<?>,Type> variables, String path, int depth) {
        if(graph!=null)return graph.write(value,declared,variables,path,depth,null);
        RegisteredTypes.Entry entry=RegisteredTypeMapping.outputType(mapper,value,declared,variables,path);
        if(entry!=null) {
            if(depth+1>=Yomu.DEFAULT_MAX_DEPTH)throw error(path,"Maximum polymorphic depth exceeded");
            Map<String,Object> wrapper=new LinkedHashMap<String,Object>(4);
            wrapper.put("$thermometer","type/1");wrapper.put("type",entry.id);
            wrapper.put("value",writeBody(value,entry.type,variables,path,depth+1));return wrapper;
        }
        Object result=writeBody(value,declared,variables,path,depth);
        if(!mapper.registeredTypes.byId.isEmpty() && typeMarker(result)!=null) {
            @SuppressWarnings("unchecked") Map<String,Object> object=(Map<String,Object>)result;
            return escapeLiteral(object);
        }
        return result;
    }
    Object writeBody(Object value, Type declared, Map<TypeVariable<?>,Type> variables, String path, int depth) {
        Object result=writeBodyValue(value,declared,variables,path,depth);
        if(observer!=null)observer.onWrite(value,result,path,false);
        return result;
    }
    private Object writeBodyValue(Object value, Type declared, Map<TypeVariable<?>,Type> variables, String path, int depth) {
        if (value == null) return null;
        boolean typed = mapper.objectGraph || !mapper.registeredTypes.byId.isEmpty() || !mapper.codecs.isEmpty() || mapper.mapEncoding==MapEncoding.ENTRY_ARRAY;
        if (typed) {
            declared = writeType(declared, variables, value.getClass(), path);
            if (!boxed(raw(declared, variables, path)).isInstance(value)) throw error(path, "Value does not match declared type " + declared);
            Codec<?> codec = mapper.writeCodec(declared, value.getClass(), variables, path);
            if (codec != null) return encodeBody(codec, value, declared, variables, path, depth);
        }
        if (value == null || value instanceof String || value instanceof Boolean) return value;
        if (value instanceof Number) {
            if (!numeric(value.getClass())) throw error(path, "Unsupported number type: " + value.getClass().getName());
            return value;
        }
        if (value instanceof Character) return value.toString();
        if (value instanceof Enum) return ((Enum<?>) value).name();
        enter(value, path, depth);
        try {
            if (value instanceof CommentedValue) {
                CommentedValue cv = (CommentedValue) value;
                return new CommentedValue(cv.getComment(), write(cv.getValue(), path, depth + 1));
            }
            if (value instanceof Map) {
                if(mapper.mapEncoding==MapEncoding.ENTRY_ARRAY) {
                    if(value instanceof IdentityHashMap)throw error(path,"Entry-array encoding does not preserve IdentityHashMap key identity");
                    Type mapType=Map.class.isAssignableFrom(raw(declared,variables,path)) ? declared : value.getClass();
                    Type[] mapTypes=containerArguments(mapType,Map.class,variables,path);
                    return writeEntries((Map<?,?>)value,mapTypes,variables,path,depth);
                }
                Type element = Object.class;
                if (typed) {
                    Type mapType = Map.class.isAssignableFrom(raw(declared,variables,path)) ? declared : value.getClass();
                    element = containerArguments(mapType, Map.class, variables, path)[1];
                }
                Map<String, Object> result = new LinkedHashMap<String, Object>();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    if (!(entry.getKey() instanceof String)) throw error(path, "Map keys must be String");
                    String key = (String) entry.getKey();
                    result.put(key, write(entry.getValue(), element, variables, child(path, key), depth + 1));
                }
                return result;
            }
            if (value instanceof Iterable || value.getClass().isArray()) {
                List<Object> result = new ArrayList<Object>();
                if (value instanceof Iterable) {
                    Type element = Object.class;
                    if (typed && value instanceof Collection) {
                        Type collectionType = Collection.class.isAssignableFrom(raw(declared,variables,path)) ? declared : value.getClass();
                        element = containerArguments(collectionType, Collection.class, variables, path)[0];
                    }
                    int i = 0;
                    for (Object item : (Iterable<?>) value) result.add(write(item, element, variables, index(path, i++), depth + 1));
                } else {
                    Type component = value.getClass().getComponentType();
                    if (typed) component = declared instanceof GenericArrayType ? ((GenericArrayType)declared).getGenericComponentType()
                            : raw(declared,variables,path).isArray() ? raw(declared,variables,path).getComponentType() : component;
                    for (int i = 0; i < Array.getLength(value); i++) result.add(write(Array.get(value, i), component, variables, index(path, i), depth + 1));
                }
                return result;
            }
            Json5Mapper.Model model = mapper.model(value.getClass(), path);
            Map<String, Object> result = new LinkedHashMap<String, Object>(model.fields.size()<=3?4:16);
            Map<TypeVariable<?>,Type> fieldTypes = typed || model.hasFieldCodecs ? modelBindings(model,declared,variables,path) : Collections.<TypeVariable<?>,Type>emptyMap();
            for (ObjectModel.Property field : model.fields.values()) {
                if (!field.write) continue;
                String location = child(path, field.name);
                result.put(field.name,writeProperty(field,value,fieldTypes,location,depth+1));
            }
            return result;
        } finally { active.remove(value); }
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private Map<Object,Object> readEntries(List<?> entries,Class<?> type,Type[] types,
                                           Map<TypeVariable<?>,Type> variables,String path,int depth) {
        Map<Object,Object> result;
        if(type==EnumMap.class) {
            Class<?> keyClass=raw(types[0],variables,path);
            if(!keyClass.isEnum())throw error(path,"EnumMap requires a concrete enum key type");
            result=new EnumMap((Class)keyClass);
        } else result=(Map)map(type,path);
        if(graph!=null)graph.bind(entries,result);
        int entryIndex=0;
        for(Object row:entries) {
            String location=index(path,entryIndex++);
            if(!(row instanceof List))throw expected(location,"[key,value] entry array",row);
            List<?> pair=(List<?>)row;
            if(pair.size()!=2)throw error(location,"Map entry must contain exactly two values");
            enter(pair,location,depth+1);
            try {
                String keyPath=index(location,0),valuePath=index(location,1);
                Object key=read(pair.get(0),types[0],variables,keyPath,depth+2);
                if(graph!=null)graph.checkKey(pair.get(0),keyPath);
                boolean duplicate;
                try { duplicate=result.containsKey(key); }
                catch(RuntimeException e){throw new MappingException(keyPath,"Map rejected key",e);}
                if(duplicate)throw error(keyPath,"Duplicate map key after conversion");
                Object item=read(pair.get(1),types[1],variables,valuePath,depth+2);
                try { result.put(key,item); }
                catch(RuntimeException e){throw new MappingException(location,"Map rejected entry",e);}
            } finally {active.remove(pair);}
        }
        return result;
    }

    private List<Object> writeEntries(Map<?,?> map,Type[] types,Map<TypeVariable<?>,Type> variables,String path,int depth) {
        List<Object> result=new ArrayList<Object>();
        int i=0;
        for(Map.Entry<?,?> entry:map.entrySet()) {
            String location=index(path,i++);
            if(depth+1>=Yomu.DEFAULT_MAX_DEPTH)throw error(location,"Maximum mapping depth exceeded");
            List<Object> pair=new ArrayList<Object>(2);
            pair.add(write(entry.getKey(),types[0],variables,index(location,0),depth+2));
            pair.add(write(entry.getValue(),types[1],variables,index(location,1),depth+2));
            result.add(pair);
        }
        return result;
    }

    Object writeProperty(ObjectModel.Property field,Object owner,Map<TypeVariable<?>,Type> fieldTypes,String path,int depth) {
        Object value=field.get(owner,path);
        Object converted=field.codec!=null && value!=null
                ? encode(field.codec,value,writeType(field.outputType(),fieldTypes,value.getClass(),path),fieldTypes,path,depth)
                : mapper.objectGraph || !mapper.registeredTypes.byId.isEmpty() || !mapper.codecs.isEmpty() || mapper.mapEncoding==MapEncoding.ENTRY_ARRAY ? write(value,field.outputType(),fieldTypes,path,depth) : write(value,path,depth);
        return field.comment.isEmpty() ? converted : new CommentedValue(field.comment,converted);
    }

    Object decode(Codec<?> codec, Object value, Type type, Class<?> raw,
                          Map<TypeVariable<?>,Type> variables, String path, int depth) {
        if(StreamingValues.isView(value))value=StreamingValues.materialize(value);
        if (depth >= Yomu.DEFAULT_MAX_DEPTH) throw error(path,"Maximum codec depth exceeded");
        Type concrete = TypeKeys.of(substitute(type,variables,path));
        if (codecReads == null) codecReads = new ArrayList<DecodeFrame>();
        for (DecodeFrame frame : codecReads) {
            if (frame.value == value && frame.type.equals(concrete)) throw error(path,"Recursive codec invocation");
        }
        codecReads.add(new DecodeFrame(value, concrete));
        try {
            Object result = codec.decode(observer==null?value:observer.codecInput(value), new Context(concrete,variables,path,depth));
            if (result == null ? raw.isPrimitive() : !boxed(raw).isInstance(result)) {
                throw error(path,"Codec returned a value incompatible with " + concrete);
            }
            return observer==null?result:observer.onRead(value,result,true);
        } catch (MappingException e) { throw e; }
        catch (RuntimeException e) { throw new MappingException(path,"Codec failed reading " + concrete,e); }
        finally { codecReads.remove(codecReads.size()-1); }
    }

    private Object encode(Codec<?> codec,Object value,Type type,Map<TypeVariable<?>,Type> variables,String path,int depth) {
        return graph==null?encodeBody(codec,value,type,variables,path,depth):graph.write(value,type,variables,path,depth,codec);
    }
    @SuppressWarnings("unchecked")
    Object encodeBody(Codec<?> codec, Object value, Type type, Map<TypeVariable<?>,Type> variables, String path, int depth) {
        enter(value,path,depth);
        Object result;
        try {
            result = ((Codec<Object>)codec).encode(value,new Context(TypeKeys.of(substitute(type,variables,path)),variables,path,depth));
        } catch (MappingException e) { throw e; }
        catch (RuntimeException e) { throw new MappingException(path,"Codec failed writing " + type,e); }
        finally { active.remove(value); }
        validateEncoded(result,path,depth);
        if(observer!=null)observer.onWrite(value,result,path,true);
        return result;
    }

    // A codec returns a finished JSON5 representation. Do not apply codecs to it again.
    void validateEncoded(Object value, String path, int depth) {
        if (value == null || value instanceof String || value instanceof Boolean) return;
        if (value instanceof Number) {
            if (!numeric(value.getClass())) throw error(path,"Unsupported number from codec");
            return;
        }
        enter(value,path,depth);
        try {
            if (value instanceof CommentedValue) {
                validateEncoded(((CommentedValue)value).getValue(),path,depth+1);
            } else if (value instanceof Map) {
                for (Map.Entry<?,?> e : ((Map<?,?>)value).entrySet()) {
                    if (!(e.getKey() instanceof String)) throw error(path,"Codec object keys must be String");
                    validateEncoded(e.getValue(),child(path,(String)e.getKey()),depth+1);
                }
            } else if (value instanceof Iterable) {
                int i=0;
                for (Object item : (Iterable<?>)value) validateEncoded(item,index(path,i++),depth+1);
            } else if (value.getClass().isArray()) {
                for(int i=0;i<Array.getLength(value);i++) validateEncoded(Array.get(value,i),index(path,i),depth+1);
            } else throw error(path,"Codec must return core JSON5 values; use context.toValue for nested objects");
        } finally { active.remove(value); }
    }

    private final class Context implements CodecContext {
        final Type target;
        final Map<TypeVariable<?>,Type> variables;
        final String path;
        final int depth;
        Context(Type target, Map<TypeVariable<?>,Type> variables, String path, int depth) {
            this.target=target; this.variables=variables; this.path=path; this.depth=depth;
        }
        public String getPath() { return path; }
        public Type getTargetType() { return target; }
        @SuppressWarnings("unchecked")
        public <T> T read(Object value, Class<T> type) {
            return (T) MappingSession.this.read(value,Objects.requireNonNull(type,"type"),variables,path,depth+1);
        }
        @SuppressWarnings("unchecked")
        public <T> T read(Object value, TypeRef<T> type) {
            return (T) MappingSession.this.read(value,Objects.requireNonNull(type,"type").getType(),variables,path,depth+1);
        }
        public Object toValue(Object value) { return MappingSession.this.write(value,path,depth+1); }
        public <T> Object toValue(T value, TypeRef<T> type) {
            return MappingSession.this.write(value,Objects.requireNonNull(type,"type").getType(),variables,path,depth+1);
        }
        public CodecContext atField(String name) {
            return new Context(Object.class,variables,child(path,Objects.requireNonNull(name,"name")),depth+1);
        }
        public CodecContext atIndex(int i) {
            if (i<0) throw new IllegalArgumentException("Negative array index");
            return new Context(Object.class,variables,index(path,i),depth+1);
        }
        public MappingException error(String message) { return MappingValues.error(path,message); }
    }

    private static final class DecodeFrame {
        final Object value;
        final Type type;
        DecodeFrame(Object value, Type type) { this.value=value; this.type=type; }
    }

    private static final class Assignment {
        final ObjectModel.Property property;
        final Object value;
        final String path;
        Assignment(ObjectModel.Property property,Object value,String path) { this.property=property; this.value=value; this.path=path; }
    }
}
