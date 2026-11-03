package com.sumirelabs.yomu.mapper;

import java.lang.reflect.*;
import java.util.Arrays;
import java.util.Objects;

/** Structural registry keys; Class identity also distinguishes different mod class loaders. */
final class TypeKeys {
    private TypeKeys() { }

    static Type of(Type type) {
        if (type instanceof Class) return type;
        if (type instanceof ParameterizedType) {
            ParameterizedType p = (ParameterizedType) type;
            Type[] args = p.getActualTypeArguments();
            for (int i = 0; i < args.length; i++) args[i] = of(args[i]);
            return new Parameterized(p.getRawType(), p.getOwnerType() == null ? null : of(p.getOwnerType()), args);
        }
        if (type instanceof GenericArrayType) return new GenericArray(of(((GenericArrayType)type).getGenericComponentType()));
        throw new IllegalArgumentException("Codec type must be concrete: " + type);
    }

    private static final class Parameterized implements ParameterizedType {
        final Type raw, owner;
        final Type[] args;
        Parameterized(Type raw, Type owner, Type[] args) { this.raw=raw; this.owner=owner; this.args=args.clone(); }
        public Type getRawType() { return raw; }
        public Type getOwnerType() { return owner; }
        public Type[] getActualTypeArguments() { return args.clone(); }
        public int hashCode() { return Arrays.hashCode(args) ^ Objects.hashCode(owner) ^ raw.hashCode(); }
        public boolean equals(Object value) {
            if (!(value instanceof ParameterizedType)) return false;
            ParameterizedType p=(ParameterizedType)value;
            return raw.equals(p.getRawType()) && Objects.equals(owner,p.getOwnerType()) && Arrays.equals(args,p.getActualTypeArguments());
        }
        public String toString() {
            StringBuilder s=new StringBuilder(raw.getTypeName()).append('<');
            for(int i=0;i<args.length;i++) { if(i>0)s.append(", "); s.append(args[i].getTypeName()); }
            return s.append('>').toString();
        }
    }

    private static final class GenericArray implements GenericArrayType {
        final Type component;
        GenericArray(Type component) { this.component=component; }
        public Type getGenericComponentType() { return component; }
        public int hashCode() { return component.hashCode(); }
        public boolean equals(Object value) {
            return value instanceof GenericArrayType && component.equals(((GenericArrayType)value).getGenericComponentType());
        }
        public String toString() { return component.getTypeName()+"[]"; }
    }
}
