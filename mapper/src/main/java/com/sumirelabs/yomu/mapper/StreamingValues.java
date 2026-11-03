package com.sumirelabs.yomu.mapper;

import com.sumirelabs.yomu.Json5StreamReader;
import java.io.*;
import java.math.*;
import java.nio.file.*;
import java.util.*;

/** Operation-local, disk-backed wire values. No views escape a successful mapping operation. */
final class StreamingValues implements Closeable {
    private static final int NIL=0,FALSE=1,TRUE=2,STRING=3,LONG=4,DOUBLE=5,BIGINT=6,DECIMAL=7,OBJECT=8,ARRAY=9;
    private final Path file;
    private final RandomAccessFile input;
    private final byte[] buffer=new byte[8192];
    private long base=-1,position;
    private int available;
    private final Map<Long,Object> pinned=new HashMap<Long,Object>();
    private boolean closed;
    private StreamingValues(Path file) throws IOException {this.file=file;input=new RandomAccessFile(file.toFile(),"r");}
    interface View { void pin(); }
    private static final class TempFiles {static final String PREFIX="yomu-stream-"+UUID.randomUUID()+"-";}
    static String tempPrefix(){return TempFiles.PREFIX;}
    static void pin(Object value){if(value instanceof View)((View)value).pin();}
    static boolean isView(Object value){return value instanceof View;}
    static Object materialize(Object value) {
        if(value instanceof Map) {
            Map<String,Object> out=new LinkedHashMap<String,Object>();
            for(Map.Entry<?,?> e:((Map<?,?>)value).entrySet())out.put((String)e.getKey(),materialize(e.getValue()));return out;
        }
        if(value instanceof List){List<Object> out=new ArrayList<Object>();for(Object item:(List<?>)value)out.add(materialize(item));return out;}
        return value;
    }
    static final class Failure extends RuntimeException {
        private static final long serialVersionUID=1;
        Failure(IOException cause){super(cause);}
    }
    static StreamingValues spool(Json5StreamReader reader,boolean requireEnd) throws IOException {
        Path file=Files.createTempFile(tempPrefix()+"values-",".bin");boolean success=false;
        try {
            try(Counting output=new Counting(file)){copy(reader,output);if(requireEnd)reader.requireEnd();}
            StreamingValues result=new StreamingValues(file);success=true;return result;
        }finally{if(!success)Files.deleteIfExists(file);}
    }
    Object root(){return value(0);}
    private static void copy(Json5StreamReader reader,Counting out) throws IOException {
        DataOutputStream data=out.data;
        switch(reader.peek()) {
            case BEGIN_OBJECT: {
                long start=out.count;data.writeByte(OBJECT);data.writeLong(0);data.writeInt(0);data.writeLong(0);
                // Only this object's keys and offsets are kept, never its child values. Duplicate keys retain first order and last value.
                Map<String,Long> members=new LinkedHashMap<String,Long>();reader.beginObject();
                while(reader.hasNext()){String key=reader.nextName();long child=out.count;copy(reader,out);members.put(key,child);}reader.endObject();
                long directory=out.count;
                for(Map.Entry<String,Long> entry:members.entrySet()){writeString(data,entry.getKey());data.writeLong(entry.getValue());}
                out.patch(start,members.size(),directory);break;
            }
            case BEGIN_ARRAY: {
                long start=out.count;data.writeByte(ARRAY);data.writeLong(0);data.writeInt(0);int count=0;reader.beginArray();
                while(reader.hasNext()){if(count==Integer.MAX_VALUE)throw new IOException("Array size exceeds supported range");copy(reader,out);count++;}reader.endArray();
                out.patch(start,count,-1);break;
            }
            case STRING:data.writeByte(STRING);writeString(data,reader.nextString());break;
            case BOOLEAN:data.writeByte(reader.nextBoolean()?TRUE:FALSE);break;
            case NULL:reader.nextNull();data.writeByte(NIL);break;
            case NUMBER: {
                Number n=reader.nextNumber();
                if(n instanceof Long){data.writeByte(LONG);data.writeLong(n.longValue());}
                else if(n instanceof Double){data.writeByte(DOUBLE);data.writeLong(Double.doubleToRawLongBits(n.doubleValue()));}
                else if(n instanceof BigInteger){data.writeByte(BIGINT);writeString(data,n.toString());}
                else {BigDecimal decimal=(BigDecimal)n;data.writeByte(DECIMAL);data.writeInt(decimal.scale());writeString(data,decimal.unscaledValue().toString());}break;
            }
            default:throw new IllegalStateException("Expected stream value");
        }
    }
    private static void putLong(byte[] bytes,int offset,long value){for(int i=0;i<8;i++)bytes[offset+i]=(byte)(value>>>(56-i*8));}
    private static void writeString(DataOutputStream out,String text) throws IOException {
        out.writeInt(text.length());for(int i=0;i<text.length();i++)out.writeChar(text.charAt(i));
    }
    private static final class Counting extends OutputStream {
        final RandomAccessFile file;
        final DataOutputStream data=new DataOutputStream(this);
        final byte[] buffer=new byte[8192];
        byte[] header;
        long count,base;int used;
        Counting(Path path) throws IOException{file=new RandomAccessFile(path.toFile(),"rw");}
        public void write(int value) throws IOException{if(used==buffer.length)flush();buffer[used++]=(byte)value;count++;}
        public void write(byte[] bytes,int offset,int length) throws IOException {
            if(length>=buffer.length){flush();file.write(bytes,offset,length);base+=length;count+=length;return;}
            if(length>buffer.length-used)flush();System.arraycopy(bytes,offset,buffer,used,length);used+=length;count+=length;
        }
        public void flush() throws IOException{if(used!=0){file.write(buffer,0,used);base+=used;used=0;}}
        void patch(long start,int size,long directory) throws IOException {
            int length=directory<0?12:20;
            long offset=start+1;
            if(offset>=base && offset-base<=used-length) {
                int at=(int)(offset-base);putLong(buffer,at,count);
                for(int i=0;i<4;i++)buffer[at+8+i]=(byte)(size>>>(24-i*8));
                if(directory>=0)putLong(buffer,at+12,directory);
            } else {
                if(header==null)header=new byte[20];
                flush();putLong(header,0,count);for(int i=0;i<4;i++)header[8+i]=(byte)(size>>>(24-i*8));
                if(directory>=0)putLong(header,12,directory);
                file.seek(offset);file.write(header,0,length);file.seek(count);
            }
        }
        public void close() throws IOException{try{flush();}finally{file.close();}}
    }
    private int byteValue() throws IOException {
        if(closed)throw new IOException("Spool is closed");
        if(position<base || position>=base+available){input.seek(position);base=position;available=input.read(buffer);if(available<0)throw new EOFException("Truncated spool");}
        return buffer[(int)(position++-base)]&255;
    }
    private int intValue() throws IOException{return byteValue()<<24|byteValue()<<16|byteValue()<<8|byteValue();}
    private long longValue() throws IOException{return (long)intValue()<<32|intValue()&0xffffffffL;}
    private String string() throws IOException {int length=intValue();char[] chars=new char[length];for(int i=0;i<length;i++)chars[i]=(char)(byteValue()<<8|byteValue());return new String(chars);}
    private Object value(long offset) {
        try {
            Object existing=pinned.get(offset);if(existing!=null)return existing;
            position=offset;int type=byteValue();
            switch(type) {
                case NIL:return null;case FALSE:return Boolean.FALSE;case TRUE:return Boolean.TRUE;case STRING:return string();
                case LONG:return longValue();case DOUBLE:return Double.longBitsToDouble(longValue());case BIGINT:return new BigInteger(string());
                case DECIMAL:int scale=intValue();return new BigDecimal(new BigInteger(string()),scale);
                case OBJECT:case ARRAY: {
                    long end=longValue();int count=intValue();long directory=type==OBJECT?longValue():position;
                    return type==OBJECT?new ObjectView(offset,count,directory):new ArrayView(offset,count,directory,end);
                }
                default:throw new IOException("Invalid spool tag");
            }
        }catch(IOException e){throw new Failure(e);}
    }
    private long after(long offset) {
        try {
            position=offset;int type=byteValue();
            switch(type) {
                case OBJECT:case ARRAY:return longValue();case NIL:case FALSE:case TRUE:return position;
                case LONG:case DOUBLE:return position+8;case DECIMAL:position+=4;case STRING:case BIGINT:int length=intValue();return position+(long)length*2;
                default:throw new IOException("Invalid spool tag");
            }
        }catch(IOException e){throw new Failure(e);}
    }
    private final class ObjectView extends AbstractMap<String,Object> implements View {
        final long offset,directory;final int count;
        ObjectView(long offset,int count,long directory){this.offset=offset;this.count=count;this.directory=directory;}
        public void pin(){pinned.put(offset,this);}
        public int size(){return count;}
        public Set<Entry<String,Object>> entrySet(){return new AbstractSet<Entry<String,Object>>() {
            public int size(){return count;}
            public Iterator<Entry<String,Object>> iterator(){return new Iterator<Entry<String,Object>>() {
                int index;long cursor=directory;
                public boolean hasNext(){return index<count;}
                public Entry<String,Object> next(){if(!hasNext())throw new NoSuchElementException();try {
                    position=cursor;String key=string();long child=longValue();cursor=position;index++;return new SimpleImmutableEntry<String,Object>(key,value(child));
                }catch(IOException e){throw new Failure(e);}}
                public void remove(){throw new UnsupportedOperationException();}
            };}
        };}
    }
    private final class ArrayView extends AbstractList<Object> implements View {
        final long offset,start,end;final int count;
        ArrayView(long offset,int count,long start,long end){this.offset=offset;this.count=count;this.start=start;this.end=end;}
        public void pin(){pinned.put(offset,this);}
        public int size(){return count;}
        public Object get(int index){if(index<0||index>=count)throw new IndexOutOfBoundsException();long cursor=start;for(int i=0;i<index;i++)cursor=after(cursor);return value(cursor);}
        public Iterator<Object> iterator(){return new Iterator<Object>() {
            int index;long cursor=start;
            public boolean hasNext(){return index<count;}
            public Object next(){if(!hasNext())throw new NoSuchElementException();long current=cursor;cursor=after(cursor);index++;return value(current);}
            public void remove(){throw new UnsupportedOperationException();}
        };}
    }
    public void close() throws IOException {if(!closed){closed=true;try{input.close();}finally{pinned.clear();Files.deleteIfExists(file);}}}
}
