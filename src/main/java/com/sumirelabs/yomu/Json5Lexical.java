package com.sumirelabs.yomu;

/** Shared lexical rules without initializing the full String parser on token fast paths. */
final class Json5Lexical {
    private Json5Lexical() { }
    static boolean identifierStart(int c) {
        if(c<128){int lower=c|32;return lower>='a'&&lower<='z'||c=='$'||c=='_';}
        return Json5Unicode.start(c);
    }
    static boolean jsonEscape(int c) {
        return c=='"'||c=='\\'||c=='/'||c=='b'||c=='f'||c=='n'||c=='r'||c=='t'||c=='u';
    }
    static int hexDigit(char c) {
        if(c>='0'&&c<='9')return c-'0';
        int lower=c|32;
        if(lower>='a'&&lower<='f')return lower-'a'+10;
        return -1;
    }
}
