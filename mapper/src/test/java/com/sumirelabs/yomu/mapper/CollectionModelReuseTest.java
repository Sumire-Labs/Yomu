package com.sumirelabs.yomu.mapper;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class CollectionModelReuseTest {
    public static class Row {
        @Json5Field(aliases={"alias"}) public String name;
        public Row child;
    }
    public static class Created {
        final String name;
        @Json5Creator public Created(@Json5Field(name="name") String name) {
            if("bad".equals(name))throw new IllegalArgumentException("bad name");
            this.name=name;
        }
    }
    @Test public void laterRowsKeepNullAliasCycleAndCreatorChecks() {
        Json5Mapper mapper=new Json5Mapper();TypeRef<List<Row>> rows=new TypeRef<List<Row>>(){};
        List<Row> valid=mapper.read("[null,{name:'a'},{alias:'b'}]",rows);
        assertNull(valid.get(0));assertEquals("a",valid.get(1).name);assertEquals("b",valid.get(2).name);
        try {mapper.read("[{name:'a'},{name:'b',alias:'c'}]",rows);fail("Duplicate alias accepted");}
        catch(MappingException expected){assertEquals("$[1][\"alias\"]",expected.getPath());}
        Map<String,Object> cycle=new LinkedHashMap<String,Object>();cycle.put("child",cycle);
        try {mapper.convert(Arrays.asList(Collections.singletonMap("name","a"),cycle),rows);fail("Cycle accepted");}
        catch(MappingException expected){assertEquals("$[1][\"child\"]",expected.getPath());}
        try {mapper.read("[{name:'ok'},{name:'bad'}]",new TypeRef<List<Created>>(){});fail("Creator failure lost");}
        catch(MappingException expected){assertEquals("$[1]",expected.getPath());assertTrue(expected.getCause() instanceof IllegalArgumentException);}
    }
}
