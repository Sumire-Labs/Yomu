package com.sumirelabs.yomu.mapper;

import java.lang.reflect.*;
import java.math.RoundingMode;
import java.util.*;

/** Cached object metadata. Record APIs are accessed reflectively to retain Java 8 linkage. */
class ObjectModel {
    private static final Object[] NO_ARGUMENTS=new Object[0];
    private static final boolean HANDLE_FIELD_ACCESS=methodHandleReflection();

    private static boolean methodHandleReflection() {
        try {
            String version=System.getProperty("java.specification.version","1.8");
            return !version.startsWith("1.") && Integer.parseInt(version)>=18;
        } catch(SecurityException | NumberFormatException unavailable) {return false;}
    }
    final Map<String,Property> fields = new LinkedHashMap<String,Property>();
    final Map<String,Property> inputs = new HashMap<String,Property>();
    final Set<String> ignored = new HashSet<String>();
    boolean hasAliases, hasFieldCodecs;
    final boolean simpleBindings;
    final Executable creator;
    final Property[] parameters;
    final Constructor<?> constructor;
    final Exception constructionFailure;

    ObjectModel(Class<?> type, NamingStrategy naming, boolean beans, Set<Field> registeredStatics) {
        if (type.getName().startsWith("java.")) throw new IllegalArgumentException("Unsupported JDK type");
        Constructor<?> noArgs = null;
        Exception failure = null;
        try {
            if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) throw new InstantiationException("Abstract type");
            noArgs = type.getDeclaredConstructor(); noArgs.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException e) { failure = e; }
        constructor = noArgs; constructionFailure = failure;
        LinkedHashMap<String,Property> properties = new LinkedHashMap<String,Property>();
        List<Class<?>> hierarchy = hierarchy(type);
        Object[] components = Records.components(type);
        Set<String> recordNames = new HashSet<String>();
        if (components != null) for (Object c:components) recordNames.add(Records.name(c));
        for (Class<?> c:hierarchy) {
            Field[] declared = c.getDeclaredFields();
            Arrays.sort(declared, Comparator.comparing(Field::getName));
            for (Field f:declared) {
                int m=f.getModifiers();
                if (Modifier.isStatic(m) || Modifier.isTransient(m) || f.isSynthetic()) continue;
                Json5Field a=f.getAnnotation(Json5Field.class);
                if (!Modifier.isPublic(m) && a==null && !recordNames.contains(f.getName())) continue;
                Property p=property(properties,f.getName());
                if (p.field!=null) throw new IllegalArgumentException("Hidden mapped field: "+f.getName());
                p.field=f; p.options.merge(a);
            }
            Method[] methods=c.getDeclaredMethods();
            Arrays.sort(methods, Comparator.comparing(Method::toGenericString));
            for (Method method:methods) {
                if (recordNames.contains(method.getName()) && method.getParameterCount()==0) continue;
                addMethod(properties,method,beans);
            }
        }
        // Include inherited public interface/default methods without depending on java.beans.
        Set<Class<?>> interfaces=new HashSet<Class<?>>();
        boolean simple = hierarchy.size() == 1;
        for (Class<?> c:hierarchy) {
            Class<?>[] parents = c.getInterfaces();
            if (parents.length != 0) simple = false;
            for (Class<?> parent:parents) addInterfaceMethods(properties,parent,beans,interfaces);
        }
        simpleBindings = simple;
        if (components!=null) for (Object component:components) {
            Property p=property(properties,Records.name(component));
            Method accessor=Records.accessor(component);
            p.getter=accessor; p.options.merge(accessor.getAnnotation(Json5Field.class));
        }
        Executable selected=null;
        for (Constructor<?> c:type.getDeclaredConstructors()) if (c.isAnnotationPresent(Json5Creator.class)) {
            if (selected!=null) throw new IllegalArgumentException("Multiple Json5Creator members"); selected=c;
        }
        for (Method m:type.getDeclaredMethods()) if (m.isAnnotationPresent(Json5Creator.class)) {
            if (selected!=null) throw new IllegalArgumentException("Multiple Json5Creator members");
            if (!Modifier.isStatic(m.getModifiers()) || !type.isAssignableFrom(m.getReturnType()))
                throw new IllegalArgumentException("Creator factory must be static and return "+type.getName());
            selected=m;
        }
        boolean canonical=selected==null && components!=null;
        if (canonical) {
            Class<?>[] types=new Class<?>[components.length];
            for (int i=0;i<types.length;i++) types[i]=Records.type(components[i]);
            try { selected=type.getDeclaredConstructor(types); }
            catch (NoSuchMethodException e) { throw new IllegalArgumentException("Missing record canonical constructor",e); }
        }
        creator=selected;
        parameters=creator==null ? new Property[0] : new Property[creator.getParameterCount()];
        if (creator!=null) {
            creator.setAccessible(true);
            Parameter[] args=creator.getParameters();
            for (int i=0;i<args.length;i++) {
                Json5Field a=args[i].getAnnotation(Json5Field.class);
                String javaName=canonical ? Records.name(components[i]) : args[i].isNamePresent() ? args[i].getName() : null;
                Property p=null;
                if (javaName!=null) p=properties.get(javaName);
                if (a!=null && !a.name().isEmpty()) {
                    Property match=null;
                    for (Property candidate:properties.values()) {
                        String name=candidate.options.name.isEmpty() ? naming.translate(candidate.id) : candidate.options.name;
                        if (a.name().equals(name)) {
                            if (match!=null) throw new IllegalArgumentException("Ambiguous creator parameter: "+a.name());
                            match=candidate;
                        }
                    }
                    if (p!=null && match!=null && p!=match) throw new IllegalArgumentException("Conflicting creator parameter: "+a.name());
                    if (match!=null) p=match;
                }
                if (p==null) {
                    String id=javaName!=null ? javaName : a!=null && !a.name().isEmpty() ? a.name() : null;
                    if (id==null) throw new IllegalArgumentException("Creator parameter "+i+" requires @Json5Field(name=...) or -parameters");
                    p=property(properties,id);
                }
                if (p.parameterIndex>=0) throw new IllegalArgumentException("Duplicate creator parameter: "+p.id);
                p.parameterIndex=i; p.parameterType=args[i].getParameterizedType();
                p.parameterClass=args[i].getType(); p.options.merge(a); parameters[i]=p;
            }
        }
        Set<String> names=new HashSet<String>();
        List<Property> allProperties=new ArrayList<Property>(properties.values());
        allProperties.addAll(staticProperties(type,registeredStatics));
        for (Property p:allProperties) {
            List<String> all=finishProperty(p,naming,names);
            if (p.options.ignore) { ignored.addAll(all); continue; }
            fields.put(p.name,p);
            for (String name:all) inputs.put(name,p);
            hasAliases |= all.size()>1; hasFieldCodecs |= p.codec!=null;
        }
    }

    private static List<Class<?>> hierarchy(Class<?> type) {
        List<Class<?>> hierarchy=new ArrayList<Class<?>>();
        for(Class<?> c=type;c!=null && c!=Object.class;c=c.getSuperclass())hierarchy.add(c);
        Collections.reverse(hierarchy);return hierarchy;
    }

    /** Static-only output never inspects creators/accessors or constructs the owning class. */
    static Map<String,Property> staticModel(Class<?> type,NamingStrategy naming,Set<Field> registered) {
        Map<String,Property> fields=new LinkedHashMap<String,Property>();
        Set<String> names=new HashSet<String>();
        for(Property p:staticProperties(type,registered)) {
            finishProperty(p,naming,names);
            if(!p.options.ignore)fields.put(p.name,p);
        }
        return Collections.unmodifiableMap(fields);
    }

    private static List<Property> staticProperties(Class<?> type,Set<Field> registered) {
        List<Property> properties=new ArrayList<Property>();
        for(Class<?> c:hierarchy(type)) {
            Field[] fields=c.getDeclaredFields();
            Arrays.sort(fields,Comparator.comparing(Field::getName));
            for(Field field:fields) {
                if(!Modifier.isStatic(field.getModifiers()) || field.isSynthetic())continue;
                Json5Field annotation=field.getAnnotation(Json5Field.class);
                if((annotation==null || !annotation.staticOutput()) && !registered.contains(field))continue;
                Property p=new Property(field.getName());p.field=field;p.options.merge(annotation);properties.add(p);
            }
        }
        return properties;
    }

    private static List<String> finishProperty(Property p,NamingStrategy naming,Set<String> names) {
        p.finish(naming);
        List<String> all=new ArrayList<String>();all.add(p.name);all.addAll(p.options.aliases);
        for(String name:all)if(name==null || name.isEmpty() || !names.add(name))
            throw new IllegalArgumentException("Duplicate or empty mapped name: "+name);
        return all;
    }

    private static Property property(Map<String,Property> properties,String name) {
        Property p=properties.get(name);
        if (p==null) { p=new Property(name); properties.put(name,p); }
        return p;
    }

    private static void addMethod(Map<String,Property> properties,Method method,boolean beans) {
        if (method.isSynthetic() || method.isBridge() || method.getDeclaringClass()==Object.class) return;
        Json5Field annotation=method.getAnnotation(Json5Field.class);
        if (annotation==null && (!beans || !Modifier.isPublic(method.getModifiers()))) return;
        if (Modifier.isStatic(method.getModifiers())) {
            if (annotation!=null) throw new IllegalArgumentException("Static property accessor: "+method);
            return;
        }
        String n=method.getName(); String id=null; boolean getter=false;
        if (method.getParameterCount()==0 && method.getReturnType()!=void.class) {
            getter=true;
            if (n.startsWith("get") && n.length()>3) id=decap(n.substring(3));
            else if (n.startsWith("is") && n.length()>2 && method.getReturnType()==boolean.class) id=decap(n.substring(2));
        } else if (method.getParameterCount()==1 && (annotation!=null || method.getReturnType()==void.class)) {
            if (n.startsWith("set") && n.length()>3) id=decap(n.substring(3));
        }
        if (id==null && annotation!=null) {
            if (method.getParameterCount()==0 && method.getReturnType()!=void.class) { getter=true; id=n; }
            else if (method.getParameterCount()==1) id=n;
            else throw new IllegalArgumentException("Expected getter or setter: "+method);
        }
        if (id==null) return;
        Property p=property(properties,id);
        Method old=getter ? p.getter : p.setter;
        if (old!=null && !old.equals(method)) {
            if (old.getName().equals(method.getName()) && method.getDeclaringClass().isAssignableFrom(old.getDeclaringClass())
                    && overrides(method,old)) { p.options.merge(annotation); return; }
            if (!getter && old.getParameterTypes()[0]!=method.getParameterTypes()[0])
                if (!old.getDeclaringClass().isAssignableFrom(method.getDeclaringClass()) || !overrides(old,method))
                    throw new IllegalArgumentException("Overloaded setter: "+id);
            if (getter && !old.getName().equals(method.getName())) {
                if (old.getReturnType()!=boolean.class || method.getReturnType()!=boolean.class)
                    throw new IllegalArgumentException("Ambiguous getter: "+id);
                // JavaBeans boolean isX takes precedence over getX.
                if (old.getName().startsWith("is")) { p.options.merge(annotation); return; }
            } else if (method.getDeclaringClass().isAssignableFrom(old.getDeclaringClass())) {
                p.options.merge(annotation); return;
            }
        }
        if (getter) p.getter=method; else p.setter=method;
        p.options.merge(annotation);
    }

    private static void addInterfaceMethods(Map<String,Property> properties,Class<?> type,boolean beans,Set<Class<?>> visited) {
        if (!visited.add(type)) return;
        for(Class<?> parent:type.getInterfaces()) addInterfaceMethods(properties,parent,beans,visited);
        Method[] methods=type.getDeclaredMethods();
        Arrays.sort(methods,Comparator.comparing(Method::toGenericString));
        for(Method method:methods) addMethod(properties,method,beans);
    }

    private static boolean overrides(Method base,Method derived) {
        if(Arrays.equals(base.getParameterTypes(),derived.getParameterTypes())) return true;
        for(Method bridge:derived.getDeclaringClass().getDeclaredMethods()) {
            if(bridge.isBridge() && bridge.getName().equals(derived.getName())
                    && Arrays.equals(bridge.getParameterTypes(),base.getParameterTypes()))return true;
        }
        return false;
    }

    private static String decap(String name) {
        if (name.length()>1 && Character.isUpperCase(name.charAt(0)) && Character.isUpperCase(name.charAt(1))) return name;
        return Character.toLowerCase(name.charAt(0))+name.substring(1);
    }

    Object newInstance(String path) {
        if (constructionFailure!=null) throw new MappingException(path,"Accessible no-argument constructor required",constructionFailure);
        try { return constructor.newInstance(NO_ARGUMENTS); }
        catch (ReflectiveOperationException e) { throw invocation(path,"Constructor failed",e); }
    }

    Object create(Object[] args,String path) {
        try {
            Object result=creator instanceof Constructor ? ((Constructor<?>)creator).newInstance(args) : ((Method)creator).invoke(null,args);
            if (result==null || !creator.getDeclaringClass().isInstance(result)) throw new MappingException(path,"Creator returned null or an incompatible type");
            return result;
        } catch (MappingException e) { throw e; }
        catch (ReflectiveOperationException | IllegalArgumentException e) { throw invocation(path,"Creator failed",e); }
    }

    private static MappingException invocation(String path,String message,Exception error) {
        Throwable cause=error instanceof InvocationTargetException ? ((InvocationTargetException)error).getCause() : error;
        return new MappingException(path,message,cause);
    }

    static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type==boolean.class) return false;
        if (type==char.class) return '\0';
        if (type==byte.class) return (byte)0;
        if (type==short.class) return (short)0;
        if (type==int.class) return 0;
        if (type==long.class) return 0L;
        if (type==float.class) return 0F;
        return 0D;
    }

    static final class Property {
        final String id;
        final Options options=new Options();
        Field field; Method getter,setter;
        Object interfaceSetter;
        int parameterIndex=-1; volatile Type parameterType; Class<?> parameterClass;
        String name,comment; boolean read,write; Codec<?> codec;
        Property(String id) { this.id=id; }
        Type inputType() {
            Type type=parameterType;
            if(type==null) {
                type=setter!=null ? setter.getGenericParameterTypes()[0]
                        : field!=null ? field.getGenericType() : getter.getGenericReturnType();
                parameterType=type;
            }
            return type;
        }
        Type outputType() { return getter!=null ? getter.getGenericReturnType() : field.getGenericType(); }
        boolean isStatic() { return field!=null && Modifier.isStatic(field.getModifiers()); }
        boolean canSet() { return !isStatic() && (setter!=null || field!=null && !Modifier.isFinal(field.getModifiers())); }
        void finish(NamingStrategy naming) {
            name=options.name.isEmpty() ? naming.translate(id) : options.name;
            if (name==null || name.isEmpty()) throw new IllegalArgumentException("Empty mapped name for "+id);
            comment=options.comment;
            if(options.staticOutput && !isStatic())throw new IllegalArgumentException("staticOutput is only valid on static fields: "+id);
            read=!isStatic() && options.read && (parameterIndex>=0 || field!=null || setter!=null);
            write=options.write && (getter!=null || field!=null);
            if (options.ignore) return;
            if (field!=null) field.setAccessible(true);
            if (getter!=null) getter.setAccessible(true);
            if (setter!=null) setter.setAccessible(true);
            if(HANDLE_FIELD_ACCESS && read && parameterIndex<0 && setter==null && field!=null && field.getType().isInterface() && canSet()) {
                try {
                    // Conversion checks the actual value type before this unchecked interface cast.
                    // Avoid the reflection adapter for every assignment to an interface field.
                    interfaceSetter=FieldSetters.create(field);
                } catch(IllegalAccessException | SecurityException inaccessible) { interfaceSetter=null; }
            }
            Class<?> input=parameterIndex>=0 ? parameterClass : setter!=null ? setter.getParameterTypes()[0]
                    : field!=null ? field.getType() : getter.getReturnType();
            Class<?> output=getter!=null ? getter.getReturnType() : field!=null ? field.getType() : input;
            if (!compatible(input,output)) throw new IllegalArgumentException("Incompatible property types: "+id);
            if (options.codec!=Json5Field.DefaultCodec.class) {
                if (!options.format.isEmpty()) throw new IllegalArgumentException("Cannot combine codec and format on "+id);
                try {
                    Constructor<?> ctor=options.codec.getDeclaredConstructor(); ctor.setAccessible(true);
                    codec=(Codec<?>)ctor.newInstance();
                } catch (ReflectiveOperationException e) { throw new IllegalArgumentException("Codec needs a no-argument constructor: "+id,e); }
            } else if (!options.format.isEmpty()) codec=FieldFormats.create(input,options);
        }
        Object get(Object value,String path) {
            try { return getter!=null ? getter.invoke(value) : field.get(isStatic()?null:value); }
            catch (ReflectiveOperationException | IllegalArgumentException e) { throw invocation(path,"Cannot read property",e); }
        }
        // Called only for a validated mutable instance field and compatible scalar,
        // and an already constructed owner. Preserve the full path on failure.
        void setDirect(Object target,Object value,String parent,String key) {
            try {field.set(target,value);}
            catch(ReflectiveOperationException | IllegalArgumentException e) {
                throw invocation(MappingValues.child(parent,key),"Cannot set property",e);
            }
        }
        void set(Object target,Object value,String path) {
            if(isStatic())throw new MappingException(path,"Static fields are output-only");
            if (!canSet()) throw new MappingException(path,"Final fields and getter-only properties are output-only");
            try {
                if(interfaceSetter!=null) {
                    if(value!=null && !field.getType().isInstance(value))throw new IllegalArgumentException("Incompatible interface field value");
                    if(target==null)throw new NullPointerException();
                    if(!field.getDeclaringClass().isInstance(target))throw new IllegalArgumentException("Incompatible field owner");
                    FieldSetters.set(interfaceSetter,target,value,path);
                } else if (setter!=null) setter.invoke(target,value); else field.set(target,value);
            }
            catch (ReflectiveOperationException | IllegalArgumentException e) { throw invocation(path,"Cannot set property",e); }
        }
        private static boolean compatible(Class<?> a,Class<?> b) {
            a=box(a); b=box(b); return a.isAssignableFrom(b) || b.isAssignableFrom(a);
        }
        private static Class<?> box(Class<?> c) {
            if(c==int.class)return Integer.class; if(c==long.class)return Long.class;
            if(c==boolean.class)return Boolean.class; if(c==char.class)return Character.class;
            if(c==double.class)return Double.class; if(c==float.class)return Float.class;
            if(c==byte.class)return Byte.class; if(c==short.class)return Short.class; return c;
        }
    }

    static final class Options {
        String name="",comment="",format="",locale="",zone="UTC";
        boolean read=true,write=true,required=true,ignore,staticOutput;
        RoundingMode rounding=RoundingMode.UNNECESSARY;
        Class<? extends Codec<?>> codec=Json5Field.DefaultCodec.class;
        final Set<String> aliases=new LinkedHashSet<String>();
        void merge(Json5Field a) {
            if(a==null)return;
            name=mergeText(name,a.name(),"name"); comment=mergeText(comment,a.comment(),"comment");
            format=mergeText(format,a.format(),"format"); locale=mergeText(locale,a.locale(),"locale");
            if(!a.zone().equals("UTC")) zone=mergeText(zone.equals("UTC") ? "" : zone,a.zone(),"zone");
            if(a.rounding()!=RoundingMode.UNNECESSARY) {
                if(rounding!=RoundingMode.UNNECESSARY && rounding!=a.rounding()) throw new IllegalArgumentException("Conflicting rounding");
                rounding=a.rounding();
            }
            if(a.codec()!=Json5Field.DefaultCodec.class) {
                if(codec!=Json5Field.DefaultCodec.class && codec!=a.codec()) throw new IllegalArgumentException("Conflicting codecs");
                codec=a.codec();
            }
            Set<String> local=new HashSet<String>();
            for(String alias:a.aliases()) {
                if(alias.isEmpty() || !local.add(alias)) throw new IllegalArgumentException("Duplicate or empty alias");
                aliases.add(alias);
            }
            read &= a.read(); write &= a.write(); required &= a.required(); ignore |= a.ignore();
            staticOutput |= a.staticOutput();
        }
        private static String mergeText(String old,String value,String label) {
            if(value.isEmpty())return old;
            if(!old.isEmpty() && !old.equals(value))throw new IllegalArgumentException("Conflicting property "+label);
            return value;
        }
    }

    private static final class Records {
        static final Method IS_RECORD, COMPONENTS;
        static {
            Method is=null,components=null;
            try { is=Class.class.getMethod("isRecord"); components=Class.class.getMethod("getRecordComponents"); }
            catch(NoSuchMethodException ignored) { }
            IS_RECORD=is; COMPONENTS=components;
        }
        static Object[] components(Class<?> type) {
            if(IS_RECORD==null)return null;
            try { return Boolean.TRUE.equals(IS_RECORD.invoke(type)) ? (Object[])COMPONENTS.invoke(type) : null; }
            catch(ReflectiveOperationException e) { throw new IllegalArgumentException("Cannot inspect record",e); }
        }
        static Object call(Object c,String name) {
            try { return c.getClass().getMethod(name).invoke(c); }
            catch(ReflectiveOperationException e) { throw new IllegalArgumentException("Cannot inspect record component",e); }
        }
        static String name(Object c) { return (String)call(c,"getName"); }
        static Class<?> type(Object c) { return (Class<?>)call(c,"getType"); }
        static Method accessor(Object c) { return (Method)call(c,"getAccessor"); }
    }
}
