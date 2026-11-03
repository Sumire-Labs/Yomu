package com.sumirelabs.yomu;

import java.io.*;
import java.util.*;

/** Pull reader with a fixed input buffer, a bounded nesting stack and one current scalar token.
 * Caller-provided readers are borrowed. Instances are not thread-safe. */
public final class Json5StreamReader implements Closeable {
    /** The next syntactic event. Comments and whitespace are discarded. */
    public enum Token { BEGIN_OBJECT,END_OBJECT,BEGIN_ARRAY,END_ARRAY,NAME,STRING,NUMBER,BOOLEAN,NULL,END_DOCUMENT }
    private static final int ROOT=0,DONE=1,KEY=2,KEY_COMMA=3,COLON=4,OBJECT_VALUE=5,OBJECT_DONE=6,ARRAY=7,ARRAY_COMMA=8,ARRAY_DONE=9;
    private final Reader input;
    private final boolean owned;
    private final Json5ReadOptions options;
    private final StreamBuffers work;
    private final char[] buffer;
    private final byte[] stack;
    private int cursor,limit,depth,offset,line=1,column=1;
    private boolean eof,closed,previousCr;
    private Token token;
    private Object scalar;
    private int disabledLiterals;
    private boolean released;
    private int decodedEnd;
    private boolean decodedLines;

    public Json5StreamReader(Reader input) { this(input,Json5ReadOptions.DEFAULT); }
    public Json5StreamReader(Reader input,Json5ReadOptions options) { this(input,options,false); }
    Json5StreamReader(Reader input,Json5ReadOptions options,boolean owned) {
        this.input=Objects.requireNonNull(input,"input");this.options=Objects.requireNonNull(options,"options");this.owned=owned;
        work=StreamBuffers.acquire();buffer=work.input;stack=work.stack;stack[0]=ROOT;
    }
    public int getOffset(){return offset;}
    public int getLine(){return line;}
    public int getColumn(){return column;}
    public Json5ReadOptions getReadOptions(){return options;}
    public Token peek() throws IOException {
        ensureOpen();return token!=null?token:nextToken();
    }
    private Token nextToken() throws IOException {
        int c=skip(),state=stack[depth];
        if(state==DONE) {
            if(c!=-1)throw error("Unexpected content after root value");
            token=Token.END_DOCUMENT;releaseBuffers();return token;
        }
        if(state==OBJECT_DONE || state==ARRAY_DONE) {
            if(c==(state==OBJECT_DONE?'}':']')){take();return token=state==OBJECT_DONE?Token.END_OBJECT:Token.END_ARRAY;}
            if(c!=',')throw error("Expected ','");
            advance(1,false);state=state==OBJECT_DONE?KEY_COMMA:ARRAY_COMMA;stack[depth]=(byte)state;c=skip();
        }
        if(state==KEY || state==KEY_COMMA) {
            if(c=='}') {
                if(state==KEY_COMMA && options.isStrictJson())throw error("Trailing object comma is not allowed in JSON");
                take();return token=Token.END_OBJECT;
            }
            scalar=c=='"'||c=='\''?quoted(true,c):identifier();return token=Token.NAME;
        }
        if(state==COLON){if(c!=':')throw error("Expected ':'");advance(1,false);stack[depth]=OBJECT_VALUE;c=skip();} // Continue directly with the object value.
        if(state==ARRAY || state==ARRAY_COMMA) {
            if(c==']') {
                if(state==ARRAY_COMMA && options.isStrictJson())throw error("Trailing array comma is not allowed in JSON");
                take();return token=Token.END_ARRAY;
            }
        }
        if(c=='{'){take();return token=Token.BEGIN_OBJECT;}
        if(c=='['){take();return token=Token.BEGIN_ARRAY;}
        if(c=='"'||c=='\''){scalar=quoted(false,c);return token=Token.STRING;}
        scalar=unquoted();return token=scalar==null?Token.NULL:scalar instanceof Boolean?Token.BOOLEAN:Token.NUMBER;
    }
    public void beginObject() throws IOException { begin(Token.BEGIN_OBJECT,KEY); }
    public void beginArray() throws IOException { begin(Token.BEGIN_ARRAY,ARRAY); }
    private void begin(Token expected,int state) throws IOException {
        require(expected);
        if(depth>=options.getMaxDepth())throw error("Maximum nesting depth exceeded");
        complete();stack[++depth]=(byte)state;
    }
    public void endObject() throws IOException { end(Token.END_OBJECT); }
    public void endArray() throws IOException { end(Token.END_ARRAY); }
    private void end(Token expected) throws IOException {require(expected);depth--;token=null;scalar=null;}
    public boolean hasNext() throws IOException {
        if(depth==0)throw new IllegalStateException("hasNext requires an open container");
        Token next=peek();return next!=Token.END_OBJECT && next!=Token.END_ARRAY;
    }
    public String nextName() throws IOException {require(Token.NAME);String name=(String)scalar;token=null;scalar=null;stack[depth]=COLON;return name;}
    public String nextString() throws IOException {require(Token.STRING);String value=(String)scalar;complete();return value;}
    public Number nextNumber() throws IOException {require(Token.NUMBER);Number value=(Number)scalar;complete();return value;}
    public boolean nextBoolean() throws IOException {require(Token.BOOLEAN);boolean value=(Boolean)scalar;complete();return value;}
    public void nextNull() throws IOException {require(Token.NULL);complete();}
    /** Materializes only the next value. Use tokens or skipValue to avoid retaining a value tree. */
    public Object readValue() throws IOException {
        switch(peek()) {
            case BEGIN_OBJECT:
                Map<String,Object> map=new LinkedHashMap<String,Object>(4);beginObject();
                while(hasNext()){
                    String name=nextName();
                    Token next=peek();
                    if(next==Token.STRING||next==Token.NUMBER||next==Token.BOOLEAN||next==Token.NULL) {
                        Object value=scalar;complete();map.put(name,value);
                    } else map.put(name,readValue());
                }endObject();return map;
            case BEGIN_ARRAY:
                List<Object> list=new ArrayList<Object>();beginArray();while(hasNext())list.add(readValue());endArray();return list;
            case STRING:case NUMBER:case BOOLEAN:case NULL:
                Object value=scalar;complete();return value;
            default:throw error("Expected a value");
        }
    }
    /** Consumes one value without building its containers. A single scalar can still be arbitrarily large. */
    public void skipValue() throws IOException {
        int nesting=0;
        do {
            switch(peek()) {
                case BEGIN_OBJECT:beginObject();nesting++;break;case BEGIN_ARRAY:beginArray();nesting++;break;
                case END_OBJECT:if(nesting==0)throw error("Expected a value");endObject();nesting--;break;
                case END_ARRAY:if(nesting==0)throw error("Expected a value");endArray();nesting--;break;
                case NAME:if(nesting==0)throw error("Expected a value");nextName();break;case STRING:nextString();break;case NUMBER:nextNumber();break;
                case BOOLEAN:nextBoolean();break;case NULL:nextNull();break;default:throw error("Expected a value");
            }
        }while(nesting!=0);
    }
    /** Requires one completed root and validates all trailing input. Does not close the input. */
    public void requireEnd() throws IOException {require(Token.END_DOCUMENT);}
    private void require(Token expected) throws IOException {if(token!=expected&&peek()!=expected)throw error("Expected "+expected+", got "+token);}
    private void complete() {
        int state=stack[depth];
        stack[depth]=(byte)(state==ROOT?DONE:state==OBJECT_VALUE?OBJECT_DONE:ARRAY_DONE);token=null;scalar=null;
    }
    private Object quoted(boolean key,int first) throws IOException {
            int start=cursor;
            if(options.isStrictJson()&&first!='"')return slowLex(key);
            int slot=-1;
            if(limit-start>2) {
                slot=(buffer[start+1]*31+buffer[start+2])&31;
                if((disabledLiterals&(1<<slot))!=0)slot=-1;
                else {
                    int length=work.literalLengths[slot];
                    if(length>0&&length<=limit-start) {
                        int from=slot*128,i=0;
                        while(i<length&&buffer[start+i]==work.raw[from+i])i++;
                        if(i==length){advance(length,false);return work.literals[slot];}
                        disabledLiterals|=1<<slot;slot=-1;
                    }
                }
            }
            boolean lines=false;int hash=0;
            for(int i=start+1;i<limit;i++) {
                char c=buffer[i];if(c=='\u2028'||c=='\u2029')lines=true;
                if(c==first) {
                    String value=shared(buffer,start+1,i-start-1,key,hash);
                    return finishQuoted(start,i+1,slot,lines,value);
                }
                if(c=='\\') {
                    String value=decode(start+1,i,first,key,lines);
                    if(value==null)return streamedQuoted(key);
                    return finishQuoted(start,decodedEnd,slot,decodedLines,value);
                }
                if(c=='\r'||c=='\n'||options.isStrictJson()&&c<32)return slowLex(key);
                if(key)hash=hash*31+c;
            }
            return quotedTail(key);
    }
    private Object quotedTail(boolean key) throws IOException {
        if(!key&&extendToken())return boundaryString();
        return streamedQuoted(key);
    }
    private Object boundaryString() throws IOException {
        // Lookup only: the old boundary fallback never populated or invalidated raw slots.
        int start=cursor;
        if(limit-start>2) {
            int slot=(buffer[start+1]*31+buffer[start+2])&31,length=work.literalLengths[slot];
            if((disabledLiterals&(1<<slot))==0&&length>0&&length<=limit-start) {
                int from=slot*128,i=0;
                while(i<length&&buffer[start+i]==work.raw[from+i])i++;
                if(i==length){advance(length,false);return work.literals[slot];}
            }
        }
        // Refill can bring a complete string into the fixed buffer. Decode it
        // there before constructing the general Parser fallback and its copies.
        int quote=buffer[start];boolean lines=false;
        for(int i=start+1;i<limit;i++) {
            char c=buffer[i];if(c=='\u2028'||c=='\u2029')lines=true;
            if(c==quote) {
                String value=new String(buffer,start+1,i-start-1);
                advance(i+1-start,lines);return value;
            }
            if(c=='\\') {
                String value=decode(start+1,i,quote,false,lines);
                if(value==null)break;
                advance(decodedEnd-start,decodedLines);return value;
            }
            if(c=='\r'||c=='\n'||options.isStrictJson()&&c<32)break;
        }
        return streamedQuoted(false);
    }
    private boolean extendToken() throws IOException {
        int available=limit-cursor;
        if(eof||available==buffer.length)return false;
        look(available);return limit>available;
    }
    private String finishQuoted(int start,int end,int slot,boolean lines,String value) throws IOException {
        int length=end-start;
        if(slot>=0&&!lines&&length<=128&&value.length()<=64) {
            System.arraycopy(buffer,start,work.raw,slot*128,length);work.literalLengths[slot]=length;work.literals[slot]=value;
        }
        advance(length,lines);return value;
    }
    private Object unquoted() throws IOException {
        int start=cursor;
        int end=start;
        while(end<limit) {
            char c=buffer[end];
            if(c==','||c==':'||c=='{'||c=='}'||c=='['||c==']'||c=='/'||space(c))break;
            end++;
        }
        if(end==limit||end==start)return slowLex(false);
        int size=end-start;
        if(size==4&&buffer[start]=='t'&&buffer[start+1]=='r'&&buffer[start+2]=='u'&&buffer[start+3]=='e'){advance(size,false);return Boolean.TRUE;}
        if(size==5&&buffer[start]=='f'&&buffer[start+1]=='a'&&buffer[start+2]=='l'&&buffer[start+3]=='s'&&buffer[start+4]=='e'){advance(size,false);return Boolean.FALSE;}
        if(size==4&&buffer[start]=='n'&&buffer[start+1]=='u'&&buffer[start+2]=='l'&&buffer[start+3]=='l'){advance(size,false);return null;}
        // Fast path only for unambiguous, bounded decimal integers. All other number grammar stays in Parser.
        int digits=start;boolean negative=buffer[digits]=='-';
        if(negative||buffer[digits]=='+')digits++;
        if(digits<end && size<=18 && (!options.isStrictJson()||buffer[start]!='+') && (end-digits==1||buffer[digits]!='0')) {
            long n=0;int p=digits;
            while(p<end&&buffer[p]>='0'&&buffer[p]<='9'){n=n*10+buffer[p]-'0';p++;}
            if(p==end){advance(size,false);if(negative&&n==0)return Double.valueOf(-0.0);return Long.valueOf(negative?-n:n);}
        }
        return slowLex(false);
    }
    private String identifier() throws IOException {
        int start=cursor;
        if(start==limit)return (String)slowLex(true);
        int first=buffer[start];
        if(options.isStrictJson()||first>=128||!Json5Lexical.identifierStart(first))return (String)slowLex(true);
        int slot=first&31,previous=work.literalLengths[slot],size=-previous;
        if(size>0&&size<limit-start) {
            char next=buffer[start+size];
            if(next==':'||next<=32||next=='/') {
                int at=slot*128,i=0;
                while(i<size&&buffer[start+i]==work.raw[at+i])i++;
                if(i==size){advance(size,false);return work.literals[slot];}
            }
        }
        int end=start+1,hash=first;
        while(end<limit) {
            char c=buffer[end];int lower=c|32;
            if(lower>='a'&&lower<='z'||c>='0'&&c<='9'||c=='_'||c=='$'){hash=hash*31+c;end++;}
            else break;
        }
        if(end==limit)return boundaryIdentifier();
        char c=buffer[end];
        if(c!=':'&&c!=','&&c!='{'&&c!='}'&&c!='['&&c!=']'&&c!='/'&&!space(c))return (String)slowLex(true);
        String value=shared(buffer,start,end-start,true,hash);
        if(end-start<=64&&(previous<=0||(disabledLiterals&(1<<slot))!=0)) {
            System.arraycopy(buffer,start,work.raw,slot*128,end-start);
            work.literalLengths[slot]=start-end;work.literals[slot]=value;
        }
        advance(end-start,false);return value;
    }
    private String boundaryIdentifier() throws IOException {
        if(!extendToken())return (String)slowLex(true);
        // The first character was validated before refill. Retry once, then use the linear fallback.
        int start=cursor,end=start,hash=0;
        while(end<limit) {
            char c=buffer[end];int lower=c|32;
            if(lower>='a'&&lower<='z'||c>='0'&&c<='9'||c=='_'||c=='$'){hash=hash*31+c;end++;}
            else break;
        }
        if(end==limit)return (String)slowLex(true);
        char c=buffer[end];
        if(c!=':'&&c!=','&&c!='{'&&c!='}'&&c!='['&&c!=']'&&c!='/'&&!space(c))return (String)slowLex(true);
        int length=end-start;
        String value;
        if(length<=64) {
            int slot=(hash^(hash>>>16))&(work.keys.length-2);
            String first=work.keys[slot],second=work.keys[slot+1];
            value=shared(buffer,start,length,true,hash);
            // Match the old fallback's insertion policy, including an empty second slot.
            if(value!=first&&value!=second){work.keys[slot+1]=first;work.keys[slot]=value;}
        }else value=new String(buffer,start,length);
        advance(length,false);return value;
    }
    private String decode(int from,int escaped,int quote,boolean key,boolean lines) {
        int count=escaped-from;
        char[] decoded=work.decoded;
        if(count>decoded.length)return null;
        System.arraycopy(buffer,from,decoded,0,count);
        for(int i=escaped;i<limit;) {
            int run=i;
            while(i<limit) {
                char c=buffer[i];
                if(c==quote||c=='\\'||c<32&&(options.isStrictJson()||c=='\r'||c=='\n'))break;
                if((c&~1)==0x2028)lines=true;
                i++;
            }
            int length=i-run;
            if(length>decoded.length-count)return null;
            if(length!=0){System.arraycopy(buffer,run,decoded,count,length);count+=length;}
            if(i==limit)return null;
            char c=buffer[i++];
            if(c!='\\') {
                if(c!=quote)return null;
                decodedEnd=i;decodedLines=lines;
                int hash=0;if(key)for(int j=0;j<count;j++)hash=hash*31+decoded[j];
                return shared(decoded,0,count,key,hash);
            }
            {
                if(i==limit)return null;c=buffer[i++];
                if(c=='\r'||c=='\n'||c=='\u2028'||c=='\u2029')lines=true;
                if(options.isStrictJson()&&!Json5Lexical.jsonEscape(c))return null;
                switch(c) {
                    case '\n':case '\u2028':case '\u2029':continue;
                    case '\r':if(i<limit&&buffer[i]=='\n')i++;continue;
                    case 'b':c='\b';break;case 'f':c='\f';break;case 'n':c='\n';break;
                    case 'r':c='\r';break;case 't':c='\t';break;case 'v':c='\u000b';break;
                    case 'x':case 'u': {
                        int digits=c=='x'?2:4,n=0;
                        if(i+digits>limit)return null;
                        for(int j=0;j<digits;j++){int digit=Json5Lexical.hexDigit(buffer[i++]);if(digit<0)return null;n=n*16+digit;}
                        c=(char)n;break;
                    }
                    case '0':if(i<limit&&buffer[i]>='0'&&buffer[i]<='9')return null;c=0;break;
                    default:if(c>='0'&&c<='9')return null;
                }
            }
            if(count==decoded.length)return null;
            decoded[count++]=c;
        }
        return null;
    }
    private String shared(char[] chars,int start,int length,boolean key,int hash) {
        if(length==0)return "";
        if(!key||length>64)return new String(chars,start,length);
        String[] cache=work.keys;
        int slot=(hash^(hash>>>16))&(cache.length-2);
        for(int cell=slot;cell<slot+2;cell++) {
            String value=cache[cell];if(value==null||value.length()!=length)continue;
            int i=0;while(i<length&&chars[start+i]==value.charAt(i))i++;if(i==length)return value;
        }
        String value=new String(chars,start,length);
        if(cache[slot]==null)cache[slot]=value;else if(cache[slot+1]==null)cache[slot+1]=value;else{cache[slot+1]=cache[slot];cache[slot]=value;}return value;
    }
    private void advance(int count,boolean lines) throws IOException {
        if(lines||(long)offset+count>Integer.MAX_VALUE){for(int i=0;i<count;i++)take();return;}
        cursor+=count;offset+=count;column+=count;previousCr=false;
    }
    /** Decode a quoted token across refills without retaining a second encoded copy. */
    private String streamedQuoted(boolean key) throws IOException {
        int quote=look(0);
        if(options.isStrictJson()&&quote!='"')throw error("JSON strings must be double-quoted");
        take();StringBuilder decoded=new StringBuilder(64);
        while(look(0)!=-1) {
            int start=cursor,end=start;boolean lines=false;
            while(end<limit) {
                char c=buffer[end];
                if(c==quote||c=='\\'||c<32&&(options.isStrictJson()||c=='\r'||c=='\n'))break;
                if(c=='\u2028'||c=='\u2029')lines=true;
                end++;
            }
            if(end>start){decoded.append(buffer,start,end-start);advance(end-start,lines);}
            if(cursor==limit)continue;
            int c=take();
            if(c==quote) {
                String value=decoded.toString();
                if(key&&value.length()<=64) {
                    String[] cache=work.keys;int slot=(value.hashCode()^(value.hashCode()>>>16))&(cache.length-2);
                    if(value.equals(cache[slot]))return cache[slot];
                    if(value.equals(cache[slot+1]))return cache[slot+1];
                    cache[slot+1]=cache[slot];cache[slot]=value;
                }
                return value;
            }
            if(c=='\r'||c=='\n')throw error("Unescaped line break in string");
            if(c!='\\')throw error("Unescaped control character in JSON string");
            c=take();if(c<0)throw error("Unterminated escape");
            if(options.isStrictJson()&&!Json5Lexical.jsonEscape((char)c))throw error("Invalid JSON string escape");
            switch(c) {
                case '\n':case '\u2028':case '\u2029':continue;
                case '\r':if(look(0)=='\n')take();continue;
                case 'b':c='\b';break;case 'f':c='\f';break;case 'n':c='\n';break;
                case 'r':c='\r';break;case 't':c='\t';break;case 'v':c='\u000b';break;
                case 'x':case 'u': {
                    int digits=c=='x'?2:4,n=0;
                    for(int i=0;i<digits;i++) {
                        int hex=take();if(hex<0)throw error("Incomplete hexadecimal escape");
                        int digit=Json5Lexical.hexDigit((char)hex);if(digit<0)throw error("Invalid hexadecimal escape");
                        n=n*16+digit;
                    }
                    c=n;break;
                }
                case '0':if(look(0)>='0'&&look(0)<='9')throw error("Octal escapes are forbidden");c=0;break;
                default:if(c>='0'&&c<='9')throw error("Numeric escapes are forbidden");
            }
            decoded.append((char)c);
        }
        throw error("Unterminated string");
    }

    private Object slowLex(boolean key) throws IOException {
        int startOffset=offset,startLine=line,startColumn=column;
        StringBuilder raw=new StringBuilder();int first=look(0);
        if(first=='"'||first=='\'') {
            int quote=take();raw.append((char)quote);boolean escaped=false,finished=false;
            while(look(0)!=-1) {
                int c=take();raw.append((char)c);
                if(!escaped && c==quote){finished=true;break;}
                if(!escaped && c=='\\')escaped=true;else escaped=false;
            }
            if(!finished)throw error("Unterminated string");
        }else {
            while(look(0)!=-1) {
                int c=look(0);
                if(space(c)||c==','||c==':'||c=='{'||c=='}'||c=='['||c==']'||c=='/')break;
                raw.append((char)take());
            }
        }
        if(raw.length()==0)throw error(key?"Expected an object key":"Expected a value");
        String text=raw.toString();
        try {
            Parser parser=new Parser(text,options.getMaxDepth(),options.isBigDecimals(),options.isStrictJson());
            Object value=key?parser.parseKeyLiteral():parser.parse();
            if(key && value instanceof String && ((String)value).length()<=64) {
                String string=(String)value;String[] cache=work.keys;int slot=(string.hashCode()^(string.hashCode()>>>16))&(cache.length-2);
                if(string.equals(cache[slot]))return cache[slot];if(string.equals(cache[slot+1]))return cache[slot+1];cache[slot+1]=cache[slot];cache[slot]=string;
            }
            return value;
        }catch(Json5Exception cause) {
            int row=startLine,col=startColumn;boolean cr=false;
            for(int i=0;i<cause.getOffset();i++) {
                char c=text.charAt(i);
                if(c=='\r'){row++;col=1;cr=true;}else if(c=='\n'){if(!cr)row++;col=1;cr=false;}
                else if(c=='\u2028'||c=='\u2029'){row++;col=1;cr=false;}else{col++;cr=false;}
            }
            Json5Exception error=new Json5Exception(cause.reason(),startOffset+cause.getOffset(),row,col);error.initCause(cause);throw error;
        }
    }
    private boolean space(int c) {
        if(options.isStrictJson())return c==' '||c=='\t'||c=='\r'||c=='\n';
        return c==' '||c>='\t'&&c<='\r'||c==0xa0||c==0xfeff||c==0x2028||c==0x2029||c>127&&Json5Unicode.space(c);
    }
    private int skip() throws IOException {
        int c=look(0);
        // Printable ASCII other than '/' cannot begin whitespace or a comment in either mode.
        return c>32&&c<127&&c!='/'?c:skipTrivia();
    }
    private int skipTrivia() throws IOException {
        while(true) {
            int c=look(0);
            if(space(c)){take();continue;}
            if(options.isStrictJson()||c!='/')return c;
            int next=look(1);
            if(next=='/') {
                take();take();while((c=look(0))!=-1&&c!='\r'&&c!='\n'&&c!=0x2028&&c!=0x2029)take();
            }else if(next=='*') {
                take();take();while(true){c=look(0);if(c==-1)throw error("Unterminated block comment");if(c=='*'&&look(1)=='/'){take();take();break;}take();}
            }else return c;
        }
    }
    private int look(int ahead) throws IOException {
        return cursor+ahead<limit?buffer[cursor+ahead]:refill(ahead);
    }
    private int refill(int ahead) throws IOException {
        if(cursor+ahead>=limit && !eof) {
            int remaining=limit-cursor;System.arraycopy(buffer,cursor,buffer,0,remaining);cursor=0;limit=remaining;
            while(limit<=ahead && !eof) {
                int count=input.read(buffer,limit,buffer.length-limit);
                if(count<0)eof=true;else if(count==0){int c=input.read();if(c<0)eof=true;else buffer[limit++]=(char)c;}else limit+=count;
            }
        }
        return cursor+ahead<limit?buffer[cursor+ahead]:-1;
    }
    private int take() throws IOException {
        int c=look(0);if(c<0)return c;
        if(offset==Integer.MAX_VALUE)throw error("UTF-16 offset exceeds supported range");
        cursor++;offset++;
        if(c=='\r'){line++;column=1;previousCr=true;}else if(c=='\n'){if(!previousCr)line++;column=1;previousCr=false;}
        else if(c==0x2028||c==0x2029){line++;column=1;previousCr=false;}else{column++;previousCr=false;}
        return c;
    }
    private Json5Exception error(String message){return new Json5Exception(message,offset,line,column);}
    private void ensureOpen(){if(closed)throw new IllegalStateException("Reader is closed");}
    private void releaseBuffers(){if(!released){released=true;work.release();}}
    public void close() throws IOException {if(!closed){closed=true;scalar=null;token=null;try{if(owned)input.close();}finally{releaseBuffers();}}}
}
