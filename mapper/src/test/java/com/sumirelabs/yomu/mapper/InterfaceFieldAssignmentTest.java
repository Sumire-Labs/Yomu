package com.sumirelabs.yomu.mapper;

import java.lang.reflect.Field;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class InterfaceFieldAssignmentTest {
    public static final class Holder {
        public List<String> values;
        public volatile List<String> volatileValues;
        @Json5Field private List<String> hidden;
    }

    @Test public void interfaceAssignmentsStillCheckValueOwnerAndNull() {
        ObjectModel model=new ObjectModel(Holder.class,NamingPolicy.IDENTITY,false,Collections.<Field>emptySet());
        Holder holder=new Holder();List<String> values=Arrays.asList("one","two");
        for(String name:Arrays.asList("values","volatileValues","hidden")) {
            ObjectModel.Property property=model.fields.get(name);
            property.set(holder,values,"$");assertSame(values,property.get(holder,"$"));
            try {property.set(holder,"not a List","$");fail("Unchecked interface store");}
            catch(MappingException expected){assertTrue(expected.getCause() instanceof IllegalArgumentException);}
            assertSame(values,property.get(holder,"$"));
            try {property.set(new Object(),values,"$");fail("Unchecked owner");}
            catch(MappingException expected){assertTrue(expected.getCause() instanceof IllegalArgumentException);}
            property.set(holder,null,"$");assertNull(property.get(holder,"$"));
        }
    }
}
