package com.sumirelabs.yomu.mapper;

import java.lang.reflect.Type;

/** Per-invocation conversion context. Do not retain it after a codec returns. */
public interface CodecContext {
    String getPath();
    Type getTargetType();
    <T> T read(Object value, Class<T> type);
    <T> T read(Object value, TypeRef<T> type);
    Object toValue(Object value);
    <T> Object toValue(T value, TypeRef<T> type);
    CodecContext atField(String name);
    CodecContext atIndex(int index);
    MappingException error(String message);
}
