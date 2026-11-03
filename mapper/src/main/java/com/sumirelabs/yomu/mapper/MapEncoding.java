package com.sumirelabs.yomu.mapper;

/** Wire representation of Java Map values. Does not change the JSON/JSON5 grammar. */
public enum MapEncoding {
    /** Default JSON object representation; keys must be Strings. */
    OBJECT,
    /** Array of [key,value] pairs. A declared {@code Map<K,V>} supplies the restored key/value types. */
    ENTRY_ARRAY
}
