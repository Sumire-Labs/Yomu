package com.sumirelabs.yomu.mapper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Configures a field, accessor or creator parameter. Annotating a non-public member opts it in. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
public @interface Json5Field {
    String name() default "";
    String comment() default "";
    boolean ignore() default false;
    /** Explicitly includes a static field in output only. Input can never assign it. */
    boolean staticOutput() default false;
    /** Whether input may assign this field. Disabled fields are recognized but skipped. */
    boolean read() default true;
    /** Whether this field appears in output. */
    boolean write() default true;
    /** Whether a readable creator parameter must be supplied. Ordinary fields retain defaults. */
    boolean required() default true;
    /** Additional literal input names; output always uses the primary name. */
    String[] aliases() default {};
    /** DateTimeFormatter or DecimalFormat pattern. Formatted values are JSON strings. */
    String format() default "";
    /** BCP 47 language tag for format; empty means Locale.ROOT. */
    String locale() default "";
    /** Time zone for format, used by Instant and Date (UTC by default). */
    String zone() default "UTC";
    /** Numeric output rounding; the default rejects loss of precision. */
    java.math.RoundingMode rounding() default java.math.RoundingMode.UNNECESSARY;
    /** Explicit field codec; cannot be combined with format. */
    Class<? extends Codec<?>> codec() default DefaultCodec.class;

    /** Annotation sentinel; not a usable codec. */
    final class DefaultCodec implements Codec<Object> {
        private DefaultCodec() { }
        public Object decode(Object value, CodecContext context) { throw new UnsupportedOperationException(); }
        public Object encode(Object value, CodecContext context) { throw new UnsupportedOperationException(); }
    }
}
