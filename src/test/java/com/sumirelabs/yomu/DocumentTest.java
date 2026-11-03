package com.sumirelabs.yomu;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class DocumentTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    @Test public void untouchedJson5IsExactAndNestedEditsKeepUnknownValuesAndComments() {
        String source="\ufeff// header\r\n{\r\n  ticks /* key */ : 0x14, // tick comment\r\n  nested: {known:'old', /* future */ future: [1,2,],},\r\n} // end";
        Json5Document doc=Json5Document.read(source);assertEquals(source,doc.stringify());
        doc.getObject().with("ticks",40);doc.getObject().getObject("nested").with("known","new");
        String output=doc.stringify();Json5Object values=Yomu.readObject(output);
        assertEquals(40,values.getInt("ticks"));assertEquals("new",values.getObject("nested").getString("known"));
        assertEquals(2,values.getObject("nested").getArray("future").size());
        for(String comment:new String[]{"// header","/* key */","// tick comment","/* future */","// end"})assertTrue(output.contains(comment));
        assertEquals(source,doc.getSource());assertEquals(output,doc.stringify());
    }
    @Test public void additionsRemovalsAndTypeChangesKeepRealCommentsWithoutRevivingRemovedData() {
        Json5Document doc=Json5Document.read("{/*root*/ a: {secret:'/*not a comment*/',/*inside*/ x:1}, // after\n b:[1,/*list*/2,],/*tail*/}");
        doc.getObject().remove("a");doc.getObject().getArray("b").remove(0);doc.getObject().with("new",3);
        String output=doc.stringify();Map<?,?> result=(Map<?,?>)Yomu.parse(output);
        assertFalse(result.containsKey("a"));assertFalse(output.contains("not a comment"));assertEquals(Arrays.asList(2L),result.get("b"));
        for(String comment:new String[]{"/*root*/","/*inside*/","// after","/*list*/","/*tail*/"})assertTrue(output.contains(comment));
        Json5Document scalar=doc.withValue(true);String replaced=scalar.stringify();assertEquals(Boolean.TRUE,Yomu.parse(replaced));assertTrue(replaced.contains("/*inside*/"));
    }
    @Test public void duplicateEffectiveKeysUpdateAndDeleteConsistently() {
        Json5Document doc=Json5Document.read("{a:{old:1},/*duplicate*/a:2,b:3,}");doc.getObject().with("a",4);
        assertEquals(4,Yomu.readObject(doc.stringify()).getInt("a"));assertTrue(doc.stringify().contains("a:{old:1}"));
        doc.getObject().remove("a");assertFalse(Yomu.readObject(doc.stringify()).containsKey("a"));assertTrue(doc.stringify().contains("/*duplicate*/"));
    }
    @Test public void strictOutputDropsCommentsButRetainsUnknownDataAndRejectsInvalidValues() {
        Json5Document doc=Json5Document.read("// comment\n{known:1,future:2,}");
        String json=doc.stringify(new Json5Writer().withStrictJson(true));assertFalse(json.contains("comment"));assertEquals(2,Yomu.readObject(json,Json5ReadOptions.DEFAULT.withStrictJson(true)).getInt("future"));
        assertTrue(doc.stringify().contains("// comment"));
        doc.getObject().with("bad",Double.NaN);try{doc.stringify(new Json5Writer().withStrictJson(true));fail();}catch(IllegalArgumentException expected){}
        try{Json5Document.read("{/*x*/\"a\":1}",Json5ReadOptions.DEFAULT.withStrictJson(true));fail();}catch(Json5Exception expected){}
    }
    @Test public void readerOwnershipUtf8AndAtomicFailuresKeepTheirContracts() throws Exception {
        final boolean[] closed={false};Reader input=new StringReader("// keep\n{x:'日本語'}"){public void close(){closed[0]=true;}};
        Json5Document doc=Json5Document.read(input);assertFalse(closed[0]);
        Path file=folder.getRoot().toPath().resolve("settings.json5");doc.writeAtomicallyTo(file);assertEquals(doc.getSource(),new String(Files.readAllBytes(file),StandardCharsets.UTF_8));
        doc.getObject().with("bad",Double.NaN);try{doc.writeAtomicallyTo(file,new Json5Writer().withStrictJson(true));fail();}catch(IllegalArgumentException expected){}
        assertEquals(doc.getSource(),new String(Files.readAllBytes(file),StandardCharsets.UTF_8));
        try{Json5Document.read(new ByteArrayInputStream(new byte[]{(byte)0xc3,0x28}));fail();}catch(IOException expected){}
        Json5Document unpaired=Json5Document.read("//\ud800\n{x:1}");try{unpaired.writeAtomicallyTo(file);fail();}catch(IOException expected){}
        assertEquals(doc.getSource(),new String(Files.readAllBytes(file),StandardCharsets.UTF_8));
    }
    @Test public void editedContainersCannotIntroduceCyclesOrInvalidKeys() {
        Json5Document doc=Json5Document.read("{a:1}");doc.getObject().with("cycle",doc.getValue());
        try{doc.stringify();fail();}catch(IllegalArgumentException expected){}
        assertEquals("{a:1}",doc.getSource());
    }
    @Test public void triviaAndStringsSurviveDifferentStructuralEdits() {
        String[] trivia={""," ","\r\n","/* a,b */","// a,b\n","\u2028","\ufeff"};
        for(String t:trivia)for(String root:new String[]{"{"+t+"a:"+t+"'//not comment',"+t+"b:2,"+t+"}","["+t+"1,"+t+"'a\\\'b',"+t+"2,"+t+"]"}) {
            Json5Document doc=Json5Document.read(root);assertEquals(root,doc.stringify());
            if(doc.getValue() instanceof Map){doc.getObject().remove("a");doc.getObject().with("c",Arrays.asList(3,4));}
            else {doc.getArray().remove(0);doc.getArray().add(5);}
            Yomu.parse(doc.stringify());
        }
    }
}
