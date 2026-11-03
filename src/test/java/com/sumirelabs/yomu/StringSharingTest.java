package com.sumirelabs.yomu;

import org.junit.Test;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;
import java.util.*;
import static org.junit.Assert.*;

public class StringSharingTest {
    private static String large(String input) {
        char[] padding = new char[1024];
        Arrays.fill(padding, ' ');
        return "/*" + new String(padding) + "*/" + input;
    }

    @Test public void sharesDecodedAndUnescapedValuesOnlyWithinADocument() {
        String input = large("['hello','h\\u0065llo',\"hello\",'hello', 'line\\nvalue','line\\u000avalue']");
        List<?> first = (List<?>) Yomu.parse(input);
        List<?> second = (List<?>) Yomu.parse(input);
        for (int i = 1; i < 4; i++) assertSame(first.get(0), first.get(i));
        assertSame(first.get(4), first.get(5));
        assertNotSame(first.get(0), second.get(0));
        assertEquals("line\nvalue", first.get(4));
    }

    @Test public void collisionsEvictionsAndLongStringsDoNotChangeValues() {
        List<Object> expected = new ArrayList<Object>();
        for (int i = 0; i < 1000; i++) {
            // Equal length and identical sampled positions force frequent cache-slot collisions.
            String value = "a" + String.format(Locale.ROOT, "%04x", i) + "-fixed-padding-z";
            expected.add(value);
            if (i % 7 == 0) expected.add("a0000-fixed-padding-z");
        }
        char[] chars = new char[8192];
        Arrays.fill(chars, '\u3042');
        expected.add(new String(chars) + "\n\0");
        expected.add("");
        expected.add("\ud800");
        expected.add("\udc00");
        assertEquals(expected, Yomu.parse(Yomu.stringify(expected)));
    }

    @Test public void cachedValuesCannotBypassEscapeValidation() {
        for (String invalid : new String[] {"\\x0g", "\\u000z", "\\01", "\\1", "\\u12", "abc\\"}) {
            try { Yomu.parse(large("['valid\\n','" + invalid + "']")); fail(invalid); }
            catch (Json5Exception expected) { }
        }
        for (String input : new String[] {"['\\x41','\\x4g']", "['\\0','\\01']", "['a','a\nb']",
                "['a','a'bad]", "['a','a", "['\\u0041','\\u004g']", "['\\\r\nx','\\\r\nx'bad]"}) {
            try { Yomu.parse(large(input)); fail(input); }
            catch (Json5Exception expected) { }
        }
    }

    @Test public void literalReuseKeepsQuoteBoundariesAndDecodedValues() {
        List<?> parsed = (List<?>) Yomu.parse(large("['a','a',\"a\",'a\\\nB','a\\\nB','\\x41','\\x41',"
                + "'\\0','\\0','\\ud800','\\ud800','\\u2028','\\u2028','\\q','\\q']"));
        assertEquals(Arrays.asList("a","a","a","aB","aB","A","A","\0","\0","\ud800","\ud800","\u2028","\u2028","q","q"), parsed);
        assertSame(parsed.get(3),parsed.get(4));
    }

    @Test public void cacheLengthBoundariesAndLongIdentifiersRemainValid() {
        for (int length : new int[] {0,1,63,64,65,128,4096}) {
            char[] chars = new char[length];
            Arrays.fill(chars, 'a');
            String value = new String(chars);
            assertEquals(Arrays.asList(value,value + "\n",value), Yomu.parse(large(
                    "['" + value + "','" + value + "\\n','" + value + "']")));
        }
        char[] name = new char[2048];
        Arrays.fill(name, 'a');
        String key = new String(name);
        Map<String,Object> parsed = Yomu.parseObject("{" + key + ":1," + key + ":2}");
        assertEquals(Collections.singletonMap(key,2L), parsed);
    }

    @Test public void mapCapacityPreservesOrderMutationAndDuplicateKeys() {
        for (int count : new int[] {0,1,3,4,6,7,12,13,1000}) {
            StringBuilder input = new StringBuilder("{");
            for (int i = 0; i < count; i++) input.append('k').append(i).append(':').append(i).append(',');
            if (count > 0) input.append("k0:-1,");
            Map<String,Object> map = Yomu.parseObject(input.append('}').toString());
            assertTrue(map instanceof LinkedHashMap);
            assertEquals(count, map.size());
            int i = 0;
            for (String key : map.keySet()) assertEquals("k" + i++, key);
            if (count > 0) assertEquals(-1L, map.get("k0"));
            map.put("added", true);
            assertEquals(true, map.remove("added"));
        }
    }

    @Test public void matchesPreviousParserOnGeneratedAndCorruptedDocuments() throws Exception {
        String baseline = System.getProperty("yomu.baselineClasses", "build/classes/java/sharingBaseline");
        try (URLClassLoader loader = new URLClassLoader(new URL[] {Paths.get(baseline).toUri().toURL()}, null)) {
            Method oldParse = loader.loadClass("com.sumirelabs.yomu.Yomu").getMethod("parse", String.class);
            Random random = new Random(32452843);
            String[] features = {"{a:+1,b:0xff,}", "[.5,1.,-0,NaN,Infinity,]", "{\\u0061:'\\v\\x41\\0'}",
                    "{日本語:'a\\\r\nb'}", "[null,true,false,'\\ud800','\\udc00']", "{/*x*/a:1,//y\u2028b:2}"};
            for (int iteration = 0; iteration < 2000; iteration++) {
                String input;
                if (iteration < features.length) input = features[iteration];
                else {
                    List<String> values = new ArrayList<String>();
                    for (int n = 0; n < 8; n++) {
                        char[] chars = new char[random.nextInt(96)];
                        for (int i = 0; i < chars.length; i++) chars[i] = (char) random.nextInt(65536);
                        values.add(new String(chars));
                        if ((n & 1) == 0) values.add(values.get(values.size() - 1));
                    }
                    input = Yomu.stringify(values);
                }
                compare(oldParse, large(input));
                int position = random.nextInt(input.length());
                compare(oldParse, large(input.substring(0, position)));
                compare(oldParse, large(input.substring(0, position) + "\\" + input.substring(position)));
                compare(oldParse, large(input.substring(0, position) + '\0' + input.substring(position + 1)));
            }
        }
    }

    private static void compare(Method previous, String input) throws Exception {
        Object expected = null;
        Integer expectedOffset = null;
        try { expected = previous.invoke(null, input); }
        catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (!cause.getClass().getName().equals(Json5Exception.class.getName())) throw e;
            expectedOffset = (Integer) cause.getClass().getMethod("getOffset").invoke(cause);
        }
        try {
            Object actual = Yomu.parse(input);
            assertNull("Previous parser rejected this input", expectedOffset);
            assertEquals(expected, actual);
        } catch (Json5Exception e) {
            assertNotNull("New parser rejected a previously accepted input", expectedOffset);
            assertEquals(expectedOffset.intValue(), e.getOffset());
        }
    }
}
