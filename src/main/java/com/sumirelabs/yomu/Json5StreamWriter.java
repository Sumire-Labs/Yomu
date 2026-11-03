package com.sumirelabs.yomu;

import java.io.*;
import java.lang.reflect.Array;
import java.math.*;
import java.nio.CharBuffer;
import java.util.*;

/** Incremental writer. Output may be partial after an error; discard the writer after failure.
 * No value tree or complete output string is retained. Caller-provided outputs are borrowed. */
public final class Json5StreamWriter implements Closeable,Flushable {
    private final Appendable out;
    private final Closeable owner;
    private final String indent;
    private final boolean quoteKeys,expandArrays,strictJson;
    // 0: array; 1: object expecting a name; 2: object expecting a value.
    private final byte[] states=new byte[Yomu.DEFAULT_MAX_DEPTH];
    private final int[] counts=new int[Yomu.DEFAULT_MAX_DEPTH];
    private final IdentityHashMap<Object,Boolean> active=new IdentityHashMap<Object,Boolean>();
    private CharBuffer escapedBuffer;
    private int depth;
    private boolean root,closed;
    public Json5StreamWriter(Appendable output){this(output,null,"",true,true,false);}
    public boolean isStrictJson(){return strictJson;}
    Json5StreamWriter(Appendable output,Closeable owner,String indent,boolean quoteKeys,boolean expandArrays,boolean strictJson) {
        out=Objects.requireNonNull(output,"output");this.owner=owner;this.indent=indent;this.quoteKeys=quoteKeys;this.expandArrays=expandArrays;this.strictJson=strictJson;
    }
    public Json5StreamWriter beginObject() throws IOException {before();start(true);return this;}
    public Json5StreamWriter beginArray() throws IOException {before();start(false);return this;}
    private void start(boolean object) throws IOException {
        if(depth==states.length)throw new IllegalArgumentException("Maximum nesting depth exceeded");
        out.append(object?'{':'[');states[depth]=(byte)(object?1:0);counts[depth]=0;depth++;
    }
    public Json5StreamWriter endObject() throws IOException {end(true);return this;}
    public Json5StreamWriter endArray() throws IOException {end(false);return this;}
    private void end(boolean object) throws IOException {
        ensureOpen();if(depth==0 || states[depth-1]!=(object?1:0))throw new IllegalStateException("Mismatched or incomplete container");
        depth--;if(counts[depth]!=0 && (object||expandArrays))line(depth);out.append(object?'}':']');
    }
    public Json5StreamWriter name(String name) throws IOException {
        ensureOpen();Objects.requireNonNull(name,"name");
        if(depth==0||states[depth-1]!=1)throw new IllegalStateException("Expected an object name");
        if(counts[depth-1]!=0)out.append(',');line(depth);
        if(!strictJson&&!quoteKeys&&Json5Writer.identifier(name))out.append(name);else quoted(name);
        out.append(indent.isEmpty()?":":": ");states[depth-1]=2;return this;
    }
    public Json5StreamWriter value(String value) throws IOException {if(value==null)return nullValue();before();quoted(value);return this;}
    private void quoted(String value) throws IOException {
        if(out instanceof OutputStreamWriter)escapedBuffer=StringEscapes.quoted(value,out,'"',escapedBuffer);
        else StringEscapes.quotedPlain(value,out,'"');
    }
    public Json5StreamWriter value(boolean value) throws IOException {before();out.append(value?"true":"false");return this;}
    public Json5StreamWriter value(Number value) throws IOException {if(value==null)return nullValue();validateNumber(value);before();out.append(value.toString());return this;}
    public Json5StreamWriter nullValue() throws IOException {before();out.append("null");return this;}
    /** Writes a core value directly. Containers are traversed once, without a copied representation. */
    public Json5StreamWriter value(Object value) throws IOException {core(value);return this;}
    private void core(Object value) throws IOException {
        before();int wrappers=0;
        while(value instanceof CommentedValue) {
            if(wrappers++>=Yomu.DEFAULT_MAX_DEPTH)throw new IllegalArgumentException("Maximum comment depth exceeded");
            CommentedValue cv=(CommentedValue)value;if(!strictJson)commentText(cv.getComment());value=cv.getValue();
        }
        if(value==null){out.append("null");return;}
        if(value instanceof String){quoted((String)value);return;}
        if(value instanceof Boolean){out.append(value.toString());return;}
        if(value instanceof Number){validateNumber((Number)value);out.append(value.toString());return;}
        if(active.put(value,Boolean.TRUE)!=null)throw new IllegalArgumentException("Cyclic value graph");
        try {
            if(value instanceof Map) {
                start(true);for(Map.Entry<?,?> entry:((Map<?,?>)value).entrySet()) {
                    if(!(entry.getKey() instanceof String))throw new IllegalArgumentException("Object keys must be strings");
                    name((String)entry.getKey());core(entry.getValue());
                }end(true);
            }else if(value instanceof Iterable){start(false);for(Object item:(Iterable<?>)value)core(item);end(false);}
            else if(value.getClass().isArray()){start(false);for(int i=0;i<Array.getLength(value);i++)core(Array.get(value,i));end(false);}
            else throw new IllegalArgumentException("Unsupported JSON5 value: "+value.getClass().getName());
        }finally{active.remove(value);}
    }
    /** Emits a comment at the current position; a following value is still required. Strict JSON omits it. */
    public Json5StreamWriter comment(String comment) throws IOException {ensureOpen();Objects.requireNonNull(comment,"comment");if(!strictJson)commentText(comment);return this;}
    private void commentText(String text) throws IOException {
        // A block comment can safely occur before an array separator or after an object colon.
        out.append("/* ");int start=0;
        for(int i=0;i<text.length();i++)if(text.charAt(i)=='*'&&i+1<text.length()&&text.charAt(i+1)=='/') {
            out.append(text,start,i+1).append(' ');start=i+1;
        }
        out.append(text,start,text.length()).append(" */");
    }
    private void validateNumber(Number value) {
        if(!(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long||value instanceof Float||value instanceof Double||value instanceof BigInteger||value instanceof BigDecimal))
            throw new IllegalArgumentException("Unsupported Number type: "+value.getClass().getName());
        if(strictJson && ((value instanceof Float||value instanceof Double)&&(Double.isNaN(value.doubleValue())||Double.isInfinite(value.doubleValue()))
                || value instanceof BigDecimal&&value.getClass()!=BigDecimal.class || value instanceof BigInteger&&value.getClass()!=BigInteger.class))
            throw new IllegalArgumentException("Non-finite numbers and numeric subclasses cannot be written as JSON");
    }
    private void before() throws IOException {
        ensureOpen();
        if(depth==0){if(root)throw new IllegalStateException("Only one root value is permitted");root=true;return;}
        int frame=depth-1;
        if(states[frame]==2)states[frame]=1;
        else if(states[frame]==1)throw new IllegalStateException("Expected an object name");
        else{if(counts[frame]!=0)out.append(',');if(expandArrays)line(depth);else if(counts[frame]!=0&&!indent.isEmpty())out.append(' ');}
        counts[frame]++;
    }
    private void line(int level) throws IOException {if(!indent.isEmpty()){out.append('\n');for(int i=0;i<level;i++)out.append(indent);}}
    private void ensureOpen(){if(closed)throw new IllegalStateException("Writer is closed");}
    /** Verifies a complete root, then flushes without closing a borrowed output. */
    public void finish() throws IOException {ensureOpen();if(!root||depth!=0)throw new IllegalStateException("Incomplete document");flush();}
    public void flush() throws IOException {ensureOpen();if(out instanceof Flushable)((Flushable)out).flush();}
    public void close() throws IOException {if(!closed)try{finish();}finally{closed=true;if(owner!=null)owner.close();}}
}
