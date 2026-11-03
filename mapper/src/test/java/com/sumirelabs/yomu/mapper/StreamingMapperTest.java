package com.sumirelabs.yomu.mapper;

import com.sumirelabs.yomu.*;
import java.io.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class StreamingMapperTest {
    public static class Node { public String name;public Node next;public Object extra; }
    public static class Config { public int id;public List<Integer> values; }
    public static class Named { @Json5Field(name="$thermometer",comment="literal") public String marker="type/1";public Object value; }
    public static class Amount { @Json5Field(format="0.00") public BigDecimal amount; }
    private static String stream(Json5Mapper m,Object value)throws IOException{StringWriter out=new StringWriter();m.writeStreaming(value,out);return out.toString();}
    private static <T>T read(Json5Mapper m,String source,Class<T> type)throws IOException{return m.readStreaming(new StringReader(source),type);}
    @Test public void ordinaryPojoContainersAndDuplicateKeysMatchExistingMapping()throws Exception {
        Json5Mapper m=new Json5Mapper();Config c=read(m,"{id:1,id:9,values:[1,2,3]}",Config.class);assertEquals(9,c.id);assertEquals(Arrays.asList(1,2,3),c.values);
        String text=stream(m,c);assertEquals(m.write(c),text);assertEquals(c.values,read(m,text,Config.class).values);
        Map<?,?> raw=read(m,"{a:1,b:[2],a:3}",Map.class);assertEquals(Arrays.asList("a","b"),new ArrayList<Object>(raw.keySet()));assertEquals(3L,raw.get("a"));assertEquals(Arrays.asList(2L),raw.get("b"));
        assertEquals(Arrays.asList(1L,Collections.singletonMap("x",2L)),read(m,"[1,{x:2}]",Object.class));
    }
    @Test public void polymorphismGraphsAndObjectFirstReferencesRoundTrip()throws Exception {
        Node n=new Node();n.name="root";n.next=n;n.extra=n;
        Json5Mapper m=new Json5Mapper().withType("node",Node.class).withObjectGraph(true);
        Node copy=(Node)read(m,stream(m,n),Object.class);assertSame(copy,copy.next);assertSame(copy,copy.extra);
        List<Node> shared=Arrays.asList(n,n);TypeRef<List<Node>> type=new TypeRef<List<Node>>(){};StringWriter out=new StringWriter();m.writeAsStreaming(shared,type,out);
        List<Node> result=m.readStreaming(new StringReader(out.toString()),type);assertSame(result.get(0),result.get(1));assertSame(result.get(0),result.get(0).next);
        assertEquals(Yomu.parse(m.write(n)),Yomu.parse(stream(m,n)));
    }
    @Test public void graphMemberOrderAndForwardReferencesAreUnrestricted()throws Exception {
        Json5Mapper m=new Json5Mapper().withObjectGraph(true).withType("node",Node.class);
        String source="{nodes:[{value:{next:{$ref:1}},type:'node',kind:'object'},{value:{next:{$ref:0}},kind:'object',type:'node'}],root:{$ref:0},$thermometer:'graph/1'}";
        Node n=(Node)read(m,source,Object.class);assertSame(n,n.next.next);
        String typed="{value:{name:'late'},type:'node',$thermometer:'type/1'}";
        assertEquals("late",read(new Json5Mapper().withType("node",Node.class),typed,Node.class).name);
    }
    @Test public void creatorBeansStaticsAndFieldFormatsUseTheSameModels()throws Exception {
        Json5Mapper m=new Json5Mapper().withBeanAccessors(true);
        Amount amount=new Amount();amount.amount=new BigDecimal("1.25");assertEquals(amount.amount,read(m,stream(m,amount),Amount.class).amount);
        Named named=new Named();named.value=Arrays.asList(1,2);Json5Mapper typed=m.withType("node",Node.class);
        Named copy=read(typed,stream(typed,named),Named.class);assertEquals("type/1",copy.marker);assertEquals(Arrays.asList(1L,2L),copy.value);
        Json5Mapper creator=new Json5Mapper().withType("immutable",PolymorphismTest.Immutable.class);
        assertEquals(4,((PolymorphismTest.Immutable)read(creator,stream(creator,new PolymorphismTest.Immutable("x",4)),Object.class)).age);
        PolymorphismTest.Statics.animal=new PolymorphismTest.Dog();Json5Mapper statics=new Json5Mapper().withType("dog",PolymorphismTest.Dog.class);
        String out=stream(statics,new PolymorphismTest.Statics());assertTrue(out.contains("dog"));Object before=PolymorphismTest.Statics.animal;read(statics,out,PolymorphismTest.Statics.class);assertSame(before,PolymorphismTest.Statics.animal);
    }
    @Test public void exactBigDecimalsStrictJsonAndEntryKeysArePreserved()throws Exception {
        Json5Mapper m=new Json5Mapper().withStrictJson(true).withBigDecimals(true).withMapEncoding(MapEncoding.ENTRY_ARRAY);
        TypeRef<Map<Integer,BigDecimal>> type=new TypeRef<Map<Integer,BigDecimal>>(){};String source="[[7,0.123456789012345678900]]";
        Map<Integer,BigDecimal> map=m.readStreaming(new StringReader(source),type);assertEquals(new BigDecimal("0.123456789012345678900"),map.get(7));
        StringWriter out=new StringWriter();m.writeAsStreaming(map,type,out);Yomu.parse(out.toString(),Json5ReadOptions.DEFAULT.withStrictJson(true));assertEquals(map,m.readStreaming(new StringReader(out.toString()),type));
        try{m.readStreaming(new StringReader("/*x*/[]"),type);fail();}catch(Json5Exception expected){}
        try{stream(m,Double.NaN);fail();}catch(IllegalArgumentException expected){}
        for(String number:Arrays.asList("1e2147483648","1e-2147483647","-0.00","9223372036854775808"))assertEquals(m.read(number,Number.class),read(m,number,Number.class));
    }
    @Test public void codecContextsCanEnqueueGraphNodesAndDecodeDetachedValues()throws Exception {
        Codec<Node> codec=new Codec<Node>() {
            public Object encode(Node n,CodecContext c){Map<String,Object> out=new LinkedHashMap<String,Object>();out.put("extra",c.atField("extra").toValue(n.extra));return out;}
            public Node decode(Object value,CodecContext c){Node n=new Node();n.extra=c.atField("extra").read(((Map<?,?>)value).get("extra"),Object.class);return n;}
        };
        Json5Mapper m=new Json5Mapper().withObjectGraph(true).withCodec(Node.class,codec);Node n=new Node();n.extra=Arrays.asList(1,"x");
        Node result=read(m,stream(m,n),Node.class);assertEquals(Arrays.asList(1L,"x"),result.extra);
        Codec<Map> pass=new Codec<Map>(){public Object encode(Map v,CodecContext c){return v;}public Map decode(Object v,CodecContext c){return (Map)v;}};
        Map<?,?> detached=read(new Json5Mapper().withCodec(Map.class,pass),"{x:[1,{a:2}]}",Map.class);assertEquals(2L,((Map<?,?>)((List<?>)detached.get("x")).get(1)).get("a"));
    }
    @Test public void perValueMappingProcessesAnArrayWithoutRetainingAllRecords()throws Exception {
        Json5Mapper m=new Json5Mapper();StringWriter sink=new StringWriter();Json5StreamWriter output=new Json5Writer().stream(sink);
        try(Json5StreamReader input=Yomu.stream(new StringReader("[{id:1,values:[1]},{id:2,values:[2]}]"))) {
            input.beginArray();output.beginArray();int count=0;
            while(input.hasNext()){Config record=m.readValue(input,Config.class);assertEquals(++count,record.id);m.writeValue(output,record);}
            input.endArray();input.requireEnd();output.endArray();output.finish();assertEquals(2,count);
        }
        assertEquals(2,((List<?>)Yomu.parse(sink.toString())).size());
    }
    @Test public void ioOwnershipPathAndFailureCleanupAreMaintained()throws Exception {
        Json5Mapper m=new Json5Mapper();Path file=Files.createTempFile("yomu-mapped-stream-",".json5");
        try{Config c=new Config();c.id=7;m.writeStreaming(file,c);assertEquals(7,m.readStreaming(file,Config.class).id);}finally{Files.delete(file);}
        Set<String> before=spools();try{read(m,"{id:[",Config.class);fail();}catch(Json5Exception expected){}assertEquals(before,spools());
        try{read(m,"{id:'bad'}",Config.class);fail();}catch(MappingException expected){}assertEquals(before,spools());
        Reader broken=new Reader(){public int read(char[] b,int o,int n)throws IOException{throw new IOException("spool input failed");}public void close(){}};
        try{m.readStreaming(broken,Config.class);fail();}catch(IOException e){assertEquals("spool input failed",e.getMessage());}assertEquals(before,spools());
    }
    private static Set<String> spools()throws IOException {Set<String> names=new HashSet<String>();try(DirectoryStream<Path> files=Files.newDirectoryStream(Paths.get(System.getProperty("java.io.tmpdir")),StreamingValues.tempPrefix()+"*.bin")){for(Path path:files)names.add(path.getFileName().toString());}return names;}
    @Test public void invalidReferencesCreatorsCyclesAndModeMismatchRemainErrors()throws Exception {
        Json5Mapper graph=new Json5Mapper().withObjectGraph(true);
        try{read(graph,"{$thermometer:'graph/1',root:{$ref:3},nodes:[]}",Node.class);fail();}catch(MappingException expected){}
        Node n=new Node();n.next=n;try{stream(new Json5Mapper(),n);fail();}catch(MappingException e){assertTrue(e.getMessage().contains("Cyclic"));}
        try{new Json5Mapper().withStrictJson(true).readValue(Yomu.stream(new StringReader("{}")),Config.class);fail();}catch(IllegalArgumentException expected){}
        try{new Json5Mapper().withStrictJson(true).writeValue(new Json5StreamWriter(new StringBuilder()),new Config());fail();}catch(IllegalArgumentException expected){}
        Json5Mapper typed=new Json5Mapper().withType("node",Node.class);try{read(typed,"{type:'evil',value:{},$thermometer:'type/1'}",Node.class);fail();}catch(MappingException expected){}
    }
    @Test public void boundedHeapMapperAvoidsWireTreesAndFullOutputStrings()throws Exception {
        String java=new File(System.getProperty("java.home"),"bin/java"+(System.getProperty("os.name").startsWith("Windows")?".exe":"")).getPath();
        Process process=new ProcessBuilder(java,"-Xmx32m","-cp",System.getProperty("java.class.path"),StreamingMapperMemoryProbe.class.getName()).redirectErrorStream(true).start();
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=process.getInputStream().read(b))!=-1)out.write(b,0,n);
        assertEquals(new String(out.toByteArray(),StandardCharsets.UTF_8),0,process.waitFor());
    }
}
