package com.sumirelabs.yomu.mapper;

import java.lang.reflect.*;
import java.math.*;
import java.util.*;

/** Exact numeric conversions, container construction, and diagnostic paths. */
final class MappingValues {
    private MappingValues() { }

    static boolean numeric(Class<?> c) {
        // Ordinary POJOs must not resolve BigInteger/BigDecimal merely to rule
        // them out. Retain the exact whitelist for Number subclasses below.
        if(!c.isPrimitive() && !Number.class.isAssignableFrom(c))return false;
        return c == byte.class || c == short.class || c == int.class || c == long.class || c == float.class || c == double.class
                || c == Byte.class || c == Short.class || c == Integer.class || c == Long.class || c == Float.class || c == Double.class
                || c == Number.class || c == BigInteger.class || c == BigDecimal.class;
    }

    static Object number(Number n, Class<?> type, String path) {
        if (type == Number.class) return n;
        if (type.isInstance(n)) return n;
        if (n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte) {
            long value = n.longValue();
            if (type == long.class || type == Long.class) return value;
            if (type == int.class || type == Integer.class) {
                if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw error(path, "Number overflows int");
                return (int) value;
            }
            if (type == short.class || type == Short.class) {
                if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) throw error(path, "Number overflows short");
                return (short) value;
            }
            if (type == byte.class || type == Byte.class) {
                if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) throw error(path, "Number overflows byte");
                return (byte) value;
            }
            if (type == BigInteger.class) return BigInteger.valueOf(value);
            if (type == BigDecimal.class) return BigDecimal.valueOf(value);
        }
        if (type == double.class || type == Double.class) {
            double result = n.doubleValue();
            if (Double.isInfinite(result) && !(n instanceof Double || n instanceof Float)) throw error(path, "Number overflows double");
            return result;
        }
        if (type == float.class || type == Float.class) {
            float result = n.floatValue();
            if (Float.isInfinite(result) && !((n instanceof Double || n instanceof Float) && Double.isInfinite(n.doubleValue()))) throw error(path, "Number overflows float");
            return result;
        }
        try {
            // Integral conversion must examine the exact binary value, not its shortest decimal spelling.
            // For example, (double) Long.MIN_VALUE is exact although Double.toString rounds its spelling.
            BigDecimal decimal = n instanceof BigDecimal ? (BigDecimal) n
                    : type != BigDecimal.class && (n instanceof Double || n instanceof Float)
                    ? new BigDecimal(n.doubleValue()) : new BigDecimal(n.toString());
            if (type == BigDecimal.class) return decimal;
            if (decimal.signum()!=0 && decimal.stripTrailingZeros().scale()>0) throw new ArithmeticException("Fractional integer value");
            if (type == BigInteger.class) return decimal.toBigIntegerExact();
            // Reject bounded overflow before materializing powers of ten for enormous exponents.
            long min=type==long.class || type==Long.class ? Long.MIN_VALUE : type==int.class || type==Integer.class ? Integer.MIN_VALUE
                    : type==short.class || type==Short.class ? Short.MIN_VALUE : Byte.MIN_VALUE;
            long max=type==long.class || type==Long.class ? Long.MAX_VALUE : type==int.class || type==Integer.class ? Integer.MAX_VALUE
                    : type==short.class || type==Short.class ? Short.MAX_VALUE : Byte.MAX_VALUE;
            if(decimal.compareTo(BigDecimal.valueOf(min))<0 || decimal.compareTo(BigDecimal.valueOf(max))>0) throw new ArithmeticException("Integer overflow");
            if (type == long.class || type == Long.class) return decimal.longValueExact();
            if (type == int.class || type == Integer.class) return decimal.intValueExact();
            if (type == short.class || type == Short.class) return decimal.shortValueExact();
            return decimal.byteValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new MappingException(path, "Number cannot be represented exactly as " + type.getSimpleName(), e);
        }
    }

    private static Object construct(Class<?> type, String path) {
        if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) throw error(path, "Cannot construct " + type.getName());
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new MappingException(path, "Accessible no-argument constructor required for " + type.getName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    static Collection<Object> collection(Class<?> type, String path, int size) {
        if (type == List.class || type == Collection.class || type == ArrayList.class)
            return new ArrayList<Object>(Math.max(0,Math.min(size,1024)));
        if (type == Set.class) return new LinkedHashSet<Object>();
        if (type == SortedSet.class || type == NavigableSet.class) return new TreeSet<Object>();
        return (Collection<Object>) construct(type, path);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Class<?> type, String path) {
        if (type == Map.class) return new LinkedHashMap<String, Object>();
        if (type == SortedMap.class || type == NavigableMap.class) return new TreeMap<String, Object>();
        return (Map<String, Object>) construct(type, path);
    }

    static MappingException error(String path, String message) { return new MappingException(path, message); }

    static MappingException expected(String path, String type, Object value) {
        return error(path, "Expected " + type + ", got " + (value==null?"null":value.getClass().getSimpleName()));
    }

    static String child(String path, String key) {
        StringBuilder result = new StringBuilder(path.length() + key.length() + 4).append(path).append("[\"");
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '"' || c == '\\') result.append('\\').append(c);
            else if (c < 32 || c == '\u2028' || c == '\u2029') {
                result.append("\\u");
                for (int shift = 12; shift >= 0; shift -= 4) result.append("0123456789abcdef".charAt((c >> shift) & 15));
            } else result.append(c);
        }
        return result.append("\"]").toString();
    }

    static String index(String path, int i) { return path + "[" + i + "]"; }
}
