package com.sumirelabs.yomu;

import java.io.*;

/** Separate process: generated input and discarded output exceed the 32 MiB heap. */
public final class StreamingMemoryProbe {
    public static void main(String[] args)throws Exception {
        final String item="\"abcdefghijklmnopqrstuvwxyz\",";final int items=1500000;
        Reader source=new Reader(){long index;final long total=1L+(long)item.length()*items;
            public int read(char[] out,int off,int len){if(index>total)return -1;int n=(int)Math.min(len,total+1-index);for(int i=0;i<n;i++,index++)out[off+i]=index==0?'[':index==total?']':item.charAt((int)((index-1)%item.length()));return n;}public void close(){}};
        int count=0;try(Json5StreamReader reader=Yomu.stream(source)){reader.beginArray();while(reader.hasNext()){if(reader.nextString().length()!=26)throw new AssertionError();count++;}reader.endArray();reader.requireEnd();}
        if(count!=items)throw new AssertionError(count);
        final long[] length={0};Appendable sink=new Appendable(){public Appendable append(char c){length[0]++;return this;}public Appendable append(CharSequence s){length[0]+=s.length();return this;}public Appendable append(CharSequence s,int start,int end){length[0]+=end-start;return this;}};
        Json5StreamWriter writer=new Json5StreamWriter(sink);writer.beginArray();for(int i=0;i<items;i++)writer.value("abcdefghijklmnopqrstuvwxyz");writer.endArray();writer.finish();
        if(length[0]<40000000)throw new AssertionError(length[0]);System.out.println("bounded heap: input/output > 40 MB");
    }
}
