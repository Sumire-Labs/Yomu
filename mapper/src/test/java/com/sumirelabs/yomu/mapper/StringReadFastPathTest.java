package com.sumirelabs.yomu.mapper;

import org.junit.Test;
import static org.junit.Assert.*;

public class StringReadFastPathTest {
    public static final class Box<T> { public T value; }
    public static final class Bean<T> {
        private T value;
        public T getValue(){return value;}
        public void setValue(T value){this.value=value;}
    }

    @Test public void cachedInputTypesStayGenericAcrossRequestedTypesAndAccessors() {
        Json5Mapper mapper=new Json5Mapper();
        assertEquals("one",mapper.read("{value:'one'}",new TypeRef<Box<String>>(){}).value);
        assertEquals(Integer.valueOf(2),mapper.read("{value:2}",new TypeRef<Box<Integer>>(){}).value);
        assertEquals("three",mapper.read("{value:'three'}",new TypeRef<Box<String>>(){}).value);
        Json5Mapper beans=mapper.withBeanAccessors(true);
        assertEquals("four",beans.read("{value:'four'}",new TypeRef<Bean<String>>(){}).getValue());
        assertEquals(Integer.valueOf(5),beans.read("{value:5}",new TypeRef<Bean<Integer>>(){}).getValue());
    }

    @Test public void stringCodecsNullAndRegisteredEnvelopesAreStillHonored() {
        Json5Mapper codec=new Json5Mapper().withCodec(String.class,new Codec<String>() {
            public String decode(Object value,CodecContext context){return "decoded:"+value;}
            public Object encode(String value,CodecContext context){return value;}
        });
        assertEquals("decoded:one",codec.read("'one'",String.class));
        assertEquals("decoded:two",codec.read("{value:'two'}",new TypeRef<Box<String>>(){}).value);
        assertNull(new Json5Mapper().read("null",String.class));
        Json5Mapper typed=new Json5Mapper().withType("text",String.class);
        assertEquals("three",typed.read("{$thermometer:'type/1',type:'text',value:'three'}",String.class));
        assertEquals("four",typed.read("'four'",String.class));
        try {typed.read("123",String.class);fail("Number accepted as String");}catch(MappingException expected){}
    }
}
