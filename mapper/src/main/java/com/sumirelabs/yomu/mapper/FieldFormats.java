package com.sumirelabs.yomu.mapper;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParsePosition;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.Date;
import java.util.Locale;

/** Immutable per-field adapters. Mutable DecimalFormat instances are cloned per call. */
final class FieldFormats {
    private FieldFormats() { }

    static Codec<?> create(final Class<?> type, ObjectModel.Options annotation) {
        Locale locale = annotation.locale.isEmpty() ? Locale.ROOT
                : new Locale.Builder().setLanguageTag(annotation.locale).build();
        ZoneId zone = ZoneId.of(annotation.zone);
        if (isNumber(type)) return number(type, annotation.format, locale, annotation.rounding);
        if (!isTemporal(type)) throw new IllegalArgumentException("format is unsupported for " + type.getName());
        DateTimeFormatterBuilder builder = new DateTimeFormatterBuilder().appendPattern(annotation.format);
        if (hasYearOfEra(annotation.format)) builder.parseDefaulting(ChronoField.ERA, 1);
        DateTimeFormatter base = builder.toFormatter(locale).withResolverStyle(ResolverStyle.STRICT);
        final DateTimeFormatter formatter = type == Date.class || type == Instant.class ? base.withZone(zone) : base;
        return new Codec<Object>() {
            public Object decode(Object value, CodecContext context) {
                if (!(value instanceof String)) throw context.error("Expected a formatted date/time string");
                String text = (String)value;
                if (type == Date.class) return Date.from(Instant.from(formatter.parse(text)));
                if (type == Instant.class) return Instant.from(formatter.parse(text));
                if (type == LocalDate.class) return LocalDate.parse(text, formatter);
                if (type == LocalTime.class) return LocalTime.parse(text, formatter);
                if (type == LocalDateTime.class) return LocalDateTime.parse(text, formatter);
                if (type == OffsetDateTime.class) return OffsetDateTime.parse(text, formatter);
                if (type == OffsetTime.class) return OffsetTime.parse(text, formatter);
                if (type == ZonedDateTime.class) return ZonedDateTime.parse(text, formatter);
                if (type == Year.class) return Year.parse(text, formatter);
                if (type == YearMonth.class) return YearMonth.parse(text, formatter);
                return MonthDay.parse(text, formatter);
            }
            public Object encode(Object value, CodecContext context) {
                return formatter.format(type == Date.class ? Instant.ofEpochMilli(((Date)value).getTime())
                        : (TemporalAccessor)value);
            }
        };
    }

    private static boolean hasYearOfEra(String pattern) {
        boolean quoted = false;
        for (int i=0;i<pattern.length();i++) {
            char c=pattern.charAt(i);
            if (c == '\'') {
                if (i+1 < pattern.length() && pattern.charAt(i+1) == '\'') i++;
                else quoted = !quoted;
            } else if (!quoted && c == 'y') return true;
        }
        return false;
    }

    static boolean isNumber(Class<?> type) {
        return type == byte.class || type == short.class || type == int.class || type == long.class
                || type == float.class || type == double.class || type == Byte.class || type == Short.class
                || type == Integer.class || type == Long.class || type == Float.class || type == Double.class
                || type == BigDecimal.class || type == java.math.BigInteger.class || type == Number.class;
    }

    private static boolean isTemporal(Class<?> type) {
        return type == Date.class || type == Instant.class || type == LocalDate.class || type == LocalTime.class
                || type == LocalDateTime.class || type == OffsetDateTime.class || type == OffsetTime.class
                || type == ZonedDateTime.class || type == Year.class || type == YearMonth.class || type == MonthDay.class;
    }

    private static Codec<Number> number(final Class<?> type, String pattern, Locale locale, java.math.RoundingMode rounding) {
        final DecimalFormat prototype = new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(locale));
        prototype.setParseBigDecimal(true);
        prototype.setRoundingMode(rounding);
        return new Codec<Number>() {
            public Number decode(Object value, CodecContext context) {
                if (!(value instanceof String)) throw context.error("Expected a formatted numeric string");
                String text = (String)value;
                ParsePosition position = new ParsePosition(0);
                Number result = ((DecimalFormat)prototype.clone()).parse(text, position);
                if (!(result instanceof BigDecimal) || position.getIndex() != text.length())
                    throw context.error("Invalid formatted number");
                return (Number)MappingValues.number(result, type, context.getPath());
            }
            public Object encode(Number value, CodecContext context) {
                if ((value instanceof Double || value instanceof Float) &&
                        (Double.isInfinite(value.doubleValue()) || Double.isNaN(value.doubleValue())))
                    throw context.error("Formatted numbers must be finite");
                return ((DecimalFormat)prototype.clone()).format(value);
            }
        };
    }
}
