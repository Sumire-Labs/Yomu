package com.sumirelabs.yomu.mapper;

import java.lang.reflect.*;
import java.util.*;

import static com.sumirelabs.yomu.mapper.MappingValues.*;

/** Generic type resolution and creator inference; no per-operation state. */
final class MappingTypes {
    private MappingTypes() { }

    static Class<?> boxed(Class<?> c) {
        if(c==int.class)return Integer.class;
        if(c==long.class)return Long.class;
        if(c==boolean.class)return Boolean.class;
        if(c==double.class)return Double.class;
        if(c==float.class)return Float.class;
        if(c==short.class)return Short.class;
        if(c==byte.class)return Byte.class;
        if(c==char.class)return Character.class;
        return c;
    }

    static Type writeType(Type type, Map<TypeVariable<?>,Type> variables, Class<?> runtime, String path) {
        // Writing an erased generic POJO was already supported: infer unknown field types from values.
        try { return substitute(type,variables,path); }
        catch (MappingException e) {
            if (containsUnresolved(type)) return runtime;
            throw e;
        }
    }

    private static boolean containsUnresolved(Type type) {
        if(type instanceof TypeVariable || type instanceof WildcardType)return true;
        if(type instanceof GenericArrayType)return containsUnresolved(((GenericArrayType)type).getGenericComponentType());
        if(type instanceof ParameterizedType) {
            for(Type argument:((ParameterizedType)type).getActualTypeArguments()) if(containsUnresolved(argument))return true;
        }
        return false;
    }

    static Type resolve(Type type, Map<TypeVariable<?>, Type> variables, String path) {
        Set<Type> seen = null;
        while (type instanceof TypeVariable) {
            if (seen == null) seen = new HashSet<Type>();
            if (!seen.add(type) || !variables.containsKey(type)) throw error(path, "Unresolved type variable: " + type);
            type = variables.get(type);
        }
        if (type instanceof WildcardType) throw error(path, "Wildcard targets are not supported; use a concrete type");
        return type;
    }

    static Class<?> raw(Type type, Map<TypeVariable<?>, Type> variables, String path) {
        type = resolve(type, variables, path);
        if (type instanceof Class) return (Class<?>) type;
        if (type instanceof ParameterizedType) return (Class<?>) ((ParameterizedType) type).getRawType();
        if (type instanceof GenericArrayType) return Array.newInstance(raw(((GenericArrayType) type).getGenericComponentType(), variables, path), 0).getClass();
        throw error(path, "Unsupported target type: " + type);
    }

    static Map<TypeVariable<?>, Type> bindings(Type type, Map<TypeVariable<?>, Type> inherited, String path) {
        Map<TypeVariable<?>, Type> result = new HashMap<TypeVariable<?>, Type>(inherited);
        collectBindings(type,result,path,new HashSet<Class<?>>());
        return result;
    }

    /** A raw class without a hierarchy contributes no new bindings to an empty scope. */
    static Map<TypeVariable<?>,Type> modelBindings(ObjectModel model,Type type,Map<TypeVariable<?>,Type> inherited,String path) {
        return model.simpleBindings && type instanceof Class && inherited.isEmpty()
                ? inherited : bindings(type,inherited,path);
    }

    private static void collectBindings(Type type,Map<TypeVariable<?>,Type> result,String path,Set<Class<?>> visited) {
        if(type==null || type==Object.class)return;
        Class<?> c=raw(type,result,path);
        if(!visited.add(c))return;
        if(type instanceof ParameterizedType) {
            Type[] args=((ParameterizedType)type).getActualTypeArguments();
            TypeVariable<?>[] parameters=c.getTypeParameters();
            Type[] actual=new Type[args.length];
            for(int i=0;i<actual.length;i++) {
                // Keep erased variables unresolved until the property actually needs a concrete type.
                try { actual[i]=substitute(args[i],result,path); }
                catch(MappingException e) { if(!containsUnresolved(args[i]))throw e;actual[i]=args[i]; }
            }
            for(int i=0;i<parameters.length;i++) if(!parameters[i].equals(actual[i]))result.put(parameters[i],actual[i]);
        }
        collectBindings(c.getGenericSuperclass(),result,path,visited);
        for(Type parent:c.getGenericInterfaces())collectBindings(parent,result,path,visited);
    }

    static Type substitute(Type type, Map<TypeVariable<?>, Type> variables, String path) {
        type = resolve(type, variables, path);
        if (type instanceof ParameterizedType) {
            final ParameterizedType parameterized = (ParameterizedType) type;
            final Type[] args = parameterized.getActualTypeArguments();
            for (int i = 0; i < args.length; i++) args[i] = substitute(args[i], variables, path);
            return new ParameterizedType() {
                public Type getRawType() { return parameterized.getRawType(); }
                public Type getOwnerType() { return parameterized.getOwnerType(); }
                public Type[] getActualTypeArguments() { return args.clone(); }
            };
        }
        if (type instanceof GenericArrayType) {
            final Type component = substitute(((GenericArrayType) type).getGenericComponentType(), variables, path);
            return new GenericArrayType() {
                public Type getGenericComponentType() { return component; }
            };
        }
        return type;
    }

    static void inferFactoryBindings(Method factory, Type target, Map<TypeVariable<?>,Type> variables, String path) {
        Type returned=factory.getGenericReturnType();
        if (returned instanceof ParameterizedType && raw(returned,variables,path)==raw(target,variables,path)) {
            Type[] patterns=((ParameterizedType)returned).getActualTypeArguments();
            TypeVariable<?>[] names=factory.getReturnType().getTypeParameters();
            for (int i=0;i<patterns.length;i++) {
                Type actual=variables.get(names[i]);
                if (actual!=null) inferFactoryType(patterns[i],substitute(actual,variables,path),factory,variables,path);
            }
        }
    }

    private static void inferFactoryType(Type pattern, Type actual, Method factory, Map<TypeVariable<?>,Type> variables, String path) {
        if (pattern instanceof TypeVariable && ((TypeVariable<?>)pattern).getGenericDeclaration().equals(factory)) {
            Type previous=variables.put((TypeVariable<?>)pattern,actual);
            if (previous!=null && !TypeKeys.of(previous).equals(TypeKeys.of(actual))) throw error(path,"Conflicting factory type inference");
        } else if (pattern instanceof ParameterizedType && actual instanceof ParameterizedType) {
            ParameterizedType a=(ParameterizedType)pattern,b=(ParameterizedType)actual;
            if (!a.getRawType().equals(b.getRawType())) throw error(path,"Incompatible factory return type");
            Type[] left=a.getActualTypeArguments(),right=b.getActualTypeArguments();
            for(int i=0;i<left.length;i++) inferFactoryType(left[i],right[i],factory,variables,path);
        } else if (pattern instanceof GenericArrayType && actual instanceof GenericArrayType) {
            inferFactoryType(((GenericArrayType)pattern).getGenericComponentType(),((GenericArrayType)actual).getGenericComponentType(),factory,variables,path);
        } else if (pattern instanceof GenericArrayType && actual instanceof Class && ((Class<?>)actual).isArray())
            inferFactoryType(((GenericArrayType)pattern).getGenericComponentType(),((Class<?>)actual).getComponentType(),factory,variables,path);
        else if (!TypeKeys.of(pattern).equals(TypeKeys.of(actual))) throw error(path,"Incompatible factory return type");
    }

    static Type[] containerArguments(Type type, Class<?> container, Map<TypeVariable<?>, Type> inherited, String path) {
        Class<?> c = raw(type, inherited, path);
        TypeVariable<?>[] parameters = c.getTypeParameters();
        Type[] actual = type instanceof ParameterizedType ? ((ParameterizedType) type).getActualTypeArguments() : new Type[parameters.length];
        for (int i = 0; i < actual.length; i++) actual[i] = actual[i] == null ? Object.class : substitute(actual[i], inherited, path);
        // Direct containers need the resolved arguments, not a scope for traversing parents.
        // Preserve the original validation path for malformed ParameterizedType implementations.
        if (c == container && parameters.length == actual.length) return actual;
        Map<TypeVariable<?>, Type> local = new HashMap<TypeVariable<?>, Type>(inherited);
        for (int i = 0; i < parameters.length; i++) local.put(parameters[i], actual[i]);
        if (c == container) return actual;
        for (Type parent : c.getGenericInterfaces()) {
            if (container.isAssignableFrom(raw(parent, local, path))) return containerArguments(parent, container, local, path);
        }
        Type parent = c.getGenericSuperclass();
        if (parent != null) return containerArguments(parent, container, local, path);
        throw error(path, "Cannot resolve container type: " + type);
    }
}
