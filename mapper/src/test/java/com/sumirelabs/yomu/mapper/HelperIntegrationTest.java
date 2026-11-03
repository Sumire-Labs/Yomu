package com.sumirelabs.yomu.mapper;

import com.sumirelabs.yomu.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class HelperIntegrationTest {
    public static class Child {public String name="sumire";}
    @Test public void helperCollectionsCanBeReadAndWrittenWithoutSpecialMapperDependencies() {
        Json5Mapper mapper=new Json5Mapper().withBigDecimals(true);
        Json5Object object=mapper.read("{count:2,amount:1.2300,items:[true]}",Json5Object.class);
        assertEquals(2,object.getInt("count"));assertEquals(new BigDecimal("1.2300"),object.getBigDecimal("amount"));
        Json5Array array=mapper.read("[{count:2},false]",Json5Array.class);
        assertEquals(2,array.getObject(0).getInt("count"));assertFalse(array.getBoolean(1));
        object.with("child",new Child());
        Json5Object output=Json5Object.parse(mapper.write(object));
        assertEquals("sumire",output.getObject("child").getString("name"));
        List<Json5Object> list=mapper.read("[{count:3}]",new TypeRef<List<Json5Object>>(){});
        assertEquals(3,list.get(0).getInt("count"));
    }
    @Test public void strictModeAndStandardCodecsContinueToControlOutputOfHelpers() {
        Json5Mapper mapper=new Json5Mapper().withStandardCodecs().withStrictJson(true).withBigDecimals(true);
        UUID id=UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        Json5Object object=Yomu.object().with("id",id).with("amount",Yomu.commented("amount",new BigDecimal("1.2500")));
        String output=mapper.write(object);assertFalse(output.contains("//"));
        Json5Object parsed=Json5Object.parse(output,new Json5ReadOptions().withStrictJson(true).withBigDecimals(true));
        assertEquals(id.toString(),parsed.getString("id"));assertEquals(new BigDecimal("1.2500"),parsed.getBigDecimal("amount"));
        object.with("bad",Double.NaN);
        try {mapper.write(object);fail();}catch(IllegalArgumentException expected) { }
    }
    @Test public void stringUtilitiesCanBeUsedByCodecsWithoutChangingOuterGrammar() {
        Codec<Child> codec=Codecs.string(s -> {Child child=new Child();child.name=Json5Strings.unescape(s);return child;},child -> Json5Strings.escape(child.name));
        Json5Mapper mapper=new Json5Mapper().withStrictJson(true).withCodec(Child.class,codec);
        Child value=new Child();value.name="sumire\n🌸\\";
        assertEquals(value.name,mapper.read(mapper.write(value),Child.class).name);
    }
}
