package com.sumirelabs.yomu;

import java.math.*;
import java.util.*;

/** Optional mutable List helper with live nested views and checked typed access. */
public final class Json5Array extends AbstractList<Object> {
    private final List<Object> values;
    private final String path;
    public Json5Array() { this(new ArrayList<Object>(),"$"); }
    /** Shallow copy. Nested values are shared. */
    public Json5Array(Collection<?> source) { this(new ArrayList<Object>(Objects.requireNonNull(source,"source")),"$"); }
    Json5Array(List<Object> values,String path) { this.values=Objects.requireNonNull(values,"values");this.path=path; }
    /** Creates a live view, without copying the supplied list. */
    public static Json5Array wrap(List<Object> values) { return new Json5Array(values,"$"); }
    public static Json5Array parse(String source) { return parse(source,Json5ReadOptions.DEFAULT); }
    public static Json5Array parse(String source,Json5ReadOptions options) { return Json5Values.array(Yomu.parse(source,options),"$"); }
    public List<Object> asList() { return values; }
    public Json5Array copy() { return new Json5Array((Collection<?>)values); }
    public String getPath() { return path; }
    @Override public Object get(int index) { return values.get(index); }
    @Override public int size() { return values.size(); }
    @Override public Object set(int index,Object value) { return values.set(index,value); }
    @Override public void add(int index,Object value) { values.add(index,value);modCount++; }
    @Override public Object remove(int index) { Object old=values.remove(index);modCount++;return old; }
    @Override public boolean addAll(int index,Collection<?> source) { boolean changed=values.addAll(index,source);if(changed)modCount++;return changed; }
    @Override public boolean addAll(Collection<?> source) { return addAll(size(),source); }
    @Override public void clear() { if(!values.isEmpty()){values.clear();modCount++;} }
    @Override public Iterator<Object> iterator() { return listIterator(); }
    @Override public ListIterator<Object> listIterator(int index) {
        final ListIterator<Object> iterator=values.listIterator(index);
        return new ListIterator<Object>() {
            public boolean hasNext(){return iterator.hasNext();}
            public Object next(){return iterator.next();}
            public boolean hasPrevious(){return iterator.hasPrevious();}
            public Object previous(){return iterator.previous();}
            public int nextIndex(){return iterator.nextIndex();}
            public int previousIndex(){return iterator.previousIndex();}
            public void remove(){iterator.remove();modCount++;}
            public void set(Object value){iterator.set(value);}
            public void add(Object value){iterator.add(value);modCount++;}
        };
    }
    public Json5Array addValue(Object value) { add(value);return this; }
    private String at(int index) { return Json5Values.index(path,index); }
    public String getString(int index) { return Json5Values.string(get(index),at(index)); }
    public boolean getBoolean(int index) { return Json5Values.bool(get(index),at(index)); }
    public Number getNumber(int index) { return Json5Values.number(get(index),at(index)); }
    public int getInt(int index) { return (int)Json5Values.integral(get(index),Integer.MIN_VALUE,Integer.MAX_VALUE,at(index)); }
    public long getLong(int index) { return Json5Values.integral(get(index),Long.MIN_VALUE,Long.MAX_VALUE,at(index)); }
    public double getDouble(int index) { return Json5Values.floating(get(index),at(index)); }
    public BigInteger getBigInteger(int index) { return Json5Values.integer(get(index),at(index)); }
    public BigDecimal getBigDecimal(int index) { return Json5Values.decimal(get(index),at(index)); }
    public Json5Object getObject(int index) { return Json5Values.object(get(index),at(index)); }
    public Json5Array getArray(int index) { return Json5Values.array(get(index),at(index)); }
}
