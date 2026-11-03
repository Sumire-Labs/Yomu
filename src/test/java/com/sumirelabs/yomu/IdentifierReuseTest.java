package com.sumirelabs.yomu;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class IdentifierReuseTest {
    @Test public void keysAndQuotedLiteralsCanReplaceTheSameSlotWithoutConfusion() throws Exception {
        StringBuilder text=new StringBuilder("[");
        // 't' selects slot 20; quoted prefix 'a' 'u' selects the same slot.
        for(int i=0;i<100;i++)text.append("{seed:'start',text:").append(i)
                .append(",other:'au").append(i%3).append("',textMore:'au0',t\\u0065xt:'au1'},");
        text.append("{text:123,other:'au0'}]");
        List<?> values=(List<?>)Yomu.parse(text.toString());
        assertEquals(values,Yomu.parse(new java.io.StringReader(text.toString())));
        for(int i=0;i<100;i++) {
            Map<?,?> row=(Map<?,?>)values.get(i);
            assertEquals("au1",row.get("text"));assertEquals("au0",row.get("textMore"));
            assertEquals("au"+(i%3),row.get("other"));
        }
        assertEquals(123L,((Map<?,?>)values.get(100)).get("text"));
    }
    private static String prefix() {
        StringBuilder out=new StringBuilder("[");
        for(int i=0;i<80;i++)out.append("{text:'escaped\\n/").append(i)
                .append("',path:'assets\\\\/").append(i).append("',label:'日本語/").append(i).append("'},");
        return out.toString();
    }
    @Test public void reusedKeysPreserveBoundariesEscapesAndUnicodeContinuations() throws Exception {
        String source=prefix()+"{textMore:1,text :2,path/*comment*/:3,label\n:4,t\\u0065xt:5,texté:6,text\\u200cz:7,'text-more':8}]";
        List<?> values=(List<?>)Yomu.parse(source);
        Map<?,?> tail=(Map<?,?>)values.get(80);
        assertEquals(Arrays.asList("textMore","text","path","label","texté","text\u200cz","text-more"),new ArrayList<Object>(tail.keySet()));
        assertEquals(5L,tail.get("text"));assertEquals(6L,tail.get("texté"));assertEquals(7L,tail.get("text\u200cz"));
        assertEquals(values,Yomu.parse(new java.io.StringReader(source)));
        for(String invalid:new String[]{"{text-more:1}]","{text\\u0020:1}]","{text\0:1}]"}) {
            try {Yomu.parse(prefix()+invalid);fail("Invalid key accepted");}catch(Json5Exception expected){}
        }
    }
}
