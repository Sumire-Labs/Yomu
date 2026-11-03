package com.sumirelabs.yomu;

import java.util.Objects;

/** String bodies and complete quoted literals. No global buffers or caches are retained. */
public final class Json5Strings {
    private Json5Strings() { }

    /** Escapes a body for double quotes; the result is valid in JSON and JSON5. Does not add quotes. */
    public static String escape(String text) { return escape(text,'"'); }
    /** Escapes a body for a JSON5 single- or double-quoted string. */
    public static String escape(String text,char quote) {
        Objects.requireNonNull(text,"text");StringEscapes.requireQuote(quote);
        boolean needed=false;
        for(int i=0;i<text.length();i++)if(StringEscapes.needsEscape(text.charAt(i),quote)){needed=true;break;}
        if(!needed)return text;
        StringBuilder out=new StringBuilder(text.length());
        StringEscapes.body(text,out,quote);return out.toString();
    }
    /** Adds double quotes and escapes a complete JSON/JSON5 string literal. */
    public static String quote(String text) { return quote(text,'"'); }
    public static String quote(String text,char quote) {
        Objects.requireNonNull(text,"text");StringEscapes.requireQuote(quote);
        StringBuilder out=new StringBuilder(text.length());
        StringEscapes.quoted(text,out,quote);return out.toString();
    }
    /** Decodes a double-quoted string body using JSON5 rules, without accepting raw double quotes. */
    public static String unescape(String body) { return unescape(body,'"',Json5ReadOptions.DEFAULT); }
    public static String unescape(String body,Json5ReadOptions options) { return unescape(body,'"',options); }
    public static String unescape(String body,char quote) { return unescape(body,quote,Json5ReadOptions.DEFAULT); }
    public static String unescape(String body,char quote,Json5ReadOptions options) {
        Objects.requireNonNull(body,"body");Objects.requireNonNull(options,"options");StringEscapes.requireQuote(quote);
        if(options.isStrictJson() && quote!='"')throw new IllegalArgumentException("JSON strings must use double quotes");
        return new Parser(body,options.getMaxDepth(),options.isBigDecimals(),options.isStrictJson()).parseStringBody(quote);
    }
    /** Decodes exactly one quoted literal. Surrounding whitespace/comments and non-string roots are rejected. */
    public static String unquote(String literal) { return unquote(literal,Json5ReadOptions.DEFAULT); }
    public static String unquote(String literal,Json5ReadOptions options) {
        Objects.requireNonNull(literal,"literal");Objects.requireNonNull(options,"options");
        return new Parser(literal,options.getMaxDepth(),options.isBigDecimals(),options.isStrictJson()).parseStringLiteral();
    }
}
