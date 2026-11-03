package com.sumirelabs.yomu;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import static org.junit.Assert.*;

public class YomuTest {
    @Test public void reusedEscapeBufferAndKeyCache() {
        StringBuilder source = new StringBuilder("[");
        StringBuilder longString = new StringBuilder();
        for (int i = 0; i < 4096; i++) longString.append(i % 3 == 0 ? '\u3042' : 'a');
        for (int i = 0; i < 128; i++) {
            if (i > 0) source.append(',');
            source.append("{Aa:").append(i).append(",BB:").append(-i)
                    .append(",aaa:").append(i).append(",aba:").append(i + 1)
                    .append(",\"quoted\":'\\n\\u3042\\x41',short:'\\t',continued:'\\\n',long:")
                    .append(Yomu.stringify(longString + "\n" + i)).append('}');
        }
        List<?> values = (List<?>) Yomu.parse(source.append(']').toString());
        for (int i = 0; i < values.size(); i++) {
            Map<?,?> item = (Map<?,?>) values.get(i);
            assertEquals((long)i, item.get("Aa"));
            assertEquals((long)i, item.get("aaa"));
            assertEquals((long)i + 1, item.get("aba"));
            assertEquals("\nあA", item.get("quoted"));
            assertEquals("\t", item.get("short"));
            assertEquals("", item.get("continued"));
            assertEquals(longString + "\n" + i, item.get("long"));
        }
    }
    @Test public void officialFixtures() throws Exception {
        Path root = Paths.get("src/test/resources/json5-tests");
        int valid = 0;
        int invalid = 0;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path file : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator) {
                String name = file.toString();
                boolean accept = name.endsWith(".json") || name.endsWith(".json5");
                if (!accept && !name.endsWith(".js") && !name.endsWith(".txt")) continue;
                String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                try {
                    Object parsed = Yomu.parse(source);
                    if (!accept) fail("Accepted invalid fixture: " + file);
                    assertEquals("Round trip: " + file, parsed, Yomu.parse(Yomu.stringify(parsed)));
                    valid++;
                } catch (Json5Exception e) {
                    if (accept) throw new AssertionError("Rejected valid fixture: " + file + ": " + e, e);
                    invalid++;
                }
            }
        }
        assertTrue(valid > 70);
        assertTrue(invalid > 25);
        System.out.println("Official fixtures: " + valid + " accepted, " + invalid + " rejected");
    }

    @Test public void modConfiguration() {
        Map<String, Object> map = Yomu.parseObject("/* config */ {enabled:true, rate:+20, label:'すみれ', colors:[0xff,],}");
        assertEquals(Boolean.TRUE, map.get("enabled"));
        assertEquals(20L, map.get("rate"));
        assertEquals("すみれ", map.get("label"));
        assertEquals(Collections.singletonList(255L), map.get("colors"));
        assertEquals(Arrays.asList("enabled", "rate", "label", "colors"), Arrays.asList(map.keySet().toArray()));
    }

    @Test public void allRootTypes() {
        assertNull(Yomu.parse("null"));
        assertEquals(false, Yomu.parse("false"));
        assertEquals("x", Yomu.parse("'x'"));
        assertEquals(Arrays.asList(1L, 2L), Yomu.parse("[1,2,]"));
        assertEquals(.5, Yomu.parse("+.5"));
        assertEquals(100.0, Yomu.parse("1.e2"));
    }

    @Test public void exactIntegersAndNegativeZero() {
        String[] integers = {"9223372036854775807", "-9223372036854775808", "9223372036854775808",
                "-9223372036854775809", "18446744073709551616", "0x7fffffffffffffff", "-0x8000000000000000",
                "0xffffffffffffffff", "-0xffffffffffffffff"};
        for (String s : integers) {
            boolean neg = s.startsWith("-");
            String magnitude = neg ? s.substring(1) : s;
            BigInteger expected = magnitude.startsWith("0x") ? new BigInteger(magnitude.substring(2), 16) : new BigInteger(magnitude);
            if (neg) expected = expected.negate();
            assertEquals(expected, new BigInteger(Yomu.parse(s).toString()));
        }
        for (String s : new String[] {"-0", "-0.0", "-0e2", "-0x0"}) {
            assertEquals(Long.MIN_VALUE, Double.doubleToRawLongBits(((Number) Yomu.parse(s)).doubleValue()));
        }
        assertEquals(Double.POSITIVE_INFINITY, Yomu.parse("1e400"));
        assertTrue(Double.isNaN((Double) Yomu.parse("-NaN")));
    }

    @Test public void escapesAndUnicode() {
        assertEquals("a\0\u000bbAq", Yomu.parse("'a\\0\\vb\\x41\\q'"));
        assertEquals("ab", Yomu.parse("'a\\\r\nb'"));
        assertEquals("a\u2028b", Yomu.parse("'a\u2028b'"));
        Map<String, Object> map = Yomu.parseObject("{\\u0061:1, すみれ:2, a\\u200c:3, a\u200d:4}");
        assertEquals(1L, map.get("a"));
        assertEquals(3L, map.get("a\u200c"));
        assertEquals(4L, map.get("a\u200d"));
        assertEquals(1L, Yomu.parse("\ufeff\u000b\u00a0\u202f1\u2028// end"));
        assertEquals(Arrays.asList(1L, 2L), Yomu.parse("[1,// comment\u20292]"));
    }

    @Test public void legacyUnicodeWhitespaceDoesNotDependOnJavaVersion() {
        // Unicode 6.3 reclassified U+180E from Zs to Cf; Java 8 still uses older category data.
        for (String input : new String[] {"\u180e1", "1\u180e", "[1,\u180e2]", "{a\u180e:1}"}) {
            try { Yomu.parse(input); fail("Accepted legacy whitespace"); }
            catch (Json5Exception expected) { }
        }
        assertEquals("\u180e",Yomu.parse("'\u180e'"));
        for (char space : new char[] {'\u1680','\u2000','\u200a','\u202f','\u205f','\u3000'}) {
            assertEquals(1L,Yomu.parse(space + "1" + space));
        }
    }

    @Test public void unicodeIdentifiersAreConsistentAcrossSupportedJdks() {
        for(int point:new int[]{0xa7b3,0x1e900,0x1e4d0}) {
            String key=new String(Character.toChars(point));
            assertEquals(1L,Yomu.parseObject("{"+key+":1}").get(key));
            assertEquals(2L,Yomu.parseObject("{a"+key+":2}").get("a"+key));
        }
        assertEquals(3L,Yomu.parseObject("{\\ua7b3:3}").get("\ua7b3"));
        assertEquals(4L,Yomu.parseObject("{a\\u0301:4}").get("a\u0301"));
        try { Yomu.parse("{\\u0301:4}");fail(); } catch(Json5Exception expected) { }
        try { Yomu.parse("{😀:1}");fail(); } catch(Json5Exception expected) { }
    }

    @Test public void rejectMalformedInputs() {
        String[] inputs = {"", "// only comment", "/*", "[1,,2]", "[,]", "{a:}", "{a:1 b:2}", "{1:2}",
                "true false", "nullx", "+true", "--1", "01", "-01", "0b10", "0x", "0xg", ".", "+.", "1e", "1e+", "NaNx",
                "'\\1'", "'\\01'", "'\\x0g'", "'\\u000z'", "'a\nb'", "{\\u0030:0}", "[1]extra", "undefined"};
        for (String input : inputs) {
            try { Yomu.parse(input); fail("Accepted: " + input); }
            catch (Json5Exception expected) { }
        }
    }

    @Test public void duplicateKeysLastWinsAndDepthLimit() {
        assertEquals(2L, Yomu.parseObject("{a:null,a:2}").get("a"));
        assertEquals(Collections.singletonList(Collections.emptyList()), Yomu.parse("[[]]", 2));
        try { Yomu.parse("[[[]]]", 2); fail(); }
        catch (Json5Exception expected) { }
    }

    @Test public void errorLocation() {
        try { Yomu.parse("{\r\n a: 1,\u2028 b: } "); fail(); }
        catch (Json5Exception e) {
            assertEquals(3, e.getLine());
            assertEquals(5, e.getColumn());
            assertEquals(14, e.getOffset());
        }
    }

    @Test public void utf8AndStreamOwnership() throws Exception {
        final boolean[] closed = {false};
        ByteArrayInputStream input = new ByteArrayInputStream("'すみれ'".getBytes(StandardCharsets.UTF_8)) {
            public void close() { closed[0] = true; }
        };
        assertEquals("すみれ", Yomu.parse(input));
        assertFalse(closed[0]);
        try { Yomu.parse(new ByteArrayInputStream(new byte[] {'\'', (byte) 0xc3, '\''})); fail(); }
        catch (IOException expected) { }
        Path file = Files.createTempFile("yomu", ".json5");
        try {
            Yomu.write(file, Arrays.asList("すみれ", 2L));
            assertEquals(Arrays.asList("すみれ", 2L), Yomu.parse(file));
        } finally { Files.delete(file); }
    }

    @Test public void writerCommentsAndFormatting() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("enabled", Yomu.commented("有効化\r\n次の行\u2028最後", true));
        map.put("not-a-key", Arrays.asList(1L, 2L));
        map.put("items", Arrays.asList(Yomu.commented("item", 3L), 4L));
        Json5Writer writer = new Json5Writer().withIndent("  ").withQuoteKeys(false).withExpandArrays(false);
        String encoded = writer.stringify(map);
        assertTrue(encoded.contains("// 有効化\n  // 次の行\n  // 最後\n  enabled: true"));
        assertTrue(encoded.contains("\"not-a-key\": [1, 2]"));
        Map<String, Object> parsed = Yomu.parseObject(encoded);
        assertEquals(true, parsed.get("enabled"));
        assertEquals(Arrays.asList(3L, 4L), parsed.get("items"));
        assertEquals(parsed, Yomu.parse(Yomu.stringify(map)));
        assertEquals("{\"x\":1}", new Json5Writer().stringify(Collections.singletonMap("x", 1)));
        assertEquals(Arrays.asList(1L, 2L), Yomu.parse(Yomu.stringify(new int[] {1, 2})));
    }

    @Test public void writerRejectsCyclesAndInvalidKeys() {
        Map<String, Object> cycle = new LinkedHashMap<String, Object>();
        cycle.put("self", cycle);
        try { Yomu.stringify(cycle); fail(); } catch (IllegalArgumentException expected) { }
        try { Yomu.stringify(Collections.singletonMap(1, 2)); fail(); } catch (IllegalArgumentException expected) { }
        try { Yomu.stringify(new Object()); fail(); } catch (IllegalArgumentException expected) { }
    }

    @Test public void randomizedStringAndIntegerRoundTrips() {
        Random random = new Random(104729);
        for (int iteration = 0; iteration < 2000; iteration++) {
            char[] chars = new char[random.nextInt(64)];
            for (int i = 0; i < chars.length; i++) chars[i] = (char) random.nextInt(65536);
            String value = new String(chars);
            assertEquals(value, Yomu.parse(Yomu.stringify(value)));
            long n = random.nextLong();
            assertEquals(Long.valueOf(n), Yomu.parse(Long.toString(n)));
            BigInteger b = new BigInteger(256, random);
            assertEquals(b, Yomu.parse("0x" + b.toString(16)));
        }
    }
}
