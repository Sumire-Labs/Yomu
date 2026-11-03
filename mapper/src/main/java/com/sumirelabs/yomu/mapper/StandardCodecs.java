package com.sumirelabs.yomu.mapper;

import java.io.File;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.*;
import java.util.*;

/** Optional, locale-independent string representations; loaded only when requested. */
public final class StandardCodecs {
    private StandardCodecs() { }
    public static final Codec<UUID> UUID = Codecs.string(StandardCodecs::uuid,java.util.UUID::toString);
    public static final Codec<URI> URI = Codecs.string(java.net.URI::create,java.net.URI::toString);
    public static final Codec<URL> URL = Codecs.string(StandardCodecs::url,java.net.URL::toExternalForm);
    public static final Codec<Path> PATH = Codecs.string(Paths::get,Path::toString);
    public static final Codec<File> FILE = Codecs.string(File::new,File::getPath);
    public static final Codec<Date> DATE = Codecs.string(s -> Date.from(Instant.parse(s)),d -> Instant.ofEpochMilli(d.getTime()).toString());
    public static final Codec<Instant> INSTANT = Codecs.string(Instant::parse,Instant::toString);
    public static final Codec<LocalDate> LOCAL_DATE = Codecs.string(LocalDate::parse,LocalDate::toString);
    public static final Codec<LocalTime> LOCAL_TIME = Codecs.string(LocalTime::parse,LocalTime::toString);
    public static final Codec<LocalDateTime> LOCAL_DATE_TIME = Codecs.string(LocalDateTime::parse,LocalDateTime::toString);
    public static final Codec<OffsetDateTime> OFFSET_DATE_TIME = Codecs.string(OffsetDateTime::parse,OffsetDateTime::toString);
    public static final Codec<OffsetTime> OFFSET_TIME = Codecs.string(OffsetTime::parse,OffsetTime::toString);
    public static final Codec<ZonedDateTime> ZONED_DATE_TIME = Codecs.string(ZonedDateTime::parse,ZonedDateTime::toString);
    public static final Codec<Duration> DURATION = Codecs.string(Duration::parse,Duration::toString);
    public static final Codec<Period> PERIOD = Codecs.string(Period::parse,Period::toString);
    public static final Codec<Year> YEAR = Codecs.string(Year::parse,Year::toString);
    public static final Codec<YearMonth> YEAR_MONTH = Codecs.string(YearMonth::parse,YearMonth::toString);
    public static final Codec<MonthDay> MONTH_DAY = Codecs.string(MonthDay::parse,MonthDay::toString);
    public static final Codec<ZoneId> ZONE_ID = Codecs.string(ZoneId::of,ZoneId::getId);
    public static final Codec<ZoneOffset> ZONE_OFFSET = Codecs.string(ZoneOffset::of,ZoneOffset::getId);

    private static UUID uuid(String text) {
        UUID result = java.util.UUID.fromString(text);
        if (!result.toString().equalsIgnoreCase(text)) throw new IllegalArgumentException("Canonical UUID required");
        return result;
    }
    private static URL url(String text) {
        try { return new URL(text); }
        catch (java.net.MalformedURLException e) { throw new IllegalArgumentException("Invalid URL",e); }
    }

    private static final Map<Type,Codec<?>> ALL;
    static {
        Map<Type,Codec<?>> all = new LinkedHashMap<Type,Codec<?>>();
        all.put(java.util.UUID.class,UUID); all.put(java.net.URI.class,URI); all.put(java.net.URL.class,URL);
        all.put(Path.class,PATH); all.put(File.class,FILE); all.put(Date.class,DATE);
        all.put(Instant.class,INSTANT); all.put(LocalDate.class,LOCAL_DATE); all.put(LocalTime.class,LOCAL_TIME);
        all.put(LocalDateTime.class,LOCAL_DATE_TIME); all.put(OffsetDateTime.class,OFFSET_DATE_TIME); all.put(OffsetTime.class,OFFSET_TIME);
        all.put(ZonedDateTime.class,ZONED_DATE_TIME); all.put(Duration.class,DURATION); all.put(Period.class,PERIOD);
        all.put(Year.class,YEAR); all.put(YearMonth.class,YEAR_MONTH); all.put(MonthDay.class,MONTH_DAY);
        all.put(ZoneId.class,ZONE_ID); all.put(ZoneOffset.class,ZONE_OFFSET);
        ALL = Collections.unmodifiableMap(all);
    }
    static Map<Type,Codec<?>> defaults() { return ALL; }
}
