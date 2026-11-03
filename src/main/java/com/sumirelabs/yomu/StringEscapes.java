package com.sumirelabs.yomu;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.Buffer;
import java.nio.CharBuffer;

/** Shared string encoder for writer, public utilities and value paths. */
final class StringEscapes {
    private static final class EncodedCapabilities {
        // Java 17+ OutputStreamWriter consumes CharBuffer directly; Java 8 uses char[].
        static final boolean DIRECT_BUFFERS=directBuffers();
        private static boolean directBuffers() {
            try {
                String version=System.getProperty("java.specification.version");
                if(version==null||version.length()>3)return false;
                int major=0;
                for(int i=0;i<version.length();i++) {
                    char c=version.charAt(i);if(c<'0'||c>'9')return false;
                    major=major*10+c-'0';
                }
                return major>=17;
            }catch(SecurityException denied){return false;}
        }
        }
    private StringEscapes() { }
    static void requireQuote(char quote) {
        if (quote != '"' && quote != '\'') throw new IllegalArgumentException("Quote must be single or double");
    }
    static void quoted(String text, StringBuilder out, char quote) {
        out.append(quote);
        body(text,out,quote);
        out.append(quote);
    }
    static void body(String text, StringBuilder out, char quote) {
        int start=0;
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(!needsEscape(c,quote))continue;
            out.append(text,start,i);
            if(c==quote)out.append('\\').append(quote);
            else switch(c) {
                case '\\':out.append("\\\\");break;
                case '\n':out.append("\\n");break;
                case '\r':out.append("\\r");break;
                case '\t':out.append("\\t");break;
                case '\b':out.append("\\b");break;
                case '\f':out.append("\\f");break;
                default:
                    out.append("\\u");
                    for(int shift=12;shift>=0;shift-=4)out.append("0123456789abcdef".charAt((c>>shift)&15));
            }
            start=i+1;
        }
        out.append(text,start,text.length());
    }
    static boolean needsEscape(char c,char quote) {
        return c==quote || c=='\\' || c<32 || c=='\u2028' || c=='\u2029' || Character.isSurrogate(c);
    }
    static void quoted(String text,Appendable out,char quote) throws IOException {
        if(out instanceof OutputStreamWriter){quoted(text,out,quote,null);return;}
        quotedPlain(text,out,quote);
    }
    static void quotedPlain(String text,Appendable out,char quote) throws IOException {
        out.append(quote);int start=0;
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);if(!writerEscape(c,quote))continue;
            range(text,out,start,i);
            if(c==quote)out.append('\\').append(quote);
            else switch(c) {
                case '\\':out.append("\\\\");break;case '\n':out.append("\\n");break;case '\r':out.append("\\r");break;
                case '\t':out.append("\\t");break;case '\b':out.append("\\b");break;case '\f':out.append("\\f");break;
                default:out.append("\\u");for(int shift=12;shift>=0;shift-=4)out.append("0123456789abcdef".charAt((c>>shift)&15));
            }
            start=i+1;
        }
        range(text,out,start,text.length());out.append(quote);
    }
    static CharBuffer quoted(String text,Appendable out,char quote,CharBuffer buffer) throws IOException {
        return quotedEncoded(text,(Writer)out,quote,buffer);
    }
    private static boolean writerEscape(char c,char quote) {
        // ASCII needs only control/quote/backslash tests; Unicode needs only the other rules.
        if(c<128)return c<32||c==quote||c=='\\';
        return c=='\u2028'||c=='\u2029'||Character.isSurrogate(c);
    }
    private static void range(String text,Appendable out,int start,int end) throws IOException {
        if(start==end)return;
        if(start==0&&end==text.length()){out.append(text);return;}
        if(out instanceof Writer)((Writer)out).write(text,start,end-start);
        else out.append(text,start,end);
    }
    private static CharBuffer quotedEncoded(String text,Writer out,char quote,CharBuffer scratch) throws IOException {
        // StreamEncoder.write(String,...) creates a temporary char[] on Java 8 and 25.
        // Keep staging bounded even for a single very large scalar; no shared mutable state.
        int first=0;
        if(text.length()>1024) {
            while(first<text.length()&&!needsEscape(text.charAt(first),quote))first++;
            if(first==text.length()){out.write(quote);out.write(text);out.write(quote);return scratch;}
        }
        int capacity=text.length()<1022?Math.max(8,text.length()+2):1024;
        if(scratch==null||scratch.capacity()<capacity)scratch=CharBuffer.allocate(capacity);
        char[] buffer=scratch.array();
        int used=0;buffer[used++]=quote;
        for(int from=0;from<first;) {
            if(used==capacity){encodedWrite(out,scratch,used);used=0;}
            int length=Math.min(first-from,capacity-used);
            text.getChars(from,from+length,buffer,used);used+=length;from+=length;
        }
        // All C0 controls plus the selected ASCII quote. The upper ASCII half only escapes '\'.
        if(text.length()>1024)return quotedEncodedLong(text,out,quote,scratch,first,used,capacity);
        long asciiEscapes=0xffff_ffffL|(1L<<quote);
        for(int i=first;i<text.length();i++) {
            char c=text.charAt(i);
            boolean escaped=c<64?((asciiEscapes>>>c)&1L)!=0:c<128?c=='\\':(c|1)==0x2029||Character.isSurrogate(c);
            if(!escaped) {
                if(used==capacity){encodedWrite(out,scratch,used);used=0;}
                buffer[used++]=c;continue;
            }
            char escape;
            if(c>=32)escape=c<128?c:'u';
            // Packed bytes for controls 8..13: b, t, n, u, f, r; u retains Unicode escaping for VT.
            else if(c>=8&&c<=13)escape=(char)((0x7266756e7462L>>>((c-8)<<3))&255);
            else escape='u';
            int length=escape=='u'?6:2;
            if(capacity-used<length){encodedWrite(out,scratch,used);used=0;}
            buffer[used++]='\\';buffer[used++]=escape;
            if(escape=='u')for(int shift=12;shift>=0;shift-=4)buffer[used++]="0123456789abcdef".charAt((c>>shift)&15);
        }
        if(used==capacity){encodedWrite(out,scratch,used);used=0;}
        buffer[used++]=quote;encodedWrite(out,scratch,used);
        return scratch;
    }
    private static void encodedWrite(Writer out,CharBuffer scratch,int length) throws IOException {
        if(EncodedCapabilities.DIRECT_BUFFERS) {
            // Buffer casts select the descriptors present on Java 8 as well.
            ((Buffer)scratch).clear();((Buffer)scratch).limit(length);out.append(scratch);
        } else out.write(scratch.array(),0,length);
    }
    private static CharBuffer quotedEncodedLong(String text,Writer out,char quote,CharBuffer scratch,int first,int used,int capacity) throws IOException {
        char[] buffer=scratch.array();
        long asciiEscapes=0xffff_ffffL|(1L<<quote);
        for(int i=first;i<text.length();) {
            if(capacity-used<6){encodedWrite(out,scratch,used);used=0;}
            int end=i+Math.min(text.length()-i,(capacity-used)/6);
            for(;i<end;i++) {
                char c=text.charAt(i);
                boolean escaped=c<64?((asciiEscapes>>>c)&1L)!=0:c<128?c=='\\':(c|1)==0x2029||Character.isSurrogate(c);
                if(!escaped){buffer[used++]=c;continue;}
                char escape;
                if(c>=32)escape=c<128?c:'u';
                else if(c>=8&&c<=13)escape=(char)((0x7266756e7462L>>>((c-8)<<3))&255);
                else escape='u';
                buffer[used++]='\\';buffer[used++]=escape;
                if(escape=='u')for(int shift=12;shift>=0;shift-=4)buffer[used++]="0123456789abcdef".charAt((c>>shift)&15);
            }
        }
        if(used==capacity){encodedWrite(out,scratch,used);used=0;}
        buffer[used++]=quote;encodedWrite(out,scratch,used);return scratch;
    }
}
