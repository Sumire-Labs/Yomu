package com.sumirelabs.yomu;

import java.math.*;
import java.util.*;

/** A detached Map.Entry or live object-key view. A live view can represent a missing key. */
public final class Json5Entry implements Map.Entry<String,Object> {
    private final String key,path;
    private final Json5Object owner;
    private Object value;
    public Json5Entry(String key,Object value) {
        this.key=Objects.requireNonNull(key,"key");this.value=value;owner=null;path=Json5Values.child("$",key);
    }
    Json5Entry(Json5Object owner,String key,String path) { this.owner=owner;this.key=key;this.path=path; }
    @Override public String getKey() { return key; }
    @Override public Object getValue() { return owner==null?value:owner.get(key); }
    @Override public Object setValue(Object value) {
        if(owner!=null)return owner.put(key,value);
        Object previous=this.value;this.value=value;return previous;
    }
    public boolean exists() { return owner==null || owner.containsKey(key); }
    public String getPath() { return path; }
    private Object required() {
        if(!exists())throw new Json5AccessException(path,"Missing key");return getValue();
    }
    public String asString() { return Json5Values.string(required(),path); }
    public boolean asBoolean() { return Json5Values.bool(required(),path); }
    public Number asNumber() { return Json5Values.number(required(),path); }
    public int asInt() { return (int)Json5Values.integral(required(),Integer.MIN_VALUE,Integer.MAX_VALUE,path); }
    public long asLong() { return Json5Values.integral(required(),Long.MIN_VALUE,Long.MAX_VALUE,path); }
    public double asDouble() { return Json5Values.floating(required(),path); }
    public BigInteger asBigInteger() { return Json5Values.integer(required(),path); }
    public BigDecimal asBigDecimal() { return Json5Values.decimal(required(),path); }
    public Json5Object asObject() { return Json5Values.object(required(),path); }
    public Json5Array asArray() { return Json5Values.array(required(),path); }
    @Override public boolean equals(Object other) {
        if(!(other instanceof Map.Entry))return false;
        Map.Entry<?,?> entry=(Map.Entry<?,?>)other;return Objects.equals(key,entry.getKey()) && Objects.equals(getValue(),entry.getValue());
    }
    @Override public int hashCode() { return Objects.hashCode(key)^Objects.hashCode(getValue()); }
    @Override public String toString() { return key+"="+getValue(); }
}
