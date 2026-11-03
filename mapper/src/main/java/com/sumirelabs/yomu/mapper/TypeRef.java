package com.sumirelabs.yomu.mapper;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/** Captures a generic target: {@code new TypeRef<List<Config>>() { }}. */
public abstract class TypeRef<T> {
    private final Type type;

    protected TypeRef() {
        Type parent = getClass().getGenericSuperclass();
        if (!(parent instanceof ParameterizedType) || ((ParameterizedType) parent).getRawType() != TypeRef.class) {
            throw new IllegalArgumentException("Use a direct, parameterized TypeRef subclass");
        }
        type = ((ParameterizedType) parent).getActualTypeArguments()[0];
    }

    public final Type getType() { return type; }
}
