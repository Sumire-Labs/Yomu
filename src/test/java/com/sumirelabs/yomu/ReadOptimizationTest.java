package com.sumirelabs.yomu;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReadOptimizationTest {
    @Test public void longCollidingTailsStillCompareFullValuesAndShareEqualSpellings() {
        StringBuilder text=new StringBuilder("/*");
        for(int i=0;i<1024;i++)text.append(' ');
        text.append("*/[");
        for(int i=0;i<100;i++) {
            if(i!=0)text.append(',');
            String value="0123456789abcdefghijklmnop"+(char)('A'+i%26)+(char)('A'+i/26)+"z";
            text.append('"').append(value).append("\",\"\\u0030").append(value.substring(1)).append('"');
        }
        List<?> values=(List<?>)Yomu.parse(text.append(']').toString());
        for(int i=0;i<100;i++) {
            String expected="0123456789abcdefghijklmnop"+(char)('A'+i%26)+(char)('A'+i/26)+"z";
            assertEquals(expected,values.get(i*2));
            assertSame(values.get(i*2),values.get(i*2+1));
        }
    }

    @Test public void emptyAndSingleCharacterEscapeSegmentsPreserveBothGrammars() {
        assertEquals("\n\tあ'\\AB\0c",((List<?>)Yomu.parse("['\\n\\tあ\\'\\\\\\u0041\\x42\\0\\\r\nc']")).get(0));
        assertEquals("\n\tあ\"\\A",((List<?>)Yomu.parse("[\"\\n\\tあ\\\"\\\\\\u0041\"]",
                Json5ReadOptions.DEFAULT.withStrictJson(true))).get(0));
    }
}
