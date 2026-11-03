package com.sumirelabs.yomu.benchmark;

import java.lang.ref.Reference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;

/** Reachable Java-heap graph size; not exclusive retained size or peak heap usage. */
final class GraphMeter {
    private final IdentityHashMap<Object,Boolean> visited = new IdentityHashMap<Object,Boolean>();

    long add(Object root) throws IllegalAccessException {
        if (root == null) return 0;
        ArrayDeque<Object> pending = new ArrayDeque<Object>();
        pending.add(root);
        long bytes = 0;
        while (!pending.isEmpty()) {
            Object value = pending.removeLast();
            if (value instanceof Class || value instanceof ClassLoader || value instanceof Thread || value instanceof Reference) continue;
            if (visited.put(value, Boolean.TRUE) != null) continue;
            bytes += HeapAgent.sizeOf(value);
            Class<?> type = value.getClass();
            if (type.isArray()) {
                if (!type.getComponentType().isPrimitive()) {
                    for (int i = 0; i < Array.getLength(value); i++) {
                        Object element = Array.get(value, i);
                        if (element != null) pending.add(element);
                    }
                }
            } else {
                for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                    for (Field field : c.getDeclaredFields()) {
                        if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                        field.setAccessible(true);
                        Object referenced = field.get(value);
                        if (referenced != null) pending.add(referenced);
                    }
                }
            }
        }
        return bytes;
    }

    static Object field(Object target, String name) throws ReflectiveOperationException {
        Class<?> type = target instanceof Class ? (Class<?>) target : target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target instanceof Class ? null : target);
            } catch (NoSuchFieldException e) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }
}
