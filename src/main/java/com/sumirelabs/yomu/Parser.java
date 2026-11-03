package com.sumirelabs.yomu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Per-call scanner and recursive descent parser; no token objects or regex. */
final class Parser {
    private final String source;
    private final int length;
    private final int maxDepth;
    private final boolean bigDecimals;
    private final boolean strictJson;
    private int pos;
    private StringBuilder stringBuffer;
    private String[] strings;
    private long[] literalSpans;
    private String[] literalValues;
    private int disabledLiteralSlots;
    private static final int SHARED_STRING_LIMIT = 64;
    private static final int SHARED_STRING_SLOTS = 64;
    private static final int SHARING_DOCUMENT_THRESHOLD = 1024;
    private static final int LITERAL_CACHE_SLOTS = 32;
    private static final int LITERAL_SOURCE_LIMIT = 256;
    // The extra suffix loads regress the Java 8 String path in independent JVMs.
    // Keep its existing comparison; later JDKs benefit from rejecting the tail first.
    private static final boolean CHECK_SLICE_TAIL = checkSliceTail();

    private static boolean checkSliceTail() {
        try {
            String version=System.getProperty("java.specification.version","1.8");
            return !version.startsWith("1.");
        } catch(SecurityException unavailable) {return false;}
    }

    Parser(String source, int maxDepth, boolean bigDecimals, boolean strictJson) {
        this.source = source;
        this.length = source.length();
        this.maxDepth = maxDepth;
        this.bigDecimals = bigDecimals;
        this.strictJson = strictJson;
    }

    Object parse() {
        skip();
        Object result = value(0);
        skip();
        if (pos != length) throw error("Unexpected content after root value");
        return result;
    }

    private Object value(int depth) {
        if (pos == length) throw error("Expected a value");
        char c = source.charAt(pos);
        switch (c) {
            case '{':
                if (depth >= maxDepth) throw error("Maximum nesting depth exceeded");
                return object(depth + 1);
            case '[':
                if (depth >= maxDepth) throw error("Maximum nesting depth exceeded");
                return array(depth + 1);
            case '\'': case '"': return string();
            case 't': literal("true"); return Boolean.TRUE;
            case 'f': literal("false"); return Boolean.FALSE;
            case 'n': literal("null"); return null;
            default: return number();
        }
    }

    private Map<String, Object> object(int depth) {
        pos++;
        skip();
        Map<String, Object> result = new LinkedHashMap<String, Object>(4);
        if (take('}')) return result;
        while (true) {
            if (pos == length) throw error("Expected an object key");
            char c = source.charAt(pos);
            if (strictJson && c != '"') throw error("JSON object keys must be double-quoted");
            String key = c == '"' || c == '\'' ? string()
                    : literalSpans != null ? reusedIdentifier() : identifier();
            skip();
            expect(':');
            skip();
            result.put(key, value(depth));
            skip();
            if (take('}')) return result;
            expect(',');
            skip();
            if (strictJson && pos < length && source.charAt(pos) == '}') throw error("Trailing object comma is not allowed in JSON");
            if (take('}')) return result;
        }
    }

    private List<Object> array(int depth) {
        pos++;
        skip();
        List<Object> result = new ArrayList<Object>();
        if (take(']')) return result;
        while (true) {
            result.add(value(depth));
            skip();
            if (take(']')) return result;
            expect(',');
            skip();
            if (strictJson && pos < length && source.charAt(pos) == ']') throw error("Trailing array comma is not allowed in JSON");
            if (take(']')) return result;
        }
    }

    private String string() {
        if (strictJson && source.charAt(pos) != '"') throw error("JSON strings must be double-quoted");
        int tokenStart = pos;
        int literalSlot = -1;
        if (length >= SHARING_DOCUMENT_THRESHOLD && length - pos > 2) {
            literalSlot = (source.charAt(pos + 1) * 31 + source.charAt(pos + 2)) & (LITERAL_CACHE_SLOTS - 1);
            if ((disabledLiteralSlots & (1 << literalSlot)) != 0) literalSlot = -1;
            else if (literalSpans != null) {
                long span = literalSpans[literalSlot];
                int size = (int) span;
                // The span includes both quotes and was fully validated earlier in this parse.
                if (size > 0 && size <= length - pos && source.regionMatches(pos, source, (int) (span >>> 32), size)) {
                    pos += size;
                    return literalValues[literalSlot];
                }
                if (size > 0) {
                    // Changing values with the same prefix should not pay for a failing comparison on every row.
                    disabledLiteralSlots |= 1 << literalSlot;
                    literalSlot = -1;
                }
            }
        }
        char quote = source.charAt(pos++);
        return stringBody(quote,tokenStart,literalSlot,false);
    }

    String parseStringLiteral() {
        if(length==0 || source.charAt(0)!='"' && source.charAt(0)!='\'')throw error("Expected exactly one quoted string literal");
        String result=string();
        if(pos!=length)throw error("Unexpected content after string literal");
        return result;
    }

    String parseStringBody(char quote) { return stringBody(quote,0,-1,true); }

    String parseKeyLiteral() {
        if(length==0)throw error("Expected object key");
        char c=source.charAt(0);
        if(strictJson && c!='"')throw error("JSON object keys must be double-quoted");
        String key=c=='"'||c=='\''?string():identifier();
        if(pos!=length)throw error("Unexpected content after object key");
        return key;
    }

    private String stringBody(char quote,int tokenStart,int literalSlot,boolean bare) {
        int start = pos;
        StringBuilder decoded = null;
        while (pos < length) {
            // Scan ordinary text with a local cursor; publish the position only at
            // a quote, escape, invalid character, or the end of the input.
            int cursor = pos;
            char c;
            do {
                c = source.charAt(cursor++);
                if (c == quote || c == '\\' || c < 0x20 && (strictJson || c == '\r' || c == '\n')) break;
            } while (cursor < length);
            pos = cursor;
            if (c == quote) {
                if(bare)throw new Json5Exception("Unescaped quote in string body",source,pos-1);
                String result = decoded == null ? sharedSlice(start, pos - 1)
                        : sharedDecoded(decoded.append(source, start, pos - 1));
                if (literalSlot >= 0 && result.length() <= SHARED_STRING_LIMIT && pos - tokenStart <= LITERAL_SOURCE_LIMIT) {
                    if (literalSpans == null) {
                        literalSpans = new long[LITERAL_CACHE_SLOTS];
                        literalValues = new String[LITERAL_CACHE_SLOTS];
                    }
                    literalSpans[literalSlot] = (long) tokenStart << 32 | (pos - tokenStart);
                    literalValues[literalSlot] = result;
                }
                return result;
            }
            if (c == '\r' || c == '\n') throw error("Unescaped line break in string");
            if (strictJson && c < 0x20) throw error("Unescaped control character in JSON string");
            if (c != '\\') continue;
            if (decoded == null) {
                if (stringBuffer == null) stringBuffer = new StringBuilder(64);
                decoded = stringBuffer;
                decoded.setLength(0);
            }
            int segmentEnd = pos - 1;
            if (start < segmentEnd) {
                if (segmentEnd - start == 1) decoded.append(source.charAt(start));
                else decoded.append(source, start, segmentEnd);
            }
            if (pos == length) throw error("Unterminated escape");
            c = source.charAt(pos++);
            if (strictJson && !Json5Lexical.jsonEscape(c)) throw error("Invalid JSON string escape");
            switch (c) {
                case '\n': case '\u2028': case '\u2029': start = pos; continue;
                case '\r': take('\n'); start = pos; continue;
                case 'b': c = '\b'; break;
                case 'f': c = '\f'; break;
                case 'n': c = '\n'; break;
                case 'r': c = '\r'; break;
                case 't': c = '\t'; break;
                case 'v': c = '\u000b'; break;
                case 'x': c = (char) hex(2); break;
                case 'u': c = (char) hex(4); break;
                case '0':
                    if (pos < length && digit(source.charAt(pos))) throw error("Octal escapes are forbidden");
                    c = '\0';
                    break;
                default:
                    if (digit(c)) throw error("Numeric escapes are forbidden");
            }
            decoded.append(c);
            start = pos;
        }
        if(bare)return decoded==null ? sharedSlice(start,pos) : sharedDecoded(decoded.append(source,start,pos));
        throw error("Unterminated string");
    }

    // Share immutable strings only within this parse. Neither the source nor buffers escape.
    private String sharedSlice(int start, int end) {
        int size = end - start;
        if (size == 0) return "";
        if (length < SHARING_DOCUMENT_THRESHOLD || size > SHARED_STRING_LIMIT) return source.substring(start, end);
        if (strings == null) strings = new String[SHARED_STRING_SLOTS];
        int slot = stringSlot(size, source.charAt(start), source.charAt(start + size / 2), source.charAt(end - 1));
        for (int i = slot; i < slot + 2; i++) {
            String cached = strings[i];
            if (cached != null && cached.length() == size
                    && (!CHECK_SLICE_TAIL || size < 16 || cached.charAt(size - 2) == source.charAt(end - 2)
                        && cached.charAt(size - 1) == source.charAt(end - 1))
                    && source.regionMatches(start, cached, 0, size)) return cached;
        }
        return remember(slot, source.substring(start, end));
    }

    private String sharedDecoded(StringBuilder decoded) {
        int size = decoded.length();
        if (size == 0) return "";
        if (length < SHARING_DOCUMENT_THRESHOLD || size > SHARED_STRING_LIMIT) return decoded.toString();
        if (strings == null) strings = new String[SHARED_STRING_SLOTS];
        int slot = stringSlot(size, decoded.charAt(0), decoded.charAt(size / 2), decoded.charAt(size - 1));
        for (int i = slot; i < slot + 2; i++) {
            String cached = strings[i];
            if (cached != null && cached.length() == size
                    && (size < 16 || cached.charAt(size - 2) == decoded.charAt(size - 2)
                        && cached.charAt(size - 1) == decoded.charAt(size - 1))
                    && cached.contentEquals(decoded)) return cached;
        }
        return remember(slot, decoded.toString());
    }

    private String remember(int slot, String value) {
        if (strings[slot] == null) strings[slot] = value;
        else if (strings[slot + 1] == null) strings[slot + 1] = value;
        else {
            strings[slot + 1] = strings[slot];
            strings[slot] = value;
        }
        return value;
    }

    private static int stringSlot(int size, char first, char middle, char last) {
        int hash = size * 0x9e3779b9 ^ first * 31 ^ middle * 17 ^ last;
        return (hash ^ (hash >>> 16)) & (SHARED_STRING_SLOTS - 2);
    }

    private String reusedIdentifier() {
        int start = pos;
        // Keys may occupy empty or disabled slots, never evict an active quoted
        // literal. A later quoted literal may replace a negative (key) span.
        int keySlot = source.charAt(start) & (LITERAL_CACHE_SLOTS - 1);
        long span = literalSpans[keySlot];
        int size = -(int) span;
        if (size > 0 && size < length - start) {
            char next = source.charAt(start + size);
            if ((next == ':' || next <= 32 || next == '/')
                    && source.regionMatches(start, source, (int) (span >>> 32), size)) {
                pos = start + size;
                return literalValues[keySlot];
            }
        }
        String key = identifier();
        size = pos - start;
        if (size == key.length() && size <= SHARED_STRING_LIMIT
                && ((int)span <= 0 || (disabledLiteralSlots & (1 << keySlot)) != 0)) {
            literalSpans[keySlot] = (long) start << 32 | (-(long) size & 0xffffffffL);
            literalValues[keySlot] = key;
        }
        return key;
    }

    private String identifier() {
        int start = pos;
        int end = start;
        char initial = source.charAt(end);
        if (initial < 128 && identifierStart(initial)) {
            for (end++; end < length; end++) {
                char c = source.charAt(end);
                int lower = c | 32;
                if (!(lower >= 'a' && lower <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '$')) break;
            }
            // Non-ASCII and escaped continuations need the complete IdentifierName rules.
            if (end == length || source.charAt(end) < 128 && source.charAt(end) != '\\') {
                pos = end;
                return sharedSlice(start, end);
            }
        }
        StringBuilder decoded = null;
        boolean first = true;
        while (pos < length) {
            int cp;
            if (source.charAt(pos) == '\\') {
                if (decoded == null) decoded = new StringBuilder();
                decoded.append(source, start, pos);
                pos++;
                expect('u');
                cp = hex(4);
                if (!(first ? identifierStart(cp) : identifierPart(cp))) {
                    throw error("Invalid escaped identifier character");
                }
                decoded.append((char) cp);
                start = pos;
            } else {
                cp = source.codePointAt(pos);
                if (!(first ? identifierStart(cp) : identifierPart(cp))) break;
                pos += Character.charCount(cp);
            }
            first = false;
        }
        if (first) throw error("Expected a quoted key or identifier");
        if (decoded == null) return sharedSlice(start, pos);
        return sharedDecoded(decoded.append(source, start, pos));
    }

    static boolean identifierStart(int c) {
        return Json5Lexical.identifierStart(c);
    }

    static boolean identifierPart(int c) {
        if (identifierStart(c) || c >= '0' && c <= '9' || c == 0x200c || c == 0x200d) return true;
        return c >= 128 && Json5Unicode.part(c);
    }

    private Number number() {
        int start = pos;
        boolean negative = take('-');
        if (!negative) {
            if (strictJson && pos < length && source.charAt(pos) == '+') throw error("Leading plus is not allowed in JSON");
            take('+');
        }
        if (pos == length) throw error("Expected a number");
        char c = source.charAt(pos);
        if (strictJson && !digit(c)) throw error("Expected a JSON number");
        if (c == 'I') {
            literal("Infinity");
            return negative ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        if (c == 'N') {
            literal("NaN");
            return Double.NaN;
        }
        int digitsStart = pos;
        if (c == '0' && pos + 1 < length && (source.charAt(pos + 1) == 'x' || source.charAt(pos + 1) == 'X')) {
            if (strictJson) throw error("Hexadecimal numbers are not allowed in JSON");
            pos += 2;
            digitsStart = pos;
            while (pos < length && Json5Lexical.hexDigit(source.charAt(pos)) >= 0) pos++;
            if (pos == digitsStart) throw error("Expected hexadecimal digits");
            return integer(digitsStart, pos, 16, negative);
        }
        while (pos < length && digit(source.charAt(pos))) pos++;
        int integerEnd = pos;
        if (integerEnd - digitsStart > 1 && c == '0') throw error("Leading zeros are forbidden");
        boolean decimal = take('.');
        if (decimal) {
            int fractionStart = pos;
            while (pos < length && digit(source.charAt(pos))) pos++;
            if (pos == fractionStart && (strictJson || integerEnd == digitsStart)) throw error("Expected decimal digits");
        } else if (integerEnd == digitsStart) throw error("Expected a value");
        int exponentMark = pos;
        boolean exponent = pos < length && (source.charAt(pos) == 'e' || source.charAt(pos) == 'E');
        if (exponent) {
            pos++;
            if (!take('+')) take('-');
            int exponentStart = pos;
            while (pos < length && digit(source.charAt(pos))) pos++;
            if (pos == exponentStart) throw error("Expected exponent digits");
        }
        if (!decimal && !exponent) return integer(digitsStart, pos, 10, negative);
        if (!bigDecimals) return Double.valueOf(source.substring(start, pos));
        return ParsedNumbers.decimal(source,start,pos,exponentMark,decimal ? exponentMark - integerEnd - 1 : 0,negative);
    }

    // Accumulate negatively to handle Long.MIN_VALUE without overflow or a temporary string.
    private Number integer(int start, int end, int radix, boolean negative) {
        long limit = negative ? Long.MIN_VALUE : -Long.MAX_VALUE;
        long threshold = limit / radix;
        long n = 0;
        for (int i = start; i < end; i++) {
            int d = Json5Lexical.hexDigit(source.charAt(i));
            if (n < threshold || n * radix < limit + d) {
                return ParsedNumbers.integer(source,start,end,radix,negative);
            }
            n = n * radix - d;
        }
        if (negative && n == 0) return Double.valueOf(-0.0);
        return Long.valueOf(negative ? n : -n);
    }

    private void literal(String text) {
        if (!source.startsWith(text, pos)) throw error("Expected '" + text + "'");
        pos += text.length();
    }

    private int hex(int count) {
        int n = 0;
        for (int i = 0; i < count; i++) {
            if (pos == length) throw error("Incomplete hexadecimal escape");
            int d = Json5Lexical.hexDigit(source.charAt(pos++));
            if (d < 0) throw error("Invalid hexadecimal escape");
            n = n * 16 + d;
        }
        return n;
    }

    private static boolean digit(char c) { return c >= '0' && c <= '9'; }

    private void skip() {
        if (strictJson) {
            while (pos < length) {
                char c = source.charAt(pos);
                if (c != ' ' && c != '\t' && c != '\r' && c != '\n') return;
                pos++;
            }
            return;
        }
        while (pos < length) {
            char c = source.charAt(pos);
            if (c == ' ' || c >= '\t' && c <= '\r' || c == '\u00a0' || c == '\ufeff'
                    || c == '\u2028' || c == '\u2029' || c > 127 && Json5Unicode.space(c)) {
                pos++;
                continue;
            }
            if (c != '/' || pos + 1 == length) return;
            char next = source.charAt(pos + 1);
            if (next == '/') {
                pos += 2;
                while (pos < length) {
                    c = source.charAt(pos);
                    if (c == '\r' || c == '\n' || c == '\u2028' || c == '\u2029') break;
                    pos++;
                }
            } else if (next == '*') {
                int end = source.indexOf("*/", pos + 2);
                if (end < 0) throw error("Unterminated block comment");
                pos = end + 2;
            } else return;
        }
    }

    private boolean take(char c) {
        if (pos < length && source.charAt(pos) == c) { pos++; return true; }
        return false;
    }

    private void expect(char c) {
        if (!take(c)) throw error("Expected '" + c + "'");
    }

    private Json5Exception error(String message) { return new Json5Exception(message, source, pos); }
}
