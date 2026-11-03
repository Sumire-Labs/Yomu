package com.sumirelabs.yomu.mapper;

import com.sumirelabs.yomu.Json5Writer;
import com.sumirelabs.yomu.Yomu;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class FieldOptionsTest {
    public static class Directions {
        @Json5Field(write=false, aliases={"oldToken"}) private String token = "default";
        @Json5Field(read=false, aliases={"oldStatus"}, comment="generated") public String status = "ready";
        @Json5Field(read=false) public final int version = 1;
        @Json5Field(read=false,write=false) public String hidden = "hidden";
        @Json5Field(ignore=true, aliases={"oldIgnored"}) public String ignored = "ignored";
    }
    public static class NamesBase { public int renderDistance = 8; }
    public static class Names extends NamesBase {
        @Json5Field(name="enabledNow",aliases={"wasEnabled","old_enabled"}) public boolean isEnabled;
        public int HTTPPort = 80;
        public Map<String,Integer> rawKeys;
    }
    public static class Alias {
        @Json5Field(name="current",aliases={"old","a\"b"}) public int count;
    }
    public static class Nested { public List<Alias> items; }
    public static class AliasCollision { @Json5Field(aliases={"b"}) public int a; public int b; }
    public static class NamingCollision { public int aB; public int a_b; }
    public static class IgnoredCollision { @Json5Field(ignore=true,aliases={"b"}) public int a; public int b; }
    public static class DuplicateAlias { @Json5Field(aliases={"a"}) public int a; }
    public static class EmptyAlias { @Json5Field(aliases={""}) public int a; }
    public static class DateConfig {
        @Json5Field(format="yyyy/MM/dd") public LocalDate day;
        @Json5Field(format="uuuu-MM-dd HH:mm:ss",zone="Asia/Tokyo") public Instant instant;
        @Json5Field(format="uuuu-MM-dd HH:mm:ss",zone="UTC") public Date legacy;
        @Json5Field(format="dd MMM uuuu",locale="fr-FR") public LocalDate french;
        @Json5Field(format="uuuu-MM-dd'T'HH:mmXXX") public OffsetDateTime offset;
    }
    public static class OtherDates {
        @Json5Field(format="HH:mm:ss") public LocalTime time;
        @Json5Field(format="uuuu-MM-dd HH:mm") public LocalDateTime local;
        @Json5Field(format="HH:mmXXX") public OffsetTime offset;
        @Json5Field(format="uuuu-MM-dd HH:mm VV") public ZonedDateTime zoned;
        @Json5Field(format="uuuu") public Year year;
        @Json5Field(format="uuuu/MM") public YearMonth month;
        @Json5Field(format="MM/dd") public MonthDay annual;
    }
    public static class Numbers {
        @Json5Field(format="#,##0.00") public BigDecimal amount;
        @Json5Field(format="0.##%") public BigDecimal ratio;
        @Json5Field(format="0") public byte small;
        @Json5Field(format="0.00",locale="de-DE") public BigDecimal german;
        @Json5Field(format="0.00",rounding=RoundingMode.HALF_UP) public double rounded;
        @Json5Field(format="0") public BigInteger huge;
    }
    public static class Floating { @Json5Field(format="0.00") public Double value; }
    public static class Unsupported { @Json5Field(format="x") public String text; }
    public static class BadDate { @Json5Field(format="uuuu-MM-dd'") public LocalDate day; }
    public static class BadPattern { @Json5Field(format="0.0.0") public double number; }
    public static class BadZone { @Json5Field(format="uuuu-MM-dd",zone="NoSuchZone") public Instant time; }
    public static class BadLocale { @Json5Field(format="0",locale="not_a_tag") public int number; }
    public static class Upper implements Codec<String> {
        public String decode(Object value, CodecContext context) {
            if (!(value instanceof String)) throw context.error("Expected string");
            return ((String)value).toUpperCase(Locale.ROOT);
        }
        public Object encode(String value, CodecContext context) { return value.toLowerCase(Locale.ROOT); }
    }
    public static class Csv implements Codec<List<String>> {
        public List<String> decode(Object value, CodecContext context) {
            assertTrue(context.getTargetType() instanceof java.lang.reflect.ParameterizedType);
            if (!(value instanceof String)) throw context.error("Expected CSV string");
            return Arrays.asList(((String)value).split(","));
        }
        public Object encode(List<String> value, CodecContext context) { return String.join(",",value); }
    }
    public static class Specific {
        @Json5Field(codec=Upper.class,aliases={"old"}) public String selected;
        @Json5Field(codec=Csv.class) public List<String> list;
        public String normal;
        @Json5Field(format="uuuu/MM/dd") public LocalDate date;
    }
    public static class Generic<T> { @Json5Field(codec=Csv.class) public T list; }
    public static class Strings extends Generic<List<String>> { }
    public static class Conflict { @Json5Field(codec=Upper.class,format="x") public String text; }
    public static class BadCodec implements Codec<String> {
        public BadCodec(String ignored) { }
        public String decode(Object v,CodecContext c) { return ""; }
        public Object encode(String v,CodecContext c) { return v; }
    }
    public static class BadConstructor { @Json5Field(codec=BadCodec.class) public String text; }

    @Test public void inputAndOutputDirectionsAndStrictUnknowns() {
        Json5Mapper mapper = new Json5Mapper().withFailOnUnknownFields(true);
        Directions config = mapper.read("{oldToken:'secret',oldStatus:[],version:null,hidden:4,oldIgnored:1}",Directions.class);
        assertEquals("secret",config.token);
        assertEquals("ready",config.status);
        assertEquals("hidden",config.hidden);
        Map<String,Object> output = Yomu.parseObject(mapper.write(config));
        assertEquals(new HashSet<String>(Arrays.asList("status","version")),output.keySet());
        assertEquals("ready",output.get("status"));
        assertTrue(mapper.write(config).contains("// generated"));
        failure(() -> mapper.read("{typo:1}",Directions.class),"$[\"typo\"]");
    }

    @Test public void aliasesAreInputOnlyAndConflictingSpellingsAreRejected() {
        Json5Mapper mapper = new Json5Mapper().withFailOnUnknownFields(true);
        assertEquals(2,mapper.read("{old:2}",Alias.class).count);
        assertEquals("{\"current\":2}",mapper.write(mapper.read("{old:2}",Alias.class)));
        assertEquals(3,mapper.read("{current:1,current:3}",Alias.class).count);
        failure(() -> mapper.read("{old:1,current:2}",Alias.class),"$[\"current\"]");
        failure(() -> mapper.read("{current:2,old:1}",Alias.class),"$[\"old\"]");
        failure(() -> mapper.read("{items:[{'a\\\"b':'bad'}]}",Nested.class),"$[\"items\"][0][\"a\\\"b\"]");
    }

    @Test public void namingPoliciesHandleAcronymsAndBoundaries() {
        assertEquals("url_value",NamingPolicy.SNAKE_CASE.translate("URLValue"));
        assertEquals("http2_port",NamingPolicy.SNAKE_CASE.translate("HTTP2Port"));
        assertEquals("render-distance",NamingPolicy.KEBAB_CASE.translate("renderDistance"));
        assertEquals("urlValue",NamingPolicy.LOWER_CAMEL_CASE.translate("URL_value"));
        assertEquals("UrlValue",NamingPolicy.UPPER_CAMEL_CASE.translate("URLValue"));
        assertEquals("日本語_設定",NamingPolicy.SNAKE_CASE.translate("日本語_設定"));
    }

    @Test public void strategyIsImmutableAndExplicitNamesAliasesAndMapsAreLiteral() {
        Json5Mapper plain = new Json5Mapper();
        Json5Mapper snake = plain.withNamingStrategy(NamingPolicy.SNAKE_CASE)
                .withFailOnUnknownFields(true).withWriter(new Json5Writer()).withStandardCodecs()
                .withCodec(UUID.class,StandardCodecs.UUID);
        Names value = snake.read("{render_distance:12,http_port:81,wasEnabled:true,raw_keys:{KeepMe:1}}",Names.class);
        Map<String,Object> out = Yomu.parseObject(snake.write(value));
        assertEquals(12L,out.get("render_distance"));
        assertEquals(true,out.get("enabledNow"));
        assertEquals(Collections.singletonMap("KeepMe",1L),out.get("raw_keys"));
        assertTrue(Yomu.parseObject(plain.write(value)).containsKey("renderDistance"));
        assertTrue(Yomu.parseObject(plain.withNamingStrategy(s -> "x_"+s).write(value)).containsKey("x_HTTPPort"));
        assertTrue(Yomu.parseObject(snake.withNamingStrategy(NamingPolicy.IDENTITY).write(value)).containsKey("renderDistance"));
    }

    @Test public void ambiguousOrInvalidMetadataIsRejected() {
        Json5Mapper mapper = new Json5Mapper();
        for (Object value : new Object[]{new AliasCollision(),new IgnoredCollision(),new DuplicateAlias(),new EmptyAlias(),
                new Unsupported(),new BadDate(),new BadPattern(),new BadZone(),new BadLocale(),new Conflict(),new BadConstructor()})
            failure(() -> mapper.write(value),"$");
        failure(() -> mapper.withNamingStrategy(NamingPolicy.SNAKE_CASE).write(new NamingCollision()),"$");
        failure(() -> mapper.withNamingStrategy(s -> null).write(new Names()),"$");
        failure(() -> mapper.withNamingStrategy(s -> "").write(new Names()),"$");
    }

    @Test public void datesAreStrictAndUseExplicitLocaleAndZone() {
        Json5Mapper mapper = new Json5Mapper();
        DateConfig config = mapper.read("{day:'2024/02/29',instant:'2024-02-29 09:30:00',legacy:'2024-02-29 00:30:00',"
                + "french:'29 févr. 2024',offset:'2024-02-29T09:30+09:00'}",DateConfig.class);
        assertEquals(LocalDate.of(2024,2,29),config.day);
        assertEquals(Instant.parse("2024-02-29T00:30:00Z"),config.instant);
        assertEquals(config.instant,config.legacy.toInstant());
        assertEquals(config.day,config.french);
        Map<String,Object> out=Yomu.parseObject(mapper.write(config));
        assertEquals("2024/02/29",out.get("day"));
        assertEquals("2024-02-29T09:30+09:00",out.get("offset"));
        assertEquals(config.instant,mapper.read(mapper.write(config),DateConfig.class).instant);
        failure(() -> mapper.read("{day:'2023/02/29'}",DateConfig.class),"$[\"day\"]");
        failure(() -> mapper.read("{day:'2024/02/29junk'}",DateConfig.class),"$[\"day\"]");
        failure(() -> mapper.read("{day:2024}",DateConfig.class),"$[\"day\"]");
    }

    @Test public void remainingTemporalFormatsAndProlepticYearsRoundTrip() {
        Json5Mapper mapper=new Json5Mapper();
        OtherDates config=mapper.read("{time:'13:45:20',local:'0000-01-02 03:04',offset:'13:45+09:00',"
                + "zoned:'2024-02-29 09:30 Asia/Tokyo',year:'0000',month:'2024/02',annual:'02/29'}",OtherDates.class);
        assertEquals(0,config.local.getYear());
        OtherDates copy=mapper.read(mapper.write(config),OtherDates.class);
        assertEquals(config.time,copy.time); assertEquals(config.local,copy.local);
        assertEquals(config.offset,copy.offset); assertEquals(config.zoned,copy.zoned);
        assertEquals(config.year,copy.year); assertEquals(config.month,copy.month); assertEquals(config.annual,copy.annual);
    }

    @Test public void numericFormatsParseExactlyCheckRangesAndControlRounding() {
        Json5Mapper mapper=new Json5Mapper();
        Numbers value=mapper.read("{amount:'1,234.50',ratio:'12.5%',small:'127',german:'12,50',rounded:'1.235',huge:'9223372036854775808'}",Numbers.class);
        assertEquals(new BigDecimal("1234.50"),value.amount);
        assertEquals(0,value.ratio.compareTo(new BigDecimal("0.125")));
        assertEquals(127,value.small);
        assertEquals(new BigDecimal("12.50"),value.german);
        Map<String,Object> out=Yomu.parseObject(mapper.write(value));
        assertEquals("1,234.50",out.get("amount"));
        assertEquals("12,50",out.get("german"));
        assertEquals("1.24",out.get("rounded"));
        assertEquals(value.huge,mapper.read(mapper.write(value),Numbers.class).huge);
        failure(() -> mapper.read("{small:'128'}",Numbers.class),"$[\"small\"]");
        failure(() -> mapper.read("{small:'1.5'}",Numbers.class),"$[\"small\"]");
        failure(() -> mapper.read("{amount:'1.00oops'}",Numbers.class),"$[\"amount\"]");
        failure(() -> mapper.read("{amount:'NaN'}",Numbers.class),"$[\"amount\"]");
        failure(() -> mapper.read("{amount:1}",Numbers.class),"$[\"amount\"]");
        value.amount=new BigDecimal("1.234");
        failure(() -> mapper.write(value),"$[\"amount\"]");
        Floating floating=new Floating(); floating.value=Double.POSITIVE_INFINITY;
        failure(() -> mapper.write(floating),"$[\"value\"]");
    }

    @Test public void fieldCodecsAndFormatsOverrideGlobalCodecsAndPreserveGenericTypes() {
        Json5Mapper mapper=new Json5Mapper().withStandardCodecs()
                .withCodec(String.class,Codecs.string(s -> "global:"+s,s -> "global:"+s));
        Specific config=mapper.read("{old:'abc',list:'a,b',normal:'x',date:'2024/02/29'}",Specific.class);
        assertEquals("ABC",config.selected); assertEquals("global:x",config.normal);
        assertEquals(Arrays.asList("a","b"),config.list);
        Map<String,Object> out=Yomu.parseObject(mapper.write(config));
        assertEquals("abc",out.get("selected")); assertEquals("a,b",out.get("list"));
        assertEquals("2024/02/29",out.get("date"));
        Strings strings=new Json5Mapper().read("{list:'a,b'}",Strings.class);
        assertEquals("{\"list\":\"a,b\"}",new Json5Mapper().write(strings));
        failure(() -> mapper.read("{old:1}",Specific.class),"$[\"old\"]");
    }

    @Test public void nullsBypassFieldAdaptersAndPrimitiveNullRemainsInvalid() {
        Json5Mapper mapper=new Json5Mapper();
        assertNull(mapper.read("{day:null}",DateConfig.class).day);
        assertNull(mapper.read("{selected:null,list:null}",Specific.class).selected);
        assertNull(Yomu.parseObject(mapper.write(new DateConfig())).get("day"));
        failure(() -> mapper.read("{small:null}",Numbers.class),"$[\"small\"]");
    }

    @Test public void formattedFieldsAreSafeAcrossThreadsAndDefaultLocaleChanges() throws Exception {
        final Json5Mapper mapper=new Json5Mapper().withNamingStrategy(NamingPolicy.SNAKE_CASE);
        ExecutorService executor=Executors.newFixedThreadPool(4);
        Locale previous=Locale.getDefault();
        try {
            Locale.setDefault(Locale.JAPAN);
            List<Future<BigDecimal>> results=new ArrayList<Future<BigDecimal>>();
            for (int i=0;i<100;i++) {
                final int n=i;
                results.add(executor.submit(() -> {
                    Numbers value=new Numbers(); value.amount=new BigDecimal(n+".25");
                    return mapper.read(mapper.write(value),Numbers.class).amount;
                }));
            }
            for (int i=0;i<results.size();i++) assertEquals(new BigDecimal(i+".25"),results.get(i).get());
        } finally { Locale.setDefault(previous); executor.shutdownNow(); }
    }

    private static void failure(Runnable action,String path) {
        try { action.run(); fail("Expected mapping failure at "+path); }
        catch (MappingException e) { assertEquals(e.getMessage(),path,e.getPath()); }
    }
}
