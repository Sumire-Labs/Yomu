package com.sumirelabs.yomu.mapper;

import com.sumirelabs.yomu.CommentedValue;
import com.sumirelabs.yomu.Json5Writer;
import com.sumirelabs.yomu.Json5ReadOptions;
import com.sumirelabs.yomu.Yomu;
import com.sumirelabs.yomu.Json5StreamReader;
import com.sumirelabs.yomu.Json5StreamWriter;
import com.sumirelabs.yomu.Json5Document;
import java.io.IOException;
import java.io.Reader;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;

import static com.sumirelabs.yomu.mapper.MappingTypes.*;
import static com.sumirelabs.yomu.mapper.MappingValues.*;

/**
 * Immutable, thread-safe mapper for Java 8 POJOs. Public fields are mapped by default;
 * annotated private fields opt in. Missing fields retain constructor defaults.
 * Supports no-argument construction, explicit creators and records on capable runtimes.
 * No runtime class names are read from JSON.
 */
public final class Json5Mapper {
    final boolean failOnUnknownFields;
    final Json5Writer writer;
    final Map<Type, Codec<?>> codecs;
    private final boolean hasGenericCodecs;
    private final ClassValue<CodecMatch> runtimeCodecs;
    private final NamingStrategy namingStrategy;
    private final ClassValue<Model> models;
    private final boolean beanAccessors;
    final Json5ReadOptions readOptions;
    private final Set<Field> registeredStatics;
    private final ClassValue<Map<String,ObjectModel.Property>> staticModels;
    final MapEncoding mapEncoding;
    final boolean objectGraph;
    final RegisteredTypes registeredTypes;
    // ClassValue permits unloading mod classes and their class loaders together.
    private static final ClassValue<Model> MODELS = new ClassValue<Model>() {
        protected Model computeValue(Class<?> type) { return new Model(type, NamingPolicy.IDENTITY, false, Collections.<Field>emptySet()); }
    };
    private static final ClassValue<Map<String,ObjectModel.Property>> STATIC_MODELS = new ClassValue<Map<String,ObjectModel.Property>>() {
        protected Map<String,ObjectModel.Property> computeValue(Class<?> type) {
            return ObjectModel.staticModel(type,NamingPolicy.IDENTITY,Collections.<Field>emptySet());
        }
    };

    public Json5Mapper() { this(false, new Json5Writer(), Collections.<Type, Codec<?>>emptyMap(), NamingPolicy.IDENTITY, false, Json5ReadOptions.DEFAULT, Collections.<Field>emptySet(), MapEncoding.OBJECT,false,RegisteredTypes.EMPTY); }

    /** Starts mutable configuration; build returns an immutable reusable mapper. */
    public static Builder builder() { return new Builder(new Json5Mapper()); }
    /** Retains both syntax directions; later Builder.writer calls cannot weaken strict output. */
    public Builder toBuilder() { return new Builder(this); }

    /** Mutable configuration, not thread-safe. Ignoring a mutator's return value does not lose changes.
     * Built mappers retain their own configuration when this builder is changed later.
     * Replacing the writer preserves enabled strict output; strictJson(false) explicitly disables it. */
    public static final class Builder {
        private Json5Mapper value;
        private Builder(Json5Mapper value) { this.value = value; }
        public Builder failOnUnknownFields(boolean enabled) { value = value.withFailOnUnknownFields(enabled); return this; }
        /** Replaces formatting but does not disable strict output already enabled on this builder.
         * An incoming strict writer may enable strict output without changing the input syntax.
         * Use strictJson(false) to explicitly return both directions to JSON5. */
        public Builder writer(Json5Writer writer) {
            Objects.requireNonNull(writer,"writer");
            if(value.writer.isStrictJson() && !writer.isStrictJson())writer=writer.withStrictJson(true);
            value = value.withWriter(writer);return this;
        }
        /** Replaces all input options, including syntax, without changing output settings. */
        public Builder readOptions(Json5ReadOptions options) { value = value.withReadOptions(options); return this; }
        public Builder namingStrategy(NamingStrategy strategy) { value = value.withNamingStrategy(strategy); return this; }
        public Builder beanAccessors(boolean enabled) { value = value.withBeanAccessors(enabled); return this; }
        public Builder bigDecimals(boolean enabled) { value = value.withBigDecimals(enabled); return this; }
        /** Explicitly selects JSON or JSON5 for both directions, preserving formatting. */
        public Builder strictJson(boolean enabled) { value = value.withStrictJson(enabled); return this; }
        public Builder mapEncoding(MapEncoding encoding) { value = value.withMapEncoding(encoding); return this; }
        public Builder objectGraph(boolean enabled) { value = value.withObjectGraph(enabled); return this; }
        public Builder staticField(Class<?> declaringType, String fieldName) { value = value.withStaticField(declaringType, fieldName); return this; }
        public Builder standardCodecs() { value = value.withStandardCodecs(); return this; }
        public <T> Builder codec(Class<T> type, Codec<T> codec) { value = value.withCodec(type, codec); return this; }
        public <T> Builder codec(TypeRef<T> type, Codec<T> codec) { value = value.withCodec(type, codec); return this; }
        public <T> Builder type(String id, Class<T> type) { value = value.withType(id, type); return this; }
        public <T> Builder type(String id, TypeRef<T> type) { value = value.withType(id, type); return this; }
        public Json5Mapper build() { return value; }
    }

    private Json5Mapper(boolean failOnUnknownFields, Json5Writer writer, Map<Type, Codec<?>> codecs, final NamingStrategy namingStrategy, final boolean beanAccessors, Json5ReadOptions readOptions, final Set<Field> registeredStatics, MapEncoding mapEncoding,boolean objectGraph,RegisteredTypes registeredTypes) {
        this.failOnUnknownFields = failOnUnknownFields;
        this.writer = writer;
        this.codecs = codecs;
        this.namingStrategy = namingStrategy;
        this.beanAccessors = beanAccessors;
        this.readOptions = readOptions;
        this.registeredStatics = registeredStatics;
        this.mapEncoding = mapEncoding;
        this.objectGraph = objectGraph;
        this.registeredTypes = registeredTypes;
        this.models = namingStrategy == NamingPolicy.IDENTITY && !beanAccessors && registeredStatics.isEmpty() ? MODELS : new ClassValue<Model>() {
            protected Model computeValue(Class<?> type) { return new Model(type, namingStrategy, beanAccessors, registeredStatics); }
        };
        this.staticModels = namingStrategy == NamingPolicy.IDENTITY && registeredStatics.isEmpty() ? STATIC_MODELS : new ClassValue<Map<String,ObjectModel.Property>>() {
            protected Map<String,ObjectModel.Property> computeValue(Class<?> type) { return ObjectModel.staticModel(type,namingStrategy,registeredStatics); }
        };
        boolean generic = false;
        for (Type type : codecs.keySet()) if (!(type instanceof Class)) generic = true;
        hasGenericCodecs = generic;
        runtimeCodecs = codecs.isEmpty() ? null : new ClassValue<CodecMatch>() {
            protected CodecMatch computeValue(Class<?> type) { return matchRuntimeCodec(type); }
        };
    }

    public Json5Mapper withFailOnUnknownFields(boolean value) { return new Json5Mapper(value, writer, codecs, namingStrategy, beanAccessors, readOptions, registeredStatics, mapEncoding,objectGraph,registeredTypes); }
    /** Replaces the entire output configuration, including syntax. Unlike Builder.writer, this is an exact replacement. */
    public Json5Mapper withWriter(Json5Writer value) {
        return new Json5Mapper(failOnUnknownFields, Objects.requireNonNull(value, "writer"), codecs, namingStrategy, beanAccessors, readOptions, registeredStatics, mapEncoding,objectGraph,registeredTypes);
    }

    /** Returns a mapper using this strategy except for explicitly named fields. */
    public Json5Mapper withNamingStrategy(NamingStrategy strategy) {
        return new Json5Mapper(failOnUnknownFields, writer, codecs, Objects.requireNonNull(strategy, "strategy"), beanAccessors, readOptions, registeredStatics, mapEncoding,objectGraph,registeredTypes);
    }

    /** Opts into conventional public JavaBeans getters/setters. Annotated methods always opt in. */
    public Json5Mapper withBeanAccessors(boolean value) {
        return new Json5Mapper(failOnUnknownFields,writer,codecs,namingStrategy,value,readOptions,registeredStatics,mapEncoding,objectGraph,registeredTypes);
    }

    /** Configures parsing for reads. Does not change already parsed values passed to convert. */
    public Json5Mapper withReadOptions(Json5ReadOptions options) {
        return new Json5Mapper(failOnUnknownFields,writer,codecs,namingStrategy,beanAccessors,Objects.requireNonNull(options,"options"),registeredStatics,mapEncoding,objectGraph,registeredTypes);
    }

    /** Reads decimal literals directly as BigDecimal; the default is false. */
    public Json5Mapper withBigDecimals(boolean value) { return withReadOptions(readOptions.withBigDecimals(value)); }

    /** Sets JSON syntax for reads and writes together. Default is JSON5 in both directions. */
    public Json5Mapper withStrictJson(boolean value) {
        return new Json5Mapper(failOnUnknownFields,writer.withStrictJson(value),codecs,namingStrategy,beanAccessors,readOptions.withStrictJson(value),registeredStatics,mapEncoding,objectGraph,registeredTypes);
    }

    /** Sets the explicit wire representation of Map values; existing object mode is the default. */
    public Json5Mapper withMapEncoding(MapEncoding encoding) {
        return new Json5Mapper(failOnUnknownFields,writer,codecs,namingStrategy,beanAccessors,readOptions,registeredStatics,Objects.requireNonNull(encoding,"encoding"),objectGraph,registeredTypes);
    }

    /** Enables the versioned object graph envelope for reads and writes. Disabled by default. */
    public Json5Mapper withObjectGraph(boolean value) {
        return new Json5Mapper(failOnUnknownFields,writer,codecs,namingStrategy,beanAccessors,readOptions,registeredStatics,mapEncoding,value,registeredTypes);
    }

    /** Registers a concrete runtime type under an application-defined ID. No input class names are loaded. */
    public <T> Json5Mapper withType(String id,Class<T> type) { return registerType(id,Objects.requireNonNull(type,"type")); }
    /** Registers a concrete generic type. Only one registration per runtime class is permitted. */
    public <T> Json5Mapper withType(String id,TypeRef<T> type) { return registerType(id,Objects.requireNonNull(type,"type").getType()); }
    private Json5Mapper registerType(String id,Type type) {
        return new Json5Mapper(failOnUnknownFields,writer,codecs,namingStrategy,beanAccessors,readOptions,registeredStatics,mapEncoding,objectGraph,registeredTypes.with(id,type));
    }

    /** Explicitly registers one declared static field for output. Never enables input assignment. */
    public Json5Mapper withStaticField(Class<?> declaringType,String fieldName) {
        Objects.requireNonNull(declaringType,"declaringType");Objects.requireNonNull(fieldName,"fieldName");
        final Field field;
        try { field=declaringType.getDeclaredField(fieldName); }
        catch(NoSuchFieldException e) { throw new IllegalArgumentException("No declared field "+declaringType.getName()+"."+fieldName,e); }
        if(!Modifier.isStatic(field.getModifiers()) || field.isSynthetic())throw new IllegalArgumentException("Expected a non-synthetic static field: "+field);
        Set<Field> copy=new LinkedHashSet<Field>(registeredStatics);copy.add(field);
        return new Json5Mapper(failOnUnknownFields,writer,codecs,namingStrategy,beanAccessors,readOptions,Collections.unmodifiableSet(copy),mapEncoding,objectGraph,registeredTypes);
    }

    /** Serializes only explicitly selected static fields, without constructing an owner instance. */
    public String writeStatics(Class<?> type) { return writer.stringify(staticFieldsToValue(type)); }
    public void writeStatics(Path path,Class<?> type) throws IOException { writer.write(staticFieldsToValue(type),path); }
    /** Converts selected static fields to a core object tree. Writer syntax policy applies when writing it. */
    public Map<String,Object> staticFieldsToValue(Class<?> type) {
        Objects.requireNonNull(type,"type");
        Map<String,ObjectModel.Property> properties;
        try {properties=staticModels.get(type);}
        catch(RuntimeException e){throw new MappingException("$","Cannot inspect static fields of "+type.getName()+": "+e.getMessage(),e);}
        MappingSession conversion=new MappingSession(this);
        Map<String,Object> result=new LinkedHashMap<String,Object>();
        for(ObjectModel.Property field:properties.values())if(field.write) {
            String location=child("$",field.name);
            result.put(field.name,conversion.writeProperty(field,null,Collections.<TypeVariable<?>,Type>emptyMap(),location,1));
        }
        if(objectGraph) {
            int id=conversion.graph.nodes.size();
            Map<String,Object> node=new LinkedHashMap<String,Object>();node.put("kind","object");node.put("value",result);
            conversion.graph.nodes.add(node);
            return conversion.graph.envelope(conversion.graph.reference(id));
        }
        return conversion.escapeLiteral(result);
    }

    /** Returns an independent mapper with a codec for this exact declared type. */
    public <T> Json5Mapper withCodec(Class<T> type, Codec<T> codec) {
        return register(Objects.requireNonNull(type, "type"), codec);
    }

    public <T> Json5Mapper withCodec(TypeRef<T> type, Codec<T> codec) {
        return register(Objects.requireNonNull(type, "type").getType(), codec);
    }

    private Json5Mapper register(Type type, Codec<?> codec) {
        Type key = TypeKeys.of(type);
        if (key == void.class || key == Void.class) throw new IllegalArgumentException("void is not a codec target");
        Map<Type, Codec<?>> copy = new LinkedHashMap<Type, Codec<?>>(codecs);
        copy.put(key, Objects.requireNonNull(codec, "codec"));
        return new Json5Mapper(failOnUnknownFields, writer, Collections.unmodifiableMap(copy), namingStrategy, beanAccessors, readOptions, registeredStatics, mapEncoding,objectGraph,registeredTypes);
    }

    /** Opts into standard string representations of UUID, paths and Java 8 date/time types. */
    public Json5Mapper withStandardCodecs() {
        Map<Type,Codec<?>> copy = new LinkedHashMap<Type,Codec<?>>(StandardCodecs.defaults());
        copy.putAll(codecs); // User registrations always take precedence, regardless of call order.
        return new Json5Mapper(failOnUnknownFields,writer,Collections.unmodifiableMap(copy),namingStrategy,beanAccessors,readOptions,registeredStatics,mapEncoding,objectGraph,registeredTypes);
    }

    public <T> T read(String source, Class<T> type) { return convert(Yomu.parse(source,readOptions), type); }

    /** Reads an editable source-preserving document; initial projection invokes getters and encoders.
     * Unknown input data and original comments remain outside the mapped value. Retains the whole source. */
    public <T> MappedDocument<T> readDocument(String source,Class<T> type) {return DocumentMapping.open(this,Json5Document.read(source,readOptions),Objects.requireNonNull(type,"type"));}
    public <T> MappedDocument<T> readDocument(String source,TypeRef<T> type) {return DocumentMapping.open(this,Json5Document.read(source,readOptions),Objects.requireNonNull(type,"type").getType());}
    public <T> MappedDocument<T> readDocument(Reader source,Class<T> type) throws IOException {return DocumentMapping.open(this,Json5Document.read(source,readOptions),Objects.requireNonNull(type,"type"));}
    public <T> MappedDocument<T> readDocument(Reader source,TypeRef<T> type) throws IOException {return DocumentMapping.open(this,Json5Document.read(source,readOptions),Objects.requireNonNull(type,"type").getType());}
    public <T> MappedDocument<T> readDocument(InputStream source,Class<T> type) throws IOException {return DocumentMapping.open(this,Json5Document.read(source,readOptions),Objects.requireNonNull(type,"type"));}
    public <T> MappedDocument<T> readDocument(InputStream source,TypeRef<T> type) throws IOException {return DocumentMapping.open(this,Json5Document.read(source,readOptions),Objects.requireNonNull(type,"type").getType());}
    public <T> MappedDocument<T> readDocument(Path source,Class<T> type) throws IOException {return DocumentMapping.open(this,Json5Document.read(source,readOptions),Objects.requireNonNull(type,"type"));}
    public <T> MappedDocument<T> readDocument(Path source,TypeRef<T> type) throws IOException {return DocumentMapping.open(this,Json5Document.read(source,readOptions),Objects.requireNonNull(type,"type").getType());}
    public <T> T read(String source, TypeRef<T> type) { return convert(Yomu.parse(source,readOptions), type); }
    public <T> T read(Path source, Class<T> type) throws IOException { return convert(Yomu.parse(source,readOptions), type); }
    public <T> T read(Path source, TypeRef<T> type) throws IOException { return convert(Yomu.parse(source,readOptions), type); }
    /** Reads a complete value tree then maps it. Borrows the source and validates all trailing input.
     * Use readStreaming instead to spool wire values to disk rather than materializing this tree. */
    public <T> T read(Reader source, Class<T> type) throws IOException { return convert(Yomu.parse(source,readOptions), type); }
    public <T> T read(Reader source, TypeRef<T> type) throws IOException { return convert(Yomu.parse(source,readOptions), type); }
    public <T> T read(InputStream source, Class<T> type) throws IOException { return convert(Yomu.parse(source,readOptions), type); }
    public <T> T read(InputStream source, TypeRef<T> type) throws IOException { return convert(Yomu.parse(source,readOptions), type); }
    public String write(Object value) { return writer.stringify(toValue(value)); }
    public <T> String writeAs(T value, TypeRef<T> type) { return writer.stringify(toValue(value, type)); }
    public <T> String writeAs(T value, Class<T> type) { return writer.stringify(toValue(value, type)); }
    public <T> void writeAs(Path path, T value, TypeRef<T> type) throws IOException { writer.write(toValue(value,type),path); }
    public <T> void writeAs(Path path, T value, Class<T> type) throws IOException { writer.write(toValue(value,type),path); }
    public void write(Path path, Object value) throws IOException { writer.write(toValue(value), path); }

    /** Preferred destination-first output. Caller-owned outputs are neither flushed nor closed. */
    public void writeTo(Path output, Object value) throws IOException { write(output, value); }
    public void writeTo(Appendable output, Object value) throws IOException { writer.writeTo(output, toValue(value)); }
    public void writeTo(OutputStream output, Object value) throws IOException { writer.writeTo(output, toValue(value)); }
    public <T> void writeTo(Path output, T value, Class<T> type) throws IOException { writeAs(output, value, type); }
    public <T> void writeTo(Path output, T value, TypeRef<T> type) throws IOException { writeAs(output, value, type); }
    public <T> void writeTo(Appendable output, T value, Class<T> type) throws IOException { writer.writeTo(output, toValue(value, type)); }
    public <T> void writeTo(Appendable output, T value, TypeRef<T> type) throws IOException { writer.writeTo(output, toValue(value, type)); }
    public <T> void writeTo(OutputStream output, T value, Class<T> type) throws IOException { writer.writeTo(output, toValue(value, type)); }
    public <T> void writeTo(OutputStream output, T value, TypeRef<T> type) throws IOException { writer.writeTo(output, toValue(value, type)); }

    /**
     * Converts and serializes completely before saving through an atomic file replacement.
     * Keeps this mapper's format, graph and registered-type settings. No non-atomic fallback.
     * See Json5Writer.writeAtomicallyTo for file permissions, cleanup and durability limits.
     */
    public void writeAtomicallyTo(Path output,Object value) throws IOException {
        Objects.requireNonNull(output,"output");
        writer.writeAtomicallyTo(output,toValue(value));
    }
    /** Atomic file output using an explicit declared class, including registered polymorphism. */
    public <T> void writeAtomicallyTo(Path output,T value,Class<T> type) throws IOException {
        Objects.requireNonNull(output,"output");
        writer.writeAtomicallyTo(output,toValue(value,type));
    }
    /** Atomic file output retaining the declared generic type. */
    public <T> void writeAtomicallyTo(Path output,T value,TypeRef<T> type) throws IOException {
        Objects.requireNonNull(output,"output");
        writer.writeAtomicallyTo(output,toValue(value,type));
    }

    /** Maps one value from a pull reader. Wire values are spooled to temporary disk, not a complete RAM tree.
     * The result and graph identity tables still require memory; codecs materialize their individual input values. */
    public <T> T readValue(Json5StreamReader input,Class<T> type) throws IOException {return StreamingMapping.read(this,input,Objects.requireNonNull(type,"type"),false);}
    public <T> T readValue(Json5StreamReader input,TypeRef<T> type) throws IOException {return StreamingMapping.read(this,input,Objects.requireNonNull(type,"type").getType(),false);}
    public <T> T readStreaming(Reader input,Class<T> type) throws IOException {return StreamingMapping.read(this,Yomu.stream(input,readOptions),Objects.requireNonNull(type,"type"),true);}
    public <T> T readStreaming(Reader input,TypeRef<T> type) throws IOException {return StreamingMapping.read(this,Yomu.stream(input,readOptions),Objects.requireNonNull(type,"type").getType(),true);}
    public <T> T readStreaming(InputStream input,Class<T> type) throws IOException {return StreamingMapping.read(this,Yomu.stream(input,readOptions),Objects.requireNonNull(type,"type"),true);}
    public <T> T readStreaming(InputStream input,TypeRef<T> type) throws IOException {return StreamingMapping.read(this,Yomu.stream(input,readOptions),Objects.requireNonNull(type,"type").getType(),true);}
    public <T> T readStreaming(Path input,Class<T> type) throws IOException {try(Json5StreamReader stream=Yomu.stream(input,readOptions)){return StreamingMapping.read(this,stream,Objects.requireNonNull(type,"type"),true);}}
    public <T> T readStreaming(Path input,TypeRef<T> type) throws IOException {try(Json5StreamReader stream=Yomu.stream(input,readOptions)){return StreamingMapping.read(this,stream,Objects.requireNonNull(type,"type").getType(),true);}}

    /** Writes one mapped value into an existing token writer, without finishing its enclosing document. */
    public void writeValue(Json5StreamWriter output,Object value) throws IOException {StreamingMapping.write(this,output,value,value==null?Object.class:value.getClass());}
    public <T> void writeValue(Json5StreamWriter output,T value,Class<T> type) throws IOException {StreamingMapping.write(this,output,value,Objects.requireNonNull(type,"type"));}
    public <T> void writeValue(Json5StreamWriter output,T value,TypeRef<T> type) throws IOException {StreamingMapping.write(this,output,value,Objects.requireNonNull(type,"type").getType());}
    public void writeStreaming(Object value,Appendable output) throws IOException {Json5StreamWriter stream=writer.stream(output);writeValue(stream,value);stream.finish();}
    public void writeStreaming(Object value,OutputStream output) throws IOException {Json5StreamWriter stream=writer.stream(output);writeValue(stream,value);stream.finish();}
    public void writeStreaming(Path output,Object value) throws IOException {try(Json5StreamWriter stream=writer.stream(output)){writeValue(stream,value);}}
    public <T> void writeAsStreaming(T value,TypeRef<T> type,Appendable output) throws IOException {Json5StreamWriter stream=writer.stream(output);writeValue(stream,value,type);stream.finish();}
    public <T> void writeAsStreaming(T value,Class<T> type,Appendable output) throws IOException {Json5StreamWriter stream=writer.stream(output);writeValue(stream,value,type);stream.finish();}
    public <T> void writeAsStreaming(T value,TypeRef<T> type,OutputStream output) throws IOException {Json5StreamWriter stream=writer.stream(output);writeValue(stream,value,type);stream.finish();}
    public <T> void writeAsStreaming(T value,Class<T> type,OutputStream output) throws IOException {Json5StreamWriter stream=writer.stream(output);writeValue(stream,value,type);stream.finish();}
    public <T> void writeAsStreaming(Path output,T value,TypeRef<T> type) throws IOException {try(Json5StreamWriter stream=writer.stream(output)){writeValue(stream,value,type);}}
    public <T> void writeAsStreaming(Path output,T value,Class<T> type) throws IOException {try(Json5StreamWriter stream=writer.stream(output)){writeValue(stream,value,type);}}

    /** Destination-first aliases of the incremental APIs. Errors can leave partial output. */
    public void writeStreamingTo(Path output, Object value) throws IOException { writeStreaming(output, value); }
    public void writeStreamingTo(Appendable output, Object value) throws IOException { writeStreaming(value, output); }
    public void writeStreamingTo(OutputStream output, Object value) throws IOException { writeStreaming(value, output); }
    public <T> void writeStreamingTo(Path output, T value, Class<T> type) throws IOException { writeAsStreaming(output, value, type); }
    public <T> void writeStreamingTo(Path output, T value, TypeRef<T> type) throws IOException { writeAsStreaming(output, value, type); }
    public <T> void writeStreamingTo(Appendable output, T value, Class<T> type) throws IOException { writeAsStreaming(value, type, output); }
    public <T> void writeStreamingTo(Appendable output, T value, TypeRef<T> type) throws IOException { writeAsStreaming(value, type, output); }
    public <T> void writeStreamingTo(OutputStream output, T value, Class<T> type) throws IOException { writeAsStreaming(value, type, output); }
    public <T> void writeStreamingTo(OutputStream output, T value, TypeRef<T> type) throws IOException { writeAsStreaming(value, type, output); }

    @SuppressWarnings("unchecked")
    public <T> T convert(Object value, Class<T> type) {
        return (T) readRoot(value,Objects.requireNonNull(type,"type"));
    }

    @SuppressWarnings("unchecked")
    public <T> T convert(Object value, TypeRef<T> type) {
        return (T) readRoot(value,Objects.requireNonNull(type,"type").getType());
    }

    /** Converts Java objects to core JSON5 values, including annotation comments. */
    public Object toValue(Object value) { return writeRoot(value,value==null?Object.class:value.getClass()); }
    public <T> Object toValue(T value, TypeRef<T> type) {
        return writeRoot(value,Objects.requireNonNull(type,"type").getType());
    }
    public <T> Object toValue(T value, Class<T> type) {
        return writeRoot(value,Objects.requireNonNull(type,"type"));
    }

    Object readRoot(Object value,Type type) {
        MappingSession conversion=new MappingSession(this);
        if(objectGraph)value=conversion.graph.open(value);
        return conversion.read(value,type,Collections.<TypeVariable<?>,Type>emptyMap(),"$",0);
    }
    private Object writeRoot(Object value,Type type) {
        MappingSession conversion=new MappingSession(this);
        Object root=conversion.write(value,type,Collections.<TypeVariable<?>,Type>emptyMap(),"$",0);
        return objectGraph?conversion.graph.envelope(root):root;
    }

    Codec<?> readCodec(Type type, Class<?> raw, Map<TypeVariable<?>,Type> variables, String path) {
        if (codecs.isEmpty()) return null;
        Codec<?> exact = type instanceof Class ? codecs.get(type)
                : hasGenericCodecs ? codecs.get(TypeKeys.of(substitute(type, variables, path))) : null;
        return exact != null ? exact : codecs.get(raw);
    }

    Codec<?> writeCodec(Type type, Class<?> runtime, Map<TypeVariable<?>,Type> variables, String path) {
        if (codecs.isEmpty()) return null;
        Class<?> declared = raw(type, variables, path);
        Codec<?> exact = readCodec(type, declared, variables, path);
        if (exact != null) return exact;
        exact = codecs.get(runtime);
        if (exact != null) return exact;
        CodecMatch match = runtimeCodecs.get(runtime);
        if (match.ambiguous) throw error(path, "Ambiguous codecs for " + runtime.getName());
        return match.codec;
    }

    private CodecMatch matchRuntimeCodec(Class<?> runtime) {
        List<Class<?>> matches = new ArrayList<Class<?>>();
        for (Type key : codecs.keySet()) {
            if (key instanceof Class && ((Class<?>)key).isAssignableFrom(runtime)) matches.add((Class<?>)key);
        }
        Class<?> selected = null;
        for (Class<?> candidate : matches) {
            boolean mostSpecific = true;
            for (Class<?> other : matches) if (!other.isAssignableFrom(candidate)) mostSpecific = false;
            if (mostSpecific) selected = candidate;
        }
        if (selected == null) return matches.isEmpty() ? CodecMatch.NONE : CodecMatch.AMBIGUOUS;
        return new CodecMatch(codecs.get(selected),false);
    }

    private static final class CodecMatch {
        static final CodecMatch NONE = new CodecMatch(null,false);
        static final CodecMatch AMBIGUOUS = new CodecMatch(null,true);
        final Codec<?> codec;
        final boolean ambiguous;
        CodecMatch(Codec<?> codec, boolean ambiguous) { this.codec=codec; this.ambiguous=ambiguous; }
    }

    Model model(Class<?> type, String path) {
        try { return models.get(type); }
        catch (RuntimeException e) { throw new MappingException(path, "Cannot inspect " + type.getName() + ": " + e.getMessage(), e); }
    }

    static final class Model extends ObjectModel {
        Model(Class<?> type, NamingStrategy naming, boolean beans, Set<Field> statics) { super(type,naming,beans,statics); }
    }

}
