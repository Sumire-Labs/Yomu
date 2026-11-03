package com.sumirelabs.yomu.mapper;

import java.io.*;
import java.util.*;

/** A 32 MiB subprocess: discard a huge unknown array and emit a generated sequence of POJOs. */
public final class StreamingMapperMemoryProbe {
    public static class Item { public int id;public String name="abcdefghijklmnopqrstuvwxyz"; }
    public static void main(String[] args)throws Exception {
        final String prefix="{id:7,ignored:[",suffix="]}";final int items=1500000;
        Reader source=new Reader(){long pos;final long end=prefix.length()+2L*items;
            public int read(char[] out,int off,int len){if(pos>=end+suffix.length())return -1;int n=(int)Math.min(len,end+suffix.length()-pos);for(int i=0;i<n;i++,pos++)out[off+i]=pos<prefix.length()?prefix.charAt((int)pos):pos>=end?suffix.charAt((int)(pos-end)):(pos-prefix.length())%2==0?'0':',';return n;}public void close(){}};
        Json5Mapper mapper=new Json5Mapper();Item result=mapper.readStreaming(source,Item.class);if(result.id!=7)throw new AssertionError();
        Iterable<Item> generated=()->new Iterator<Item>(){int i;public boolean hasNext(){return i<items;}public Item next(){Item item=new Item();item.id=i++;return item;}};
        final long[] length={0};Appendable sink=new Appendable(){public Appendable append(char c){length[0]++;return this;}public Appendable append(CharSequence s){length[0]+=s.length();return this;}public Appendable append(CharSequence s,int a,int b){length[0]+=b-a;return this;}};
        mapper.writeStreaming(generated,sink);if(length[0]<60000000)throw new AssertionError(length[0]);System.out.println("bounded mapper heap: large ignored wire array, output > 60 MB");
    }
}
