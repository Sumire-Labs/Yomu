package com.sumirelabs.yomu.mapper;

import com.sumirelabs.yomu.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class PreservedMappingTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    public static class Config {
        @Json5Field(name="ticks",aliases={"old_ticks"}) public int ticks=20;
        public Child nested;public List<Child> entries;
        @Json5Field(write=false) public String token;
        @Json5Field(write=false) public Object hidden;
    }
    public static class Child {public String name;}
    public interface Pet {}
    public static class Dog implements Pet {public String name;public Pet friend;}
    public static class Box {public Pet a,b;}
    public static class Immutable {
        public final int ticks;
        @Json5Creator public Immutable(@Json5Field(name="ticks") int ticks){this.ticks=ticks;}
    }
    public static class Opaque {int value;}
    public static class Coded {public Child child;public int count;}
    public static class CodecBox {public Coded coded;public Child other;}
    public static class Singleton {
        static final Singleton INSTANCE=new Singleton();public String name;
        @Json5Creator public static Singleton make(@Json5Field(name="name") String name){INSTANCE.name=name;return INSTANCE;}
    }
    @Test public void scalarAndNestedUpdatesPreserveUnknownFieldsAliasesAndComments() {
        Json5Mapper mapper=new Json5Mapper();String source="// header\n{old_ticks:20, /* nested */ nested:{name:'old',future:42},unknown:{x:1},token:'original'}";
        MappedDocument<Config> doc=mapper.readDocument(source,Config.class);assertEquals(source,doc.stringify());
        doc.getValue().ticks=40;doc.getValue().nested.name="new";doc.getValue().token="not written";
        String text=doc.stringify();Json5Object wire=Yomu.readObject(text);
        assertEquals(40,wire.getInt("old_ticks"));assertFalse(wire.containsKey("ticks"));assertEquals(42,wire.getObject("nested").getInt("future"));
        assertEquals("original",wire.getString("token"));assertEquals(1,wire.getObject("unknown").getInt("x"));
        assertTrue(text.contains("// header"));assertTrue(text.contains("/* nested */"));assertEquals(40,mapper.read(text,Config.class).ticks);
        assertFalse(mapper.write(doc.getValue()).contains("unknown"));assertFalse(mapper.write(doc.getValue()).contains("original"));
    }
    @Test public void arrayReorderingKeepsUnknownDataWithTheOriginalPojo() {
        Json5Mapper mapper=new Json5Mapper();TypeRef<List<Child>> type=new TypeRef<List<Child>>(){};
        MappedDocument<List<Child>> doc=mapper.readDocument("[{name:'same',future:1},/*two*/{name:'same',future:2}]",type);
        Collections.reverse(doc.getValue());List<?> wire=(List<?>)Yomu.parse(doc.stringify());
        assertEquals(2L,((Map<?,?>)wire.get(0)).get("future"));assertEquals(1L,((Map<?,?>)wire.get(1)).get("future"));
        doc.getValue().get(0).name="changed";assertEquals("changed",new Json5Mapper().read(doc.stringify(),type).get(0).name);
    }
    @Test public void defaultsAreNotInsertedWithoutAnEditAndReplacementRootsKeepUnknowns() {
        MappedDocument<Config> doc=new Json5Mapper().readDocument("{/*keep*/ future:1}",Config.class);
        assertEquals("{/*keep*/ future:1}",doc.stringify());Config replacement=new Config();replacement.ticks=80;doc.setValue(replacement);
        Json5Object result=Yomu.readObject(doc.stringify());assertEquals(1,result.getInt("future"));assertEquals(80,result.getInt("ticks"));
    }
    @Test public void strictOutputAndSafeSavingRetainDataWithoutOutputtingComments() throws Exception {
        Json5Mapper mapper=new Json5Mapper().withStrictJson(true).withReadOptions(Json5ReadOptions.DEFAULT);
        MappedDocument<Config> doc=mapper.readDocument("//keep\n{ticks:20,future:1}",Config.class);doc.getValue().ticks=30;
        Path file=folder.getRoot().toPath().resolve("config.json");doc.writeAtomicallyTo(file);
        String text=new String(Files.readAllBytes(file),StandardCharsets.UTF_8);assertFalse(text.contains("keep"));
        assertEquals(1,Yomu.readObject(text,Json5ReadOptions.DEFAULT.withStrictJson(true)).getInt("future"));
        assertTrue(doc.getSource().contains("//keep"));
    }
    @Test public void registeredPolymorphicPayloadsKeepUnknownMembers() {
        Json5Mapper mapper=new Json5Mapper().withType("dog",Dog.class);Dog dog=new Dog();dog.name="old";
        Json5Object tree=Yomu.readObject(mapper.writeAs(dog,Pet.class));tree.getObject("value").with("future",42);
        String source="//pet\n"+Yomu.stringify(tree);MappedDocument<Pet> doc=mapper.readDocument(source,Pet.class);((Dog)doc.getValue()).name="new";
        String output=doc.stringify();assertTrue(output.contains("//pet"));assertEquals(42,Yomu.readObject(output).getObject("value").getInt("future"));
        assertEquals("new",((Dog)mapper.read(output,Pet.class)).name);
    }
    @Test public void reservedLookingUnknownDataIsNotMistakenForAnEnvelope() {
        MappedDocument<Config> doc=new Json5Mapper().readDocument("{$thermometer:'type/1',type:'literal',value:{future:1}}",Config.class);
        doc.getValue().ticks=99;Json5Object output=Yomu.readObject(doc.stringify());assertEquals(99,output.getInt("ticks"));assertEquals(1,output.getObject("value").getInt("future"));
    }
    @Test public void literalEnvelopesAndCreatorRootsPreserveTheirOwnPayloads() {
        Json5Mapper registered=new Json5Mapper().withType("dog",Dog.class);
        MappedDocument<Config> literal=registered.readDocument("{$thermometer:'literal/1',value:{$thermometer:'type/1',type:'literal',value:{future:1}}}",Config.class);
        literal.getValue().ticks=55;Json5Object body=Yomu.readObject(literal.stringify()).getObject("value");
        assertEquals(55,body.getInt("ticks"));assertEquals(1,body.getObject("value").getInt("future"));
        for(boolean graph:new boolean[]{false,true}) {
            Json5Mapper mapper=new Json5Mapper().withObjectGraph(graph);Object wire=mapper.toValue(new Immutable(1));
            if(graph)((Map<String,Object>)((Map<?,?>)((List<?>)((Map<?,?>)wire).get("nodes")).get(0)).get("value")).put("future",9);
            else ((Map<String,Object>)wire).put("future",9);
            MappedDocument<Immutable> doc=mapper.readDocument(Yomu.stringify(wire),Immutable.class);doc.setValue(new Immutable(2));
            String output=doc.stringify();assertEquals(2,mapper.read(output,Immutable.class).ticks);assertTrue(output.contains("future"));
        }
    }
    @Test public void changedCodecPayloadsRemainOwnedByTheirCodec() {
        Json5Mapper mapper=new Json5Mapper().withCodec(Opaque.class,new Codec<Opaque>() {
            public Opaque decode(Object value,CodecContext context){Opaque out=new Opaque();out.value=((Number)((Map<?,?>)value).get("value")).intValue();((Map<?,?>)value).remove("future");return out;}
            public Object encode(Opaque value,CodecContext context){return Collections.singletonMap("value",value.value);}
        });
        MappedDocument<Opaque> doc=mapper.readDocument("{/*comment*/value:1,future:9}",Opaque.class);
        assertTrue(doc.stringify().contains("future"));doc.getValue().value=2;String output=doc.stringify();
        assertEquals(2,mapper.read(output,Opaque.class).value);assertFalse(output.contains("future"));assertTrue(output.contains("/*comment*/"));
    }
    @Test public void graphIdsUnknownReferencesAndSharingSurviveChanges() {
        Json5Mapper mapper=new Json5Mapper().withObjectGraph(true).withType("dog",Dog.class);
        Dog dog=new Dog();dog.name="old";dog.friend=dog;Box box=new Box();box.a=dog;box.b=dog;
        Json5Object graph=Yomu.readObject(mapper.write(box));Json5Array nodes=graph.getArray("nodes");
        int dogId=-1;for(int i=0;i<nodes.size();i++)if(nodes.getObject(i).containsKey("type"))dogId=i;
        nodes.getObject(dogId).getObject("value").with("future",42);
        nodes.add(Yomu.object(Yomu.entry("kind","map"),Yomu.entry("value",Yomu.object(Yomu.entry("archived",7)))));
        nodes.getObject(dogId).getObject("value").with("unknownRef",Yomu.object(Yomu.entry("$ref",nodes.size()-1)));
        String source="//graph\n"+Yomu.stringify(graph);MappedDocument<Box> doc=mapper.readDocument(source,Box.class);
        ((Dog)doc.getValue().a).name="new";String output=doc.stringify();Json5Object stored=Yomu.readObject(output);
        assertEquals(42,stored.getArray("nodes").getObject(dogId).getObject("value").getInt("future"));
        assertEquals(nodes.size(),stored.getArray("nodes").size());assertTrue(output.contains("//graph"));
        Box restored=mapper.read(output,Box.class);assertSame(restored.a,restored.b);assertSame(restored.a,((Dog)restored.a).friend);assertEquals("new",((Dog)restored.a).name);
        Box replacement=new Box();Dog next=new Dog();next.name="replacement";next.friend=next;replacement.a=next;replacement.b=next;doc.setValue(replacement);
        String changed=doc.stringify();Box restoredAgain=mapper.read(changed,Box.class);assertSame(restoredAgain.a,restoredAgain.b);assertSame(restoredAgain.a,((Dog)restoredAgain.a).friend);
        assertEquals(42,Yomu.readObject(changed).getArray("nodes").getObject(dogId).getObject("value").getInt("future"));
    }
    @Test public void failedMappingOrSerializationDoesNotReplaceTheFile() throws Exception {
        try{new Json5Mapper().withFailOnUnknownFields(true).readDocument("{unknown:1}",Config.class);fail();}catch(MappingException expected){}
        Json5Mapper mapper=new Json5Mapper().withStrictJson(true);
        MappedDocument<Double> doc=mapper.readDocument("1.0",Double.class);doc.setValue(Double.NaN);
        Path file=folder.getRoot().toPath().resolve("saved.json");Files.write(file,"original".getBytes(StandardCharsets.UTF_8));
        try{doc.writeAtomicallyTo(file);fail();}catch(IllegalArgumentException expected){}
        assertEquals("original",new String(Files.readAllBytes(file),StandardCharsets.UTF_8));
    }
    @Test public void entryMapsMatchKeysRatherThanOriginalPositions() {
        Json5Mapper mapper=new Json5Mapper().withMapEncoding(MapEncoding.ENTRY_ARRAY);
        TypeRef<SortedMap<Integer,Immutable>> type=new TypeRef<SortedMap<Integer,Immutable>>(){};
        MappedDocument<SortedMap<Integer,Immutable>> doc=mapper.readDocument("[[2,{ticks:20,future:'two'}],[1,{ticks:10,future:'one'}]]",type);
        doc.getValue().put(2,new Immutable(21));String text=doc.stringify();List<?> rows=(List<?>)Yomu.parse(text);
        for(Object row:rows){List<?> pair=(List<?>)row;Map<?,?> value=(Map<?,?>)pair.get(1);assertEquals(((Number)pair.get(0)).intValue()==1?"one":"two",value.get("future"));}
        assertEquals(21,mapper.read(text,type).get(2).ticks);
    }
    @Test public void inputOnlyObjectMutationsCannotChangeTheRetainedSource() {
        MappedDocument<Config> doc=new Json5Mapper().readDocument("{ticks:20,hidden:{secret:'old'},future:1}",Config.class);
        ((Map<String,Object>)doc.getValue().hidden).put("secret","new");doc.getValue().ticks=30;
        Json5Object output=Yomu.readObject(doc.stringify());assertEquals("old",output.getObject("hidden").getString("secret"));assertEquals(30,output.getInt("ticks"));
    }
    @Test public void sharedFactoryResultsUsePositionsInsteadOfTransplantingUnknownData() {
        TypeRef<List<Singleton>> type=new TypeRef<List<Singleton>>(){};
        MappedDocument<List<Singleton>> doc=new Json5Mapper().readDocument("[{name:'a',future:1},{name:'b',future:2}]",type);
        assertSame(doc.getValue().get(0),doc.getValue().get(1));doc.getValue().get(0).name="new";
        List<?> output=(List<?>)Yomu.read(doc.stringify());assertEquals(1L,((Map<?,?>)output.get(0)).get("future"));assertEquals(2L,((Map<?,?>)output.get(1)).get("future"));
        Json5Mapper graph=new Json5Mapper().withObjectGraph(true);Singleton a=new Singleton(),b=new Singleton();a.name="a";b.name="b";
        try{graph.readDocument(graph.writeAs(Arrays.asList(a,b),type),type);fail("Ambiguous graph identities accepted");}catch(MappingException expected){}
    }
    @Test public void graphRebasingDoesNotRewriteLiteralReferencesInsideCodecs() {
        Json5Mapper mapper=new Json5Mapper().withObjectGraph(true).withCodec(Coded.class,new Codec<Coded>() {
            public Coded decode(Object value,CodecContext context) {
                Map<?,?> map=(Map<?,?>)value;assertEquals(77,((Number)((Map<?,?>)map.get("literal")).get("$ref")).intValue());
                Coded result=new Coded();result.child=context.read(map.get("child"),Child.class);result.count=((Number)map.get("count")).intValue();return result;
            }
            public Object encode(Coded value,CodecContext context) {
                Map<String,Object> result=new LinkedHashMap<String,Object>();result.put("child",context.toValue(value.child));result.put("count",value.count);
                result.put("literal",Collections.singletonMap("$ref",77));return result;
            }
        });
        CodecBox value=new CodecBox();value.other=new Child();value.other.name="old";value.coded=new Coded();value.coded.child=value.other;value.coded.count=1;
        Map<String,Object> wire=(Map<String,Object>)mapper.toValue(value);List<Object> nodes=(List<Object>)wire.get("nodes");
        for(Object node:nodes){Map<?,?> payload=(Map<?,?>)((Map<?,?>)node).get("value");if(payload.containsKey("name"))((Map<String,Object>)payload).put("future",42);}
        Collections.reverse(nodes);reverseRefs(wire,nodes.size());
        MappedDocument<CodecBox> doc=mapper.readDocument(Yomu.stringify(wire),CodecBox.class);doc.getValue().coded.count=2;doc.getValue().other.name="new";
        String result=doc.stringify();CodecBox restored=mapper.read(result,CodecBox.class);assertSame(restored.other,restored.coded.child);assertEquals(2,restored.coded.count);assertEquals("new",restored.other.name);
        assertTrue(result.contains("future"));
    }
    private static void reverseRefs(Object value,int size) {
        if(value instanceof Map) {
            Map<String,Object> map=(Map<String,Object>)value;Object id=map.get("$ref");
            if(map.size()==1&&id instanceof Number&&((Number)id).intValue()<size)map.put("$ref",size-1-((Number)id).intValue());
            else for(Object child:map.values())reverseRefs(child,size);
        }else if(value instanceof List)for(Object child:(List<?>)value)reverseRefs(child,size);
    }
}
