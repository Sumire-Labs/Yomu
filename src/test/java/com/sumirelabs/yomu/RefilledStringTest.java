package com.sumirelabs.yomu;

import java.io.StringReader;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class RefilledStringTest {
    @Test public void refilledStringsKeepEscapesAndGrammarChecks() throws Exception {
        for(boolean strict:new boolean[]{false,true}) {
            Json5ReadOptions options=Json5ReadOptions.DEFAULT.withStrictJson(strict);
            for(int remaining:new int[]{1,2,3,7,15}) {
                char[] spaces=new char[8192-remaining];Arrays.fill(spaces,' ');
                String prefix=new String(spaces);
                for(String token:new String[]{"\"plain日本語\"","\"a\\n\\t\\u3042\\\"z\"","\"a\u2028b\u2029c\"","\"\\\\end\""}) {
                    String source=prefix+token;
                    assertEquals(Yomu.parse(source,options),Yomu.parse(new StringReader(source),options));
                }
                for(String token:new String[]{"\"a\\u00xz\"","\"raw\nline\"","\"unterminated","\"\\1\""}) {
                    try {Yomu.parse(new StringReader(prefix+token),options);fail("Accepted "+token);}
                    catch(Json5Exception expected){}
                }
                if(strict)for(String token:new String[]{"\"a\\x41\"","\"a\u0001z\"","'single'"}) {
                    try {Yomu.parse(new StringReader(prefix+token),options);fail("Accepted strict "+token);}
                    catch(Json5Exception expected){}
                }
            }
        }
    }
}
