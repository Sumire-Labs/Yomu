package com.sumirelabs.yomu.mapper;

/**
 * Converts a user type to/from core JSON5 values. Null is handled by the mapper.
 * Implementations shared between threads must be thread-safe.
 */
public interface Codec<T> {
    T decode(Object value, CodecContext context);
    /** Return JSON5 values; use context.toValue for nested user objects. */
    Object encode(T value, CodecContext context);
}
