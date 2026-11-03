// Frozen pre-sharing regression fixture; test-only, never included in published JARs.
package com.sumirelabs.yomu;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Per-call scanner and recursive descent parser; no token objects or regex. */
final class Parser {
    private final String source;
    private final int length;
    private final int maxDepth;
    private int pos;
    private StringBuilder stringBuffer;
    private String[] keys;

    Parser(String source, int maxDepth) {
        this.source = source;
        this.length = source.length();
        this.maxDepth = maxDepth;
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
            case '\'': case '"': return string(false);
            case 't': literal("true"); return Boolean.TRUE;
            case 'f': literal("false"); return Boolean.FALSE;
            case 'n': literal("null"); return null;
            default: return number();
        }
    }

    private Map<String, Object> object(int depth) {
        pos++;
        skip();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (keys == null && depth > 1 && length >= 1024) keys = new String[64];
        if (take('}')) return result;
        while (true) {
            if (pos == length) throw error("Expected an object key");
            char c = source.charAt(pos);
            String key = c == '"' || c == '\'' ? string(true) : identifier();
            skip();
            expect(':');
            skip();
            result.put(key, value(depth));
            skip();
            if (take('}')) return result;
            expect(',');
            skip();
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
            if (take(']')) return result;
        }
    }

    private String string(boolean key) {
        char quote = source.charAt(pos++);
        int start = pos;
        StringBuilder decoded = null;
        while (pos < length) {
            char c = source.charAt(pos++);
            if (c == quote) {
                if (decoded == null) return key ? keySlice(start, pos - 1) : source.substring(start, pos - 1);
                return decoded.append(source, start, pos - 1).toString();
            }
            if (c == '\r' || c == '\n') throw error("Unescaped line break in string");
            if (c != '\\') continue;
            if (decoded == null) {
                if (stringBuffer == null) stringBuffer = new StringBuilder(64);
                decoded = stringBuffer;
                decoded.setLength(0);
            }
            decoded.append(source, start, pos - 1);
            if (pos == length) throw error("Unterminated escape");
            c = source.charAt(pos++);
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
        throw error("Unterminated string");
    }

    // Bounded, per-document key reuse: no global interning or retained input after parse returns.
    private String keySlice(int start, int end) {
        if (keys == null) return source.substring(start, end);
        int size = end - start;
        if (size == 0) return "";
        int slot = (size * 31 + source.charAt(start) * 17 + source.charAt(end - 1)) & (keys.length - 1);
        String key = keys[slot];
        if (key != null && key.length() == size && source.regionMatches(start, key, 0, size)) return key;
        return keys[slot] = source.substring(start, end);
    }

    private String identifier() {
        int start = pos;
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
        if (decoded == null) return keySlice(start, pos);
        return decoded.append(source, start, pos).toString();
    }

    static boolean identifierStart(int c) {
        if (c < 128) return c == '$' || c == '_' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
        return Character.isLetter(c) || Character.getType(c) == Character.LETTER_NUMBER;
    }

    static boolean identifierPart(int c) {
        if (identifierStart(c) || c >= '0' && c <= '9' || c == 0x200c || c == 0x200d) return true;
        int type = Character.getType(c);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                || type == Character.DECIMAL_DIGIT_NUMBER || type == Character.CONNECTOR_PUNCTUATION;
    }

    private Number number() {
        int start = pos;
        boolean negative = take('-');
        if (!negative) take('+');
        if (pos == length) throw error("Expected a number");
        char c = source.charAt(pos);
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
            pos += 2;
            digitsStart = pos;
            while (pos < length && hexDigit(source.charAt(pos)) >= 0) pos++;
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
            if (integerEnd == digitsStart && pos == fractionStart) throw error("Expected decimal digits");
        } else if (integerEnd == digitsStart) throw error("Expected a value");
        boolean exponent = pos < length && (source.charAt(pos) == 'e' || source.charAt(pos) == 'E');
        if (exponent) {
            pos++;
            if (!take('+')) take('-');
            int exponentStart = pos;
            while (pos < length && digit(source.charAt(pos))) pos++;
            if (pos == exponentStart) throw error("Expected exponent digits");
        }
        if (!decimal && !exponent) return integer(digitsStart, pos, 10, negative);
        return Double.valueOf(source.substring(start, pos));
    }

    // Accumulate negatively to handle Long.MIN_VALUE without overflow or a temporary string.
    private Number integer(int start, int end, int radix, boolean negative) {
        long limit = negative ? Long.MIN_VALUE : -Long.MAX_VALUE;
        long threshold = limit / radix;
        long n = 0;
        for (int i = start; i < end; i++) {
            int d = hexDigit(source.charAt(i));
            if (n < threshold || n * radix < limit + d) {
                BigInteger big = new BigInteger(source.substring(start, end), radix);
                return negative ? big.negate() : big;
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
            int d = hexDigit(source.charAt(pos++));
            if (d < 0) throw error("Invalid hexadecimal escape");
            n = n * 16 + d;
        }
        return n;
    }

    private static boolean digit(char c) { return c >= '0' && c <= '9'; }

    private static int hexDigit(char c) {
        if (digit(c)) return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private void skip() {
        while (pos < length) {
            char c = source.charAt(pos);
            if (c == ' ' || c >= '\t' && c <= '\r' || c == '\u00a0' || c == '\ufeff'
                    || c == '\u2028' || c == '\u2029' || c > 127 && Character.getType(c) == Character.SPACE_SEPARATOR) {
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
