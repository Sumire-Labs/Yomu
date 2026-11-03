package com.sumirelabs.yomu.mapper;

import java.lang.reflect.Field;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class LazyStringPathTest {
    public static final class Holder { @Json5Field private String value; }
    public static final class Bounded<T extends String> { public T value; }
    @Test public void anErasedStringFieldStillRequiresResolvedGenericArguments() {
        Json5Mapper mapper=new Json5Mapper();
        assertEquals("text",mapper.read("{value:'text'}",new TypeRef<Bounded<String>>(){}).value);
        try {mapper.read("{value:'text'}",Bounded.class);fail("Unresolved generic target accepted");}
        catch(MappingException expected) {assertEquals("$[\"value\"]",expected.getPath());}
    }
    @Test public void aFailedDirectAssignmentStillReportsTheEscapedInputPath() {
        ObjectModel model=new ObjectModel(Holder.class,NamingPolicy.IDENTITY,false,Collections.<Field>emptySet());
        ObjectModel.Property field=model.fields.get("value");
        field.field.setAccessible(false);
        try {
            field.setDirect(new Holder(),"text","$[3]","a\"b\\c\n");
            fail("Private field unexpectedly accessible");
        } catch(MappingException expected) {
            assertEquals("$[3][\"a\\\"b\\\\c\\u000a\"]",expected.getPath());
            assertTrue(expected.getCause() instanceof IllegalAccessException);
        }
    }
}
