package com.sumirelabs.yomu.mapper;

import java.lang.reflect.*;
import java.util.*;

/** Immutable allowlist. Only Class objects supplied by the caller can be instantiated. */
final class RegisteredTypes {
    static final RegisteredTypes EMPTY=new RegisteredTypes(Collections.<String,Entry>emptyMap(),Collections.<Class<?>,Entry>emptyMap());
    final Map<String,Entry> byId;
    final Map<Class<?>,Entry> byClass;
    private RegisteredTypes(Map<String,Entry> byId,Map<Class<?>,Entry> byClass){this.byId=byId;this.byClass=byClass;}

    RegisteredTypes with(String id,Type type) {
        Objects.requireNonNull(id,"id");Objects.requireNonNull(type,"type");
        if(id.isEmpty() || !id.equals(id.trim()))throw new IllegalArgumentException("Type ID must be nonempty without surrounding whitespace");
        Type key=TypeKeys.of(type);
        Class<?> raw=key instanceof Class?(Class<?>)key:key instanceof ParameterizedType?(Class<?>)((ParameterizedType)key).getRawType():null;
        if(raw==null || raw.isPrimitive() || raw==Void.class || raw==Object.class || raw.isInterface()
                || (!raw.isArray() && Modifier.isAbstract(raw.getModifiers())) || raw.isAnonymousClass() || raw.isLocalClass()
                || (raw.isMemberClass()&&!Modifier.isStatic(raw.getModifiers())))
            throw new IllegalArgumentException("Registered type must be a concrete, independently constructible type: "+type);
        if(key instanceof Class && raw.getTypeParameters().length!=0)
            throw new IllegalArgumentException("Generic registered types require a concrete TypeRef: "+type);
        Class<?> component=raw;
        while(component.isArray())component=component.getComponentType();
        if(raw.isArray() && component.getTypeParameters().length!=0)
            throw new IllegalArgumentException("Registered array components must have concrete non-generic classes: "+type);
        Entry previous=byId.get(id),sameClass=byClass.get(raw);
        if(previous!=null && previous.type.equals(key))return this;
        if(previous!=null)throw new IllegalArgumentException("Type ID already registered: "+id);
        if(sameClass!=null)throw new IllegalArgumentException("Runtime class already registered: "+raw.getName());
        Map<String,Entry> ids=new LinkedHashMap<String,Entry>(byId);
        Map<Class<?>,Entry> classes=new LinkedHashMap<Class<?>,Entry>(byClass);
        Entry entry=new Entry(id,key,raw);ids.put(id,entry);classes.put(raw,entry);
        return new RegisteredTypes(Collections.unmodifiableMap(ids),Collections.unmodifiableMap(classes));
    }
    static final class Entry {
        final String id;
        final Type type;
        final Class<?> raw;
        Entry(String id,Type type,Class<?> raw){this.id=id;this.type=type;this.raw=raw;}
    }
}
