package com.sumirelabs.yomu;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.*;

/** Source spans for an already validated document; never used by ordinary parsing. */
final class DocumentSyntax {
    final String source;
    private final Json5ReadOptions options;
    private final List<int[]> comments=new ArrayList<int[]>();
    private final Node root;
    private final String newline;
    private int cursor;
    private static final class Node {
        final int start;Object original;int end;
        List<Member> members;List<Node> elements;
        Node(int start,Object original){this.start=start;this.original=original instanceof Map||original instanceof List?null:original;}
    }
    private static final class Member {
        final String key;final int start,keyEnd;final Node value;
        Member(String key,int start,int keyEnd,Node value){this.key=key;this.start=start;this.keyEnd=keyEnd;this.value=value;}
    }
    DocumentSyntax(String source,Object original,Json5ReadOptions options) {
        this.source=source;this.options=options;
        newline=source.contains("\r\n")?"\r\n":source.indexOf('\r')>=0?"\r":"\n";
        trivia();root=node(original);trivia();
        if(cursor!=source.length())throw new IllegalStateException("Invalid document layout");
    }
    private Node node(Object original) {
        int start=cursor;Node node=new Node(start,original);char c=source.charAt(cursor);
        if(c=='{') {
            Map<?,?> values=original instanceof Map?(Map<?,?>)original:Collections.emptyMap();
            Map<String,Member> latest=new HashMap<String,Member>(Math.max(4,values.size()*4/3+1));node.original=latest;
            node.members=new ArrayList<Member>(values.size());
            Iterator<?> keys=values.keySet().iterator();String expected=keys.hasNext()?(String)keys.next():null;
            cursor++;trivia();
            while(source.charAt(cursor)!='}') {
                int keyStart=cursor;
                if(source.charAt(cursor)=='\''||source.charAt(cursor)=='"')quoted();
                else while(cursor<source.length()&&!space(source.charAt(cursor))&&source.charAt(cursor)!=':'&&source.charAt(cursor)!='/')cursor++;
                int keyEnd=cursor;
                String key=keyMatches(expected,keyStart,keyEnd)?expected:new Parser(source.substring(keyStart,keyEnd),options.getMaxDepth(),options.isBigDecimals(),options.isStrictJson()).parseKeyLiteral();
                if(expected!=null&&expected.equals(key)){key=expected;expected=keys.hasNext()?(String)keys.next():null;}
                trivia();cursor++;trivia();
                Object old=original instanceof Map?((Map<?,?>)original).get(key):null;
                Member member=new Member(key,keyStart,keyEnd,node(old));node.members.add(member);latest.put(key,member);trivia();
                if(source.charAt(cursor)==','){cursor++;trivia();}else break;
            }
            cursor++;
        } else if(c=='[') {
            node.original=null;node.elements=new ArrayList<Node>(original instanceof List?((List<?>)original).size():0);cursor++;trivia();int i=0;
            while(source.charAt(cursor)!=']') {
                Object old=original instanceof List && i<((List<?>)original).size()?((List<?>)original).get(i):null;
                node.elements.add(node(old));i++;trivia();
                if(source.charAt(cursor)==','){cursor++;trivia();}else break;
            }
            cursor++;
        } else if(c=='\''||c=='"')quoted();
        else while(cursor<source.length()) {
            c=source.charAt(cursor);if(space(c)||c==','||c=='}'||c==']'||c=='/')break;cursor++;
        }
        node.end=cursor;return node;
    }
    private boolean keyMatches(String expected,int start,int end) {
        if(expected==null)return false;char first=source.charAt(start);
        if(first=='\''||first=='"'){start++;end--;}
        return end-start==expected.length()&&source.regionMatches(start,expected,0,expected.length());
    }
    private void quoted() {
        char quote=source.charAt(cursor++);
        while(cursor<source.length()) {char c=source.charAt(cursor++);if(c=='\\')cursor++;else if(c==quote)return;}
        throw new IllegalStateException("Unterminated validated string");
    }
    private static boolean space(char c) {
        return c==' '||c>='\t'&&c<='\r'||c==0xa0||c==0xfeff||c==0x2028||c==0x2029||c>127&&Json5Unicode.space(c);
    }
    private void trivia() {
        while(cursor<source.length()) {
            char c=source.charAt(cursor);
            if(space(c)){cursor++;continue;}
            if(c!='/'||cursor+1==source.length())return;
            int start=cursor;char next=source.charAt(cursor+1);
            if(next=='/') {
                cursor+=2;while(cursor<source.length()&&!line(source.charAt(cursor)))cursor++;
            } else if(next=='*') {
                cursor+=2;while(!(source.charAt(cursor)=='*'&&source.charAt(cursor+1)=='/'))cursor++;cursor+=2;
            } else return;
            comments.add(new int[]{start,cursor});
        }
    }
    private static boolean line(char c){return c=='\r'||c=='\n'||c=='\u2028'||c=='\u2029';}
    String render(Object value,Json5Writer writer) {
        if(matches(root,value))return source;
        StringBuilder out=new StringBuilder(source.length()+64);out.append(source,0,root.start);
        render(root,value,writer,out);return out.append(source,root.end,source.length()).toString();
    }
    private void render(Node node,Object value,Json5Writer writer,StringBuilder out) {
        value=plain(value);
        if(matches(node,value)){out.append(source,node.start,node.end);return;}
        if(node.members!=null && value instanceof Map) {
            Map<?,?> values=(Map<?,?>)value;Map<String,Member> last=members(node);
            out.append('{');int from=node.start+1;boolean emitted=false;
            if(node.members.isEmpty()){trivia(out,from,node.end-1);from=node.end-1;}
            for(Member member:node.members) {
                boolean keep=values.containsKey(member.key);
                if(keep&&emitted)out.append(',');trivia(out,from,member.start);
                if(keep) {
                    out.append(source,member.start,member.value.start);
                    if(last.get(member.key)!=member)out.append(source,member.value.start,member.value.end);
                    else render(member.value,values.get(member.key),writer,out);
                    emitted=true;
                } else orphanComments(out,member.start,member.value.end);
                from=member.value.end;
            }
            for(Map.Entry<?,?> entry:values.entrySet())if(!last.containsKey(entry.getKey())) {
                if(emitted)out.append(',');out.append(insertion(node));
                out.append(writer.documentKey((String)entry.getKey())).append(':').append(' ').append(writer.stringify(entry.getValue()));emitted=true;
            }
            if(emitted&&comma(from,node.end-1))out.append(',');trivia(out,from,node.end-1);out.append('}');
        } else if(node.elements!=null && value instanceof List) {
            List<?> values=(List<?>)value;out.append('[');int from=node.start+1;boolean emitted=false;
            if(node.elements.isEmpty()){trivia(out,from,node.end-1);from=node.end-1;}
            for(int i=0;i<node.elements.size();i++) {
                Node child=node.elements.get(i);boolean keep=i<values.size();
                if(keep&&emitted)out.append(',');trivia(out,from,child.start);
                if(keep){render(child,values.get(i),writer,out);emitted=true;}else orphanComments(out,child.start,child.end);
                from=child.end;
            }
            for(int i=node.elements.size();i<values.size();i++) {
                if(emitted)out.append(',');out.append(insertion(node)).append(writer.stringify(values.get(i)));emitted=true;
            }
            if(emitted&&comma(from,node.end-1))out.append(',');trivia(out,from,node.end-1);out.append(']');
        } else {orphanComments(out,node.start,node.end);out.append(writer.stringify(value));}
    }
    @SuppressWarnings("unchecked") private static Map<String,Member> members(Node node){return (Map<String,Member>)node.original;}
    private static boolean matches(Node node,Object value) {
        value=plain(value);
        if(node.members!=null) {
            if(!(value instanceof Map))return false;Map<?,?> map=(Map<?,?>)value;Map<String,Member> old=members(node);
            if(!old.keySet().equals(map.keySet()))return false;
            for(Map.Entry<String,Member> entry:old.entrySet())if(!matches(entry.getValue().value,map.get(entry.getKey())))return false;
            return true;
        }
        if(node.elements!=null) {
            if(!(value instanceof List))return false;List<?> list=(List<?>)value;if(list.size()!=node.elements.size())return false;
            for(int i=0;i<list.size();i++)if(!matches(node.elements.get(i),list.get(i)))return false;return true;
        }
        return equal(node.original,value);
    }
    private String insertion(Node node) {
        for(int i=node.start+1;i<node.end;i++)if(line(source.charAt(i))) {
            int first=node.members!=null&&!node.members.isEmpty()?node.members.get(0).start
                    :node.elements!=null&&!node.elements.isEmpty()?node.elements.get(0).start:-1;
            String indent=first<0?null:indent(first);
            if(indent==null) {String parent=indent(node.start);indent=(parent==null?"":parent)+"  ";}
            return newline+indent;
        }
        return node.end-node.start>2?" ":"";
    }
    private String indent(int at) {
        int start=at;while(start>0&&!line(source.charAt(start-1)))start--;
        for(int i=start;i<at;i++)if(source.charAt(i)!=' '&&source.charAt(i)!='\t')return null;
        return source.substring(start,at);
    }
    private void orphanComments(StringBuilder out,int from,int to) {
        int low=0,high=comments.size();
        while(low<high){int mid=(low+high)>>>1;if(comments.get(mid)[0]<from)low=mid+1;else high=mid;}
        for(int i=low;i<comments.size();i++){int[] comment=comments.get(i);if(comment[0]>=to)break;out.append(source,comment[0],comment[1]).append(newline);}
    }
    private int commentEnd(int start,int end) {
        int i=start+2;
        if(source.charAt(start+1)=='/') {while(i<end&&!line(source.charAt(i)))i++;}
        else {while(i<end&&!(source.charAt(i-1)=='*'&&source.charAt(i)=='/'))i++;i++;}
        return i;
    }
    private boolean comma(int start,int end) {
        for(int i=start;i<end;i++){char c=source.charAt(i);if(c=='/')i=commentEnd(i,end)-1;else if(c==',')return true;}return false;
    }
    private void trivia(StringBuilder out,int start,int end) {
        for(int i=start;i<end;) {
            char c=source.charAt(i);
            if(c=='/'){int next=commentEnd(i,end);out.append(source,i,next);i=next;}
            else {if(c!=',')out.append(c);i++;}
        }
    }
    static Object plain(Object value){while(value instanceof CommentedValue)value=((CommentedValue)value).getValue();return value;}
    static boolean equal(Object a,Object b) {
        a=plain(a);b=plain(b);if(a==b)return true;if(a==null||b==null)return false;
        if(a instanceof Number&&b instanceof Number) {
            if(a.equals(b))return true;
            Number x=(Number)a,y=(Number)b;double xd=x.doubleValue(),yd=y.doubleValue();
            boolean xf=x instanceof Double||x instanceof Float,yf=y instanceof Double||y instanceof Float;
            if(xf&&!Double.isFinite(xd)||yf&&!Double.isFinite(yd))return xf&&yf&&Double.compare(xd,yd)==0;
            boolean xn=xf&&Double.doubleToRawLongBits(xd)==Long.MIN_VALUE,yn=yf&&Double.doubleToRawLongBits(yd)==Long.MIN_VALUE;
            if(xn||yn)return xn&&yn;
            try{return (x instanceof BigDecimal?(BigDecimal)x:new BigDecimal(x.toString())).compareTo(y instanceof BigDecimal?(BigDecimal)y:new BigDecimal(y.toString()))==0;}catch(NumberFormatException invalid){return false;}
        }
        if(a instanceof Map&&b instanceof Map) {
            Map<?,?> x=(Map<?,?>)a,y=(Map<?,?>)b;if(!x.keySet().equals(y.keySet()))return false;
            for(Object key:x.keySet())if(!equal(x.get(key),y.get(key)))return false;return true;
        }
        if(a instanceof List&&(b instanceof List||b.getClass().isArray())) {
            List<?> x=(List<?>)a;int length=b instanceof List?((List<?>)b).size():Array.getLength(b);if(x.size()!=length)return false;
            for(int i=0;i<length;i++)if(!equal(x.get(i),b instanceof List?((List<?>)b).get(i):Array.get(b,i)))return false;return true;
        }
        if(a instanceof List&&b instanceof Iterable) {
            Iterator<?> items=((Iterable<?>)b).iterator();for(Object item:(List<?>)a)if(!items.hasNext()||!equal(item,items.next()))return false;return !items.hasNext();
        }
        return a.equals(b);
    }
}
