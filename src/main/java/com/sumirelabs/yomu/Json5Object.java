package com.sumirelabs.yomu;

import java.math.*;
import java.util.*;

/** Optional mutable Map helper. Parsing still returns ordinary Maps unless explicitly wrapped. */
public final class Json5Object extends AbstractMap<String,Object> {
    private final Map<String,Object> values;
    private final String path;

    public Json5Object() { this(new LinkedHashMap<String,Object>(4),"$"); }
    /** Shallow copy. Nested values are shared. */
    public Json5Object(Map<String,?> source) { this();putAll(source); }
    Json5Object(Map<String,Object> source,String path) {
        values=Objects.requireNonNull(source,"source");this.path=path;
        for(Object key:source.keySet())if(!(key instanceof String))throw new Json5AccessException(path,"Object keys must be non-null Strings");
    }
    /** Creates a live view, without copying the supplied map. */
    public static Json5Object wrap(Map<String,Object> source) { return new Json5Object(source,"$"); }
    public static Json5Object parse(String source) { return parse(source,Json5ReadOptions.DEFAULT); }
    public static Json5Object parse(String source,Json5ReadOptions options) { return wrap(Yomu.parseObject(source,options)); }
    public Map<String,Object> asMap() { return values; }
    public Json5Object copy() { return new Json5Object(values); }
    public String getPath() { return path; }
    @Override public int size() { return values.size(); }
    @Override public boolean containsKey(Object key) { return values.containsKey(key); }
    @Override public Object get(Object key) { return values.get(key); }
    @Override public Object put(String key,Object value) { return values.put(Objects.requireNonNull(key,"key"),value); }
    @Override public Object remove(Object key) { return values.remove(key); }
    @Override public void clear() { values.clear(); }
    @Override public Set<Map.Entry<String,Object>> entrySet() { return values.entrySet(); }
    @Override public void putAll(Map<? extends String,?> source) {
        Objects.requireNonNull(source,"source");
        for(Object key:source.keySet())if(!(key instanceof String))throw new Json5AccessException(path,"Object keys must be non-null Strings");
        values.putAll(source);
    }
    public Json5Object with(String key,Object value) { put(key,value);return this; }
    public Json5Object withEntry(Map.Entry<String,?> entry) { Objects.requireNonNull(entry,"entry");return with(entry.getKey(),entry.getValue()); }
    /** A live entry view, including for a currently missing key. setValue may insert it. */
    public Json5Entry entry(String key) { return new Json5Entry(this,Objects.requireNonNull(key,"key"),Json5Values.child(path,key)); }
    private String at(String key) { return Json5Values.child(path,Objects.requireNonNull(key,"key")); }
    private Object required(String key) {
        if(!values.containsKey(key))throw new Json5AccessException(at(key),"Missing key");
        return values.get(key);
    }
    public String getString(String key) { return Json5Values.string(required(key),at(key)); }
    public String getString(String key,String fallback) { return containsKey(key)?getString(key):fallback; }
    public boolean getBoolean(String key) { return Json5Values.bool(required(key),at(key)); }
    public boolean getBoolean(String key,boolean fallback) { return containsKey(key)?getBoolean(key):fallback; }
    public Number getNumber(String key) { return Json5Values.number(required(key),at(key)); }
    public int getInt(String key) { return (int)Json5Values.integral(required(key),Integer.MIN_VALUE,Integer.MAX_VALUE,at(key)); }
    public int getInt(String key,int fallback) { return containsKey(key)?getInt(key):fallback; }
    public long getLong(String key) { return Json5Values.integral(required(key),Long.MIN_VALUE,Long.MAX_VALUE,at(key)); }
    public long getLong(String key,long fallback) { return containsKey(key)?getLong(key):fallback; }
    public double getDouble(String key) { return Json5Values.floating(required(key),at(key)); }
    public double getDouble(String key,double fallback) { return containsKey(key)?getDouble(key):fallback; }
    public BigInteger getBigInteger(String key) { return Json5Values.integer(required(key),at(key)); }
    public BigDecimal getBigDecimal(String key) { return Json5Values.decimal(required(key),at(key)); }
    public Json5Object getObject(String key) { return Json5Values.object(required(key),at(key)); }
    public Json5Array getArray(String key) { return Json5Values.array(required(key),at(key)); }
}
