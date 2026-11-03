package com.sumirelabs.yomu;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/** One globally reusable scratch block; no reader, caller class or parsed text survives release. */
final class StreamBuffers {
    private static final AtomicReference<StreamBuffers> SPARE=new AtomicReference<StreamBuffers>();
    final char[] input=new char[8192];
    final byte[] stack=new byte[513];
    final char[] decoded=new char[256];
    final String[] keys=new String[32],literals=new String[32];
    final int[] literalLengths=new int[32];
    final char[] raw=new char[32*128];
    static StreamBuffers acquire(){StreamBuffers value=SPARE.getAndSet(null);return value==null?new StreamBuffers():value;}
    void release() {
        Arrays.fill(input,'\0');Arrays.fill(decoded,'\0');
        // A short literal may replace a longer one after a refill; clear its entire fixed slot.
        for(int slot=0;slot<literalLengths.length;slot++)if(literalLengths[slot]!=0)Arrays.fill(raw,slot*128,(slot+1)*128,'\0');
        Arrays.fill(keys,null);Arrays.fill(literals,null);Arrays.fill(literalLengths,0);
        stack[0]=0;SPARE.compareAndSet(null,this);
    }
}
