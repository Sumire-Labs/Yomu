package com.sumirelabs.yomu.mapper;

import java.util.Objects;
import java.util.function.Function;

/** Helpers for composing explicit codecs without adding parser dependencies. */
public final class Codecs {
    private Codecs() { }

    /** Builds a codec whose JSON representation is a string. Null bypasses the codec. */
    public static <T> Codec<T> string(final Function<String,T> decoder, final Function<T,String> encoder) {
        Objects.requireNonNull(decoder,"decoder");
        Objects.requireNonNull(encoder,"encoder");
        return new Codec<T>() {
            public T decode(Object value, CodecContext context) {
                if (!(value instanceof String)) throw context.error("Expected a string");
                return decoder.apply((String)value);
            }
            public Object encode(T value, CodecContext context) {
                return Objects.requireNonNull(encoder.apply(value),"String codec encoder returned null");
            }
        };
    }
}
