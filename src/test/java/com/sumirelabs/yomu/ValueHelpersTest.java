package com.sumirelabs.yomu;

import java.math.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ValueHelpersTest {
    @Test public void factoriesCreateOrdinaryCollectionCompatibleHelpers() {
        Json5Object object=Yomu.object(Yomu.entry("name","sumire"),Yomu.entry("count",1),Yomu.entry("name","changed"));
        assertEquals(Arrays.asList("name","count"),new ArrayList<String>(object.keySet()));
        assertEquals("changed",object.getString("name"));assertEquals(1,object.getInt("count"));
        object.with("enabled",true).withEntry(new AbstractMap.SimpleEntry<String,Object>("nil",null));
        assertTrue(object.containsKey("nil"));assertNull(object.get("nil"));assertEquals(true,object.getBoolean("enabled"));
        Json5Array array=Yomu.array("text",object,null).addValue(4);
        assertEquals("text",array.getString(0));assertEquals(1,array.getObject(1).getInt("count"));assertEquals(4,array.getInt(3));
        assertEquals(Arrays.asList("text",object,null,4),array);
    }
    @Test public void parserDefaultsRemainPlainCollectionsAndHelperParsingIsExplicit() {
        assertEquals(LinkedHashMap.class,Yomu.parse("{}").getClass());
        assertEquals(ArrayList.class,Yomu.parse("[]").getClass());
        assertEquals(1,Json5Object.parse("{a:1,}").getInt("a"));
        assertEquals(.5,Json5Array.parse("[.5,]").getDouble(0),0);
        Json5ReadOptions json=Json5ReadOptions.DEFAULT.withStrictJson(true).withBigDecimals(true);
        assertEquals(new BigDecimal("1.2500"),Json5Object.parse("{\"a\":1.2500}",json).getBigDecimal("a"));
        assertEquals(new BigDecimal("1.2500"),Json5Array.parse("[1.2500]",json).getBigDecimal(0));
        rejected(() -> Json5Object.parse("{a:1}",json));rejected(() -> Json5Array.parse("[1,]",json));
        rejected(() -> Json5Object.parse("[]"));rejected(() -> Json5Array.parse("{}"));
    }
    @Test public void nestedViewsMutateTheOriginalTreeWithoutCopyingIt() {
        Map<String,Object> raw=Yomu.parseObject("{items:[{name:'old'}],child:{value:1}}");
        Json5Object root=Json5Object.wrap(raw);
        assertSame(raw,root.asMap());
        Json5Array items=root.getArray("items");Json5Object item=items.getObject(0);
        item.with("name","new");items.addValue(Yomu.object().with("name","second"));
        assertEquals("new",((Map<?,?>)((List<?>)raw.get("items")).get(0)).get("name"));
        assertEquals(2,((List<?>)raw.get("items")).size());
        root.getObject("child").entry("value").setValue(3);
        assertEquals(3,((Map<?,?>)raw.get("child")).get("value"));
        assertEquals("$[\"items\"][0]",item.getPath());
    }
    @Test public void entryViewsDistinguishMissingAndNullAndFollowLaterMapChanges() {
        Json5Object object=new Json5Object();Json5Entry live=object.entry("key");
        assertFalse(live.exists());assertNull(live.getValue());
        failure(() -> live.asInt(),"$[\"key\"]");
        assertNull(live.setValue(null));assertTrue(live.exists());
        failure(() -> live.asInt(),"$[\"key\"]");
        live.setValue(2);assertEquals(2,live.asInt());object.with("key",3);assertEquals(3,live.asInt());
        assertEquals(new AbstractMap.SimpleEntry<String,Object>("key",3),live);
        assertEquals(new AbstractMap.SimpleEntry<String,Object>("key",3).hashCode(),live.hashCode());
        object.remove("key");assertFalse(live.exists());live.setValue("again");assertEquals("again",object.getString("key"));
        Json5Entry detached=Yomu.entry("other",4);assertTrue(detached.exists());assertEquals(4,detached.setValue(5));
        object.withEntry(detached);detached.setValue(6);assertEquals(5,object.getInt("other"));
    }
    @Test public void typedDefaultsApplyOnlyToMissingKeysAndReportNestedPaths() {
        Json5Object root=Json5Object.parse("{items:[{a:null,b:'1'}],flag:true}");
        assertEquals(4,root.getInt("missing",4));assertEquals("fallback",root.getString("missing","fallback"));
        assertTrue(root.getBoolean("missing",true));assertEquals(4L,root.getLong("missing",4L));assertEquals(.5,root.getDouble("missing",.5),0);
        Json5Object item=root.getArray("items").getObject(0);
        failure(() -> item.getInt("a",4),"$[\"items\"][0][\"a\"]");
        failure(() -> item.getInt("b"),"$[\"items\"][0][\"b\"]");
        failure(() -> item.getString("missing"),"$[\"items\"][0][\"missing\"]");
        root.with("a\"\\\n",false);failure(() -> root.getInt("a\"\\\n"),"$[\"a\\\"\\\\\\n\"]");
    }
    @Test(timeout=10000) public void numericAccessChecksFractionsOverflowAndFiniteConversions() {
        Json5Object values=Yomu.object().with("max",Long.MAX_VALUE).with("decimal",new BigDecimal("1.2500"))
                .with("huge",new BigDecimal("1e1000000000")).with("tiny",new BigDecimal("1e-1000000000"))
                .with("rounded",(double)Long.MAX_VALUE).with("nan",Double.NaN).with("infinity",Double.POSITIVE_INFINITY);
        assertEquals(Long.MAX_VALUE,values.getLong("max"));failure(() -> values.getInt("max"),"$[\"max\"]");
        failure(() -> values.getLong("rounded"),"$[\"rounded\"]");failure(() -> values.getInt("decimal"),"$[\"decimal\"]");
        failure(() -> values.getLong("huge"),"$[\"huge\"]");failure(() -> values.getLong("tiny"),"$[\"tiny\"]");
        failure(() -> values.getDouble("huge"),"$[\"huge\"]");failure(() -> values.getBigDecimal("nan"),"$[\"nan\"]");
        assertTrue(Double.isNaN(values.getDouble("nan")));assertEquals(Double.POSITIVE_INFINITY,values.getDouble("infinity"),0);
        assertEquals(new BigDecimal("1.2500"),values.getBigDecimal("decimal"));
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE),values.getBigInteger("max"));
        assertEquals(new BigDecimal(Long.MAX_VALUE),values.getBigDecimal("max"));
        assertEquals(1,Json5Array.parse("[1.0]").getInt(0));
    }
    @Test public void standardMapAndListMutationsAndShallowCopiesRemainAvailable() {
        Json5Array array=Yomu.array(1,2);array.addAll(array);assertEquals(Arrays.asList(1,2,1,2),array);
        array.set(0,3);array.remove(1);array.add(0,4);assertEquals(Arrays.asList(4,3,1,2),array);
        array.subList(1,3).clear();assertEquals(Arrays.asList(4,2),array);
        ListIterator<Object> iterator=array.listIterator();iterator.next();iterator.set(5);iterator.add(6);assertEquals(Arrays.asList(5,6,2),array);
        Json5Array copy=array.copy();copy.set(0,7);assertEquals(5,array.getInt(0));
        Json5Object root=Yomu.object().with("array",array),clone=root.copy();
        clone.with("new",true);assertFalse(root.containsKey("new"));clone.getArray("array").set(0,8);assertEquals(8,array.getInt(0));
        root.entrySet().iterator().next().setValue("changed");assertEquals("changed",root.getString("array"));
    }
    @Test public void commentMetadataIsTransparentForTypedAccessAndModesRemainWriterControlled() {
        Json5Object object=Yomu.object().with("n",Yomu.commented("count",2))
                .with("list",Yomu.commented("items",Yomu.array(true,"x")));
        assertEquals(2,object.getInt("n"));assertEquals(2,object.entry("n").asInt());
        assertTrue(object.getArray("list").getBoolean(0));
        String json5=Yomu.stringify(object);assertTrue(json5.contains("// count"));
        String json=new Json5Writer().withStrictJson(true).stringify(object);assertFalse(json.contains("//"));
        assertEquals(2,Json5Object.parse(json,new Json5ReadOptions().withStrictJson(true)).getInt("n"));
        object.with("nan",Double.NaN);assertTrue(Yomu.stringify(object).contains("NaN"));
        rejected(() -> new Json5Writer().withStrictJson(true).stringify(object));
        Json5Array cycle=new Json5Array();cycle.add(cycle);rejected(() -> Yomu.stringify(cycle));
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    @Test public void invalidKeysAndUnmodifiableBackingsRetainClearContracts() {
        try {Yomu.entry(null,1);fail();}catch(NullPointerException expected) { }
        Map bad=new LinkedHashMap();bad.put("good",1);bad.put(2,3);
        rejected(() -> Json5Object.wrap(bad));
        Json5Object object=new Json5Object();rejected(() -> object.putAll(bad));assertTrue(object.isEmpty());
        try {object.put(null,1);fail();}catch(NullPointerException expected) { }
        Json5Object readOnly=Json5Object.wrap(Collections.<String,Object>singletonMap("a",1));
        try {readOnly.with("a",2);fail();}catch(UnsupportedOperationException expected) { }
        Json5Array list=Json5Array.wrap(Collections.<Object>unmodifiableList(Arrays.<Object>asList(1)));
        assertEquals(1,list.getInt(0));try {list.add(2);fail();}catch(UnsupportedOperationException expected) { }
        try {list.getString(4);fail();}catch(IndexOutOfBoundsException expected) { }
    }
    private static void failure(Runnable action,String path) {
        try {action.run();fail("Expected typed access error");}catch(Json5AccessException e){assertEquals(e.getMessage(),path,e.getPath());}
    }
    private static void rejected(Runnable action) {
        try {action.run();fail("Expected rejection");}catch(IllegalArgumentException expected) { }
    }
}
