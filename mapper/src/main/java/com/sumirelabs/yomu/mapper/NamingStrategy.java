package com.sumirelabs.yomu.mapper;

/** Maps a Java field name to its primary JSON name. Implementations must be pure and thread-safe. */
@FunctionalInterface
public interface NamingStrategy {
    String translate(String fieldName);
}
