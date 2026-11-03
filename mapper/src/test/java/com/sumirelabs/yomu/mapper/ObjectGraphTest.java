package com.sumirelabs.yomu.mapper;

import com.sumirelabs.yomu.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ObjectGraphTest {
    private static Json5Mapper graph(){return new Json5Mapper().withObjectGraph(true);}
    public static class Node { public String name; public Node next; public Node other; }
    public static class Holder { public List<Node> nodes; public Node[] array; public Map<String,Node> map; }
    public static class Immutable {
        public final Node node;
        @Json5Creator public Immutable(@Json5Field(name="node") Node node){this.node=node;}
    }
    public static class Pair { public Immutable a; public Immutable b; }
    public static class CreatorCycle {
        public final CreatorCycle next;
        @Json5Creator public CreatorCycle(@Json5Field(name="next") CreatorCycle next){this.next=next;}
    }
    public static class Dates { public LocalDate a; public LocalDate b; }
    public static class Commented { @Json5Field(comment="shared") public Node a; public Node b; }
    public static class DifferentAdapters {
        @Json5Field(format="uuuu/MM/dd") public LocalDate a;
        @Json5Field(format="dd-MM-uuuu") public LocalDate b;
    }
    public static class Statics { @Json5Field(staticOutput=true) public static Node first,second; }
    public static class Key {
        public int id;
        public Node link;
        @Override public int hashCode(){return id;}
        @Override public boolean equals(Object v){return v instanceof Key && ((Key)v).id==id;}
    }
    private static Node node(){Node n=new Node();n.name="sumire";return n;}
    private static void failure(Runnable action,String message) {
        try{action.run();fail("Expected "+message);}catch(MappingException e){assertTrue(e.getMessage(),e.getMessage().contains(message));}
    }
    @Test public void preservesPojoSelfMutualCyclesAndSharedChildren() {
        Node a=node(),b=node();a.next=b;b.next=a;a.other=b;
        Node copy=graph().read(graph().write(a),Node.class);
        assertSame(copy,copy.next.next);assertSame(copy.next,copy.other);assertEquals("sumire",copy.name);
        failure(()->new Json5Mapper().write(a),"Cyclic");
        assertEquals("{\"name\":\"sumire\",\"next\":null,\"other\":null}",graph().withObjectGraph(false).write(node()));
    }
    @Test public void preservesListMapArrayCyclesThroughObjectSlots() {
        List<Object> list=new ArrayList<Object>();Map<String,Object> map=new LinkedHashMap<String,Object>();Object[] array=new Object[2];
        list.add(list);list.add(map);map.put("$ref",array);array[0]=map;array[1]=list;
        List<?> copy=graph().read(graph().write(list),List.class);
        assertSame(copy,copy.get(0));Map<?,?> cm=(Map<?,?>)copy.get(1);Object[] ca=(Object[])cm.get("$ref");
        assertSame(cm,ca[0]);assertSame(copy,ca[1]);
    }
    @Test public void typedFieldsAndGenericContainersKeepIdentity() {
        Holder h=new Holder();Node n=node();n.next=n;h.nodes=Arrays.asList(n,n);h.array=new Node[]{n};h.map=new LinkedHashMap<String,Node>();h.map.put("n",n);
        Holder copy=graph().read(graph().write(h),Holder.class);
        assertSame(copy.nodes.get(0),copy.nodes.get(1));assertSame(copy.nodes.get(0),copy.array[0]);assertSame(copy.array[0],copy.map.get("n"));
        TypeRef<List<Node>> type=new TypeRef<List<Node>>(){};
        List<Node> out=graph().read(graph().writeAs(h.nodes,type),type);assertSame(out.get(0),out.get(1));assertSame(out.get(0),out.get(0).next);
    }
    @Test public void primitiveArraysAndNullScalarsWork() {
        Json5Mapper m=graph();assertArrayEquals(new int[]{1,2},m.read(m.write(new int[]{1,2}),int[].class));
        assertNull(m.read(m.write(null),Object.class));assertEquals(Integer.valueOf(4),m.read(m.write(4),Integer.class));
        assertEquals("hello",m.read(m.write("hello"),String.class));
    }
    @Test public void acyclicCreatorsAreSharedAndCreatorCyclesFailClearly() {
        Pair p=new Pair();p.a=p.b=new Immutable(node());Pair copy=graph().read(graph().write(p),Pair.class);assertSame(copy.a,copy.b);
        String wire="{$thermometer:'graph/1',root:{$ref:0},nodes:[{kind:'object',value:{next:{$ref:0}}}]}";
        failure(()->graph().read(wire,CreatorCycle.class),"creator, record or codec");
    }
    @Test public void codecsAreSharedButCannotConstructCycles() {
        Dates d=new Dates();d.a=d.b=LocalDate.of(2026,10,22);
        Json5Mapper m=graph().withStandardCodecs();Dates copy=m.read(m.write(d),Dates.class);assertSame(copy.a,copy.b);assertEquals(d.a,copy.a);
        Codec<Node> codec=new Codec<Node>() {
            public Object encode(Node n,CodecContext c){Map<String,Object> out=new LinkedHashMap<String,Object>();out.put("next",c.toValue(n.next));return out;}
            public Node decode(Object v,CodecContext c){Node n=new Node();n.next=c.read(((Map<?,?>)v).get("next"),Node.class);return n;}
        };
        Node n=node();n.next=n;Json5Mapper coded=graph().withCodec(Node.class,codec);
        failure(()->coded.read(coded.write(n),Node.class),"creator, record or codec");
    }
    @Test public void strictModeBigDecimalsAndPathRoundTrip() throws Exception {
        Json5Mapper m=graph().withStrictJson(true).withBigDecimals(true);
        TypeRef<Map<String,BigDecimal>> type=new TypeRef<Map<String,BigDecimal>>(){};
        Map<String,BigDecimal> values=new LinkedHashMap<String,BigDecimal>();values.put("n",new BigDecimal("1.23456789012345678900"));
        Path file=Files.createTempFile("yomu-graph-",".json");
        try{m.writeAs(file,values,type);assertEquals(values,m.read(file,type));}finally{Files.delete(file);}
        Yomu.parse(m.write(values),Json5ReadOptions.DEFAULT.withStrictJson(true));
        try{m.write(Double.NaN);fail();}catch(IllegalArgumentException expected){}
        assertTrue(Double.isNaN(graph().read(graph().write(Double.NaN),Double.class)));
        try{m.read("{/*comment*/}",Object.class);fail();}catch(Json5Exception expected){}
    }
    @Test public void entryMapsKeepTypedKeysAndCycles() {
        TypeRef<Map<Integer,Node>> type=new TypeRef<Map<Integer,Node>>(){};
        Json5Mapper m=graph().withMapEncoding(MapEncoding.ENTRY_ARRAY);Map<Integer,Node> input=new LinkedHashMap<Integer,Node>();Node n=node();n.next=n;input.put(7,n);input.put(8,n);
        Map<Integer,Node> copy=m.read(m.writeAs(input,type),type);assertSame(copy.get(7),copy.get(8));assertSame(copy.get(7),copy.get(7).next);
        Map<Object,Object> cyclic=new LinkedHashMap<Object,Object>();cyclic.put(1,cyclic);
        Map<?,?> out=m.read(m.write(cyclic),Map.class);assertSame(out,out.get(1L));
    }
    @Test public void completedPojoKeysWorkButCyclicKeysAndSetElementsFail() {
        Json5Mapper m=graph().withMapEncoding(MapEncoding.ENTRY_ARRAY);TypeRef<Map<Key,String>> type=new TypeRef<Map<Key,String>>(){};
        Key key=new Key();key.id=9;Map<Key,String> map=new LinkedHashMap<Key,String>();map.put(key,"ok");
        assertEquals("ok",m.read(m.writeAs(map,type),type).get(key));
        Node previous=null;
        for(int i=0;i<60;i++){Node current=node();current.next=current.other=previous;previous=current;}
        key.link=previous;
        assertEquals("ok",m.read(m.writeAs(map,type),type).get(key)); // Shared DAG key validation must not expand exponentially.
        Node n=node();n.next=n;Set<Node> set=new LinkedHashSet<Node>();set.add(n);
        TypeRef<Set<Node>> st=new TypeRef<Set<Node>>(){};
        failure(()->m.read(m.writeAs(set,st),st),"graph key/set element");
    }
    @Test public void forwardReferencesAndUnusualPropertyNamesWork() {
        String wire="{$thermometer:'graph/1',root:{$ref:1},nodes:[{kind:'object',value:{name:'a',next:{$ref:1}}},{kind:'object',value:{name:'b',next:{$ref:0},other:{$ref:0}}}]}";
        Node copy=graph().read(wire,Node.class);assertSame(copy,copy.next.next);assertSame(copy.next,copy.other);
        Map<String,Object> names=new LinkedHashMap<String,Object>();names.put("$thermometer","graph/1");names.put("nodes",names);names.put("root",names);
        Map<?,?> result=graph().read(graph().write(names),Map.class);assertSame(result,result.get("nodes"));assertSame(result,result.get("root"));
    }
    @Test public void malformedVersionsKindsAndReferencesAreRejected() {
        failure(()->graph().read("{}",Object.class),"envelope");
        for(String root:Arrays.asList("{$ref:-1}","{$ref:1}","{$ref:0.5}","{$ref:0,extra:1}","[]")) {
            failure(()->graph().read("{$thermometer:'graph/1',root:"+root+",nodes:[{kind:'list',value:[]}]}",Object.class),root.contains("0.5")?"Integer":"graph");
        }
        failure(()->graph().read("{$thermometer:'graph/2',root:null,nodes:[]}",Object.class),"version");
        failure(()->graph().read("{$thermometer:'graph/1',root:null,nodes:[{kind:'evil',value:[]}]}",Object.class),"kind");
        failure(()->graph().read("{$thermometer:'graph/1',root:null,nodes:[{kind:'list',value:[{$ref:9}]}]}",Object.class),"Dangling");
        failure(()->graph().read(graph().write(new int[]{1}),Node.class),"kind");
    }
    @Test public void incompatibleReferenceTypesAreRejected() {
        String wire="{$thermometer:'graph/1',root:{$ref:0},nodes:[{kind:'object',value:{nodes:{$ref:1},array:{$ref:1}}},{kind:'list',value:[]}]}";
        failure(()->graph().read(wire,Holder.class),"incompatible target");
    }
    @Test public void staticsShareReferencesAndInputCannotMutateThem() {
        Statics.first=Statics.second=node();Statics.first.next=Statics.first;
        Json5Mapper m=graph();Map<?,?> out=m.read(m.writeStatics(Statics.class),Map.class);
        assertSame(out.get("first"),out.get("second"));assertSame(out.get("first"),((Map<?,?>)out.get("first")).get("next"));
        Node original=Statics.first;m.read(m.writeStatics(Statics.class),Statics.class);assertSame(original,Statics.first);
    }
    @Test public void optionsAreImmutableAndPreservedInEitherOrder() {
        Json5Mapper m=graph().withFailOnUnknownFields(true).withBeanAccessors(true).withNamingStrategy(NamingPolicy.SNAKE_CASE)
                .withReadOptions(Json5ReadOptions.DEFAULT).withWriter(new Json5Writer()).withStaticField(Statics.class,"first")
                .withMapEncoding(MapEncoding.OBJECT).withStandardCodecs().withStrictJson(true).withBigDecimals(true);
        Node n=node();n.next=n;assertSameCycle(m,n);assertSameCycle(new Json5Mapper().withStandardCodecs().withObjectGraph(true),n);
        Map<?,?> ordinary=Yomu.parseObject(new Json5Mapper().write(node()));assertFalse(ordinary.containsKey("$thermometer"));
    }
    @Test public void convertedTreesCommentsAndCustomScalarCodecsWork() {
        Commented c=new Commented();c.a=c.b=node();c.a.next=c.a;
        Json5Mapper m=graph();Commented copy=m.convert(m.toValue(c),Commented.class);assertSame(copy.a,copy.b);assertSame(copy.a,copy.a.next);
        Codec<String> strings=new Codec<String>() {
            public Object encode(String value,CodecContext context){return Collections.singletonList(value);}
            public String decode(Object value,CodecContext context){return (String)((List<?>)value).get(0);}
        };
        Json5Mapper coded=m.withCodec(String.class,strings);assertEquals("text",coded.read(coded.write("text"),String.class));
        DifferentAdapters dates=new DifferentAdapters();dates.a=dates.b=LocalDate.of(2026,10,22);
        failure(()->m.write(dates),"incompatible codecs");
    }
    @Test public void graphStateIsIsolatedAcrossThreadsAndOperations() throws Exception {
        final Json5Mapper m=graph();java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            List<java.util.concurrent.Future<?>> jobs=new ArrayList<java.util.concurrent.Future<?>>();
            for(int i=0;i<24;i++)jobs.add(pool.submit(()->{Node n=node();n.next=n;assertSameCycle(m,n);}));
            for(java.util.concurrent.Future<?> job:jobs)job.get();
        }finally{pool.shutdownNow();}
        Node[] deep={node()};Node root=deep[0];for(int i=0;i<260;i++){deep[0].next=node();deep[0]=deep[0].next;}
        failure(()->m.write(root),"Maximum mapping depth");
        assertSameCycle(m,cyclic());
    }
    @Test public void cyclicKeysIdentityMapsAndMapEncodingMismatchAreRejected() {
        Json5Mapper m=graph().withMapEncoding(MapEncoding.ENTRY_ARRAY);
        Key key=new Key();key.link=node();key.link.next=key.link;
        TypeRef<Map<Key,String>> type=new TypeRef<Map<Key,String>>(){};Map<Key,String> source=new LinkedHashMap<Key,String>();source.put(key,"x");
        failure(()->m.read(m.writeAs(source,type),type),"graph key/set element");
        failure(()->graph().write(new IdentityHashMap<Object,Object>()),"IdentityHashMap");
        failure(()->graph().read(m.write(Collections.singletonMap("a","b")),Map.class),"Map encoding");
    }
    private static Node cyclic(){Node n=node();n.next=n;return n;}
    private static void assertSameCycle(Json5Mapper m,Node n){Node copy=m.read(m.write(n),Node.class);assertSame(copy,copy.next);}
}
