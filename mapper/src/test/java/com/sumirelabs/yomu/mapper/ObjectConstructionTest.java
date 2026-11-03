package com.sumirelabs.yomu.mapper;

import com.sumirelabs.yomu.Yomu;
import com.sumirelabs.yomu.Json5Writer;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ObjectConstructionTest {
    public static class Immutable {
        public final int count;
        @Json5Field(name="display_name",aliases={"oldName"}) public final String displayName;
        public boolean enabled;
        @Json5Creator private Immutable(@Json5Field(name="count") int count,
                @Json5Field(name="display_name") String name) {
            if(count<0)throw new IllegalStateException("negative");
            this.count=count; displayName=name;
        }
    }
    public static class Factory {
        public final String value;
        private Factory(String value){this.value=value;}
        @Json5Creator private static Factory of(@Json5Field(name="value") String value) {
            if(value.equals("null"))return null;
            if(value.equals("bad"))throw new UnsupportedOperationException("bad");
            return new Factory(value);
        }
    }
    public static class Optional {
        public final int count;
        public final String label;
        @Json5Creator public Optional(@Json5Field(name="count",required=false) int count,
                @Json5Field(name="label",required=false) String label) {this.count=count;this.label=label;}
    }
    public static class Generic<T> {
        public final T value;
        public final List<T> values;
        @Json5Creator public Generic(@Json5Field(name="value") T value,@Json5Field(name="values") List<T> values) {
            this.value=value;this.values=values;
        }
    }
    public static class GenericFactory<T> {
        public final T value;
        private GenericFactory(T value){this.value=value;}
        @Json5Creator public static <E> GenericFactory<E> of(@Json5Field(name="value") E value) {return new GenericFactory<E>(value);}
    }
    public static class FixedFactory<T> {
        public final T value;
        private FixedFactory(T value){this.value=value;}
        @Json5Creator public static FixedFactory<String> of(@Json5Field(name="value") String value){return new FixedFactory<String>(value);}
    }
    public static class Preferred {
        public final int value;
        public Preferred(){throw new AssertionError("No-argument constructor must not run");}
        @Json5Creator private Preferred(@Json5Field(name="value") int value){this.value=value;}
    }
    public static class Beans {
        private int count=2;
        private boolean active=true;
        private String input;
        public int getCount(){return count+1;}
        public void setCount(int count){if(count<0)throw new IllegalStateException("negative");this.count=count;}
        public boolean isActive(){return active;}
        public boolean getActive(){return false;}
        public void setActive(boolean active){this.active=active;}
        public void setInput(String value){input=value;}
        public String getStatus(){return "ready";}
    }
    public static class Annotated {
        private int value;
        @Json5Field(name="value_count",aliases={"old"},comment="count") private int getValue(){return value;}
        @Json5Field private void setValue(int n){value=n;}
        @Json5Field(format="uuuu/MM/dd") public LocalDate getDay(){return LocalDate.of(2024,2,29);}
        public void setDay(LocalDate value){throw new AssertionError("Unannotated setters are not invoked by default");}
    }
    public static class MethodCodec {
        private String value;
        @Json5Field(codec=FieldOptionsTest.Upper.class) public String getValue(){return value;}
        @Json5Field public void setValue(String value){this.value=value;}
    }
    public static class Parent<T> {
        private T value;
        public T getValue(){return value;}
        public void setValue(T value){this.value=value;}
    }
    public static class Child extends Parent<Immutable> { }
    public static class Override extends Parent<String> {
        @java.lang.Override public String getValue(){return super.getValue();}
        @java.lang.Override public void setValue(String value){super.setValue(value);}
    }
    public interface GenericInterface<T> {
        @Json5Field T getValue();
        @Json5Field void setValue(T value);
    }
    public static class InterfaceBean implements GenericInterface<Integer> {
        private Integer value;
        public Integer getValue(){return value;}
        public void setValue(Integer value){this.value=value;}
    }
    public interface DefaultValue { default String getSource(){return "default";} }
    public static class Defaults implements DefaultValue { }
    public static class Both {
        public int value;
        public int getValue(){return value+10;}
        public void setValue(int n){value=n+1;}
    }
    public static class Pending {
        public final int id;
        @Json5Creator public Pending(@Json5Field(name="id") int id){this.id=id;}
        @Json5Field(aliases={"old"}) public void setCount(int n){throw new IllegalStateException("rejected");}
    }
    public static class FormattedCreator {
        public final LocalDate day;
        @Json5Creator public FormattedCreator(@Json5Field(name="day",format="uuuu/MM/dd",aliases={"old"}) LocalDate day){this.day=day;}
    }
    public static class DisabledCreator {
        @Json5Field(read=false) public final int id;
        @Json5Creator public DisabledCreator(@Json5Field(name="id") int id){this.id=id;}
    }
    public static class Multiple {
        @Json5Creator public Multiple(){}
        @Json5Creator public Multiple(@Json5Field(name="x") int x){}
    }
    public static class NonStatic { @Json5Creator public NonStatic of(){return this;} }
    public static class WrongReturn { @Json5Creator public static String of(){return "bad";} }
    public static class Unnamed { @Json5Creator public Unnamed(int count){} }
    public static class DuplicateParams { @Json5Creator public DuplicateParams(@Json5Field(name="x") int a,@Json5Field(name="x") int b){} }
    public static class BadSetter { public void setCount(int v){} public void setCount(String v){} }
    public static class WrongTypes { public String getCount(){return "x";} public void setCount(int n){} }
    public static class Conflicting {
        @Json5Field(name="a") public int getValue(){return 1;}
        @Json5Field(name="b") public void setValue(int n){}
    }
    public static class ExplodingGetter { @Json5Field public int getValue(){throw new IllegalStateException("getter");} }

    @Test public void constructorsConsumeFinalFieldsAndAssignRemainingProperties() {
        Json5Mapper mapper=new Json5Mapper().withFailOnUnknownFields(true);
        Immutable value=mapper.read("{oldName:'sumire',count:3,enabled:true}",Immutable.class);
        assertEquals(3,value.count);assertEquals("sumire",value.displayName);assertTrue(value.enabled);
        Immutable copy=mapper.read(mapper.write(value),Immutable.class);assertEquals(value.displayName,copy.displayName);
        failure(() -> mapper.read("{count:1}",Immutable.class),"$[\"display_name\"]");
        failure(() -> mapper.read("{count:null,display_name:'x'}",Immutable.class),"$[\"count\"]");
        failure(() -> mapper.read("{count:1,display_name:'x',oldName:'y'}",Immutable.class),"$[\"oldName\"]");
        assertEquals(0,mapper.read("{}",Optional.class).count);assertNull(mapper.read("{}",Optional.class).label);
        assertEquals(0,mapper.read("{id:42}",DisabledCreator.class).id);
        assertEquals(3,mapper.read("{value:3}",Preferred.class).value);
    }

    @Test public void factoriesAndCreatorExceptionsHaveCausesAndPaths() {
        Json5Mapper mapper=new Json5Mapper();
        assertEquals("x",mapper.read("{value:'x'}",Factory.class).value);
        assertNotNull(failure(() -> mapper.read("{value:'null'}",Factory.class),"$"));
        assertTrue(failure(() -> mapper.read("{value:'bad'}",Factory.class),"$").getCause() instanceof UnsupportedOperationException);
        assertTrue(failure(() -> mapper.read("{count:-1,display_name:'x'}",Immutable.class),"$").getCause() instanceof IllegalStateException);
        failure(() -> mapper.read("{id:1,old:2}",Pending.class),"$[\"old\"]");
    }

    @Test public void genericConstructorsAndGenericFactoriesResolveConcreteTargets() {
        Json5Mapper mapper=new Json5Mapper();
        Generic<Integer> value=mapper.read("{value:3,values:[1,2]}",new TypeRef<Generic<Integer>>(){});
        assertEquals(Integer.valueOf(3),value.value);assertEquals(Integer.valueOf(2),value.values.get(1));
        GenericFactory<List<Integer>> factory=mapper.read("{value:[1,2]}",new TypeRef<GenericFactory<List<Integer>>>(){});
        assertEquals(Integer.valueOf(1),factory.value.get(0));
        failure(() -> mapper.read("{value:'x'}",new TypeRef<FixedFactory<Integer>>(){}),"$");
        FormattedCreator formatted=mapper.read("{old:'2024/02/29'}",FormattedCreator.class);
        assertEquals(LocalDate.of(2024,2,29),formatted.day);
        assertEquals("2024/02/29",Yomu.parseObject(mapper.write(formatted)).get("day"));
    }

    @Test public void beanAccessorsAreOptInAndRespectDirectionsAndBooleanPreference() {
        Json5Mapper plain=new Json5Mapper(),mapper=plain.withBeanAccessors(true).withFailOnUnknownFields(true);
        assertEquals("{}",plain.write(new Beans()));
        Beans value=mapper.read("{count:5,active:false,input:'secret',status:'ignored'}",Beans.class);
        assertEquals(6,value.getCount());assertFalse(value.isActive());assertEquals("secret",value.input);
        Map<String,Object> out=Yomu.parseObject(mapper.write(value));
        assertEquals(new HashSet<String>(Arrays.asList("count","active","status")),out.keySet());
        assertEquals(false,out.get("active"));assertEquals("ready",out.get("status"));
        assertEquals("{}",mapper.withBeanAccessors(false).write(value));
        Both both=mapper.read("{value:2}",Both.class);assertEquals(3,both.value);
        assertEquals(13L,Yomu.parseObject(mapper.write(both)).get("value"));
    }

    @Test public void annotatedMethodsAreIndependentOfBeanModeAndShareFieldOptions() {
        Json5Mapper mapper=new Json5Mapper().withFailOnUnknownFields(true);
        Annotated value=mapper.read("{old:5,day:'ignored'}",Annotated.class);
        assertEquals(5,value.getValue());String out=mapper.write(value);
        assertTrue(out.contains("// count"));assertEquals("2024/02/29",Yomu.parseObject(out).get("day"));
        MethodCodec custom=mapper.read("{value:'abc'}",MethodCodec.class);assertEquals("ABC",custom.getValue());
        assertEquals("abc",Yomu.parseObject(mapper.write(custom)).get("value"));
        assertTrue(failure(() -> mapper.write(new ExplodingGetter()),"$[\"value\"]").getCause() instanceof IllegalStateException);
        failure(() -> mapper.withBeanAccessors(true).read("{count:-1}",Beans.class),"$[\"count\"]");
    }

    @Test public void inheritedGenericsAndInterfaceDefaultsWorkWithoutJavaBeansDependency() {
        Json5Mapper mapper=new Json5Mapper().withBeanAccessors(true);
        Child child=mapper.read("{value:{count:1,display_name:'x'}}",Child.class);
        assertEquals("x",child.getValue().displayName);
        assertEquals("default",Yomu.parseObject(mapper.write(new Defaults())).get("source"));
        assertEquals("x",mapper.read("{value:'x'}",Override.class).getValue());
        InterfaceBean bean=new Json5Mapper().read("{value:3}",InterfaceBean.class);
        assertEquals(Integer.valueOf(3),bean.getValue());
    }

    @Test public void conflictingMetadataIsRejectedDeterministically() {
        Json5Mapper mapper=new Json5Mapper().withBeanAccessors(true);
        for(Class<?> type:new Class<?>[]{Multiple.class,NonStatic.class,WrongReturn.class,Unnamed.class,DuplicateParams.class,BadSetter.class,WrongTypes.class,Conflicting.class})
            failure(() -> mapper.read("{}",type),"$");
    }

    @Test public void configurationAndMetadataAreReusableAcrossThreads() throws Exception {
        final Json5Mapper mapper=new Json5Mapper().withBeanAccessors(true).withNamingStrategy(NamingPolicy.SNAKE_CASE)
                .withWriter(new Json5Writer()).withStandardCodecs().withFailOnUnknownFields(true);
        ExecutorService executor=Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> values=new ArrayList<Future<Integer>>();
            for(int i=0;i<100;i++){final int n=i;values.add(executor.submit(() -> mapper.read("{count:"+n+",display_name:'x'}",Immutable.class).count));}
            for(int i=0;i<values.size();i++)assertEquals(Integer.valueOf(i),values.get(i).get());
        } finally {executor.shutdownNow();}
    }

    private static MappingException failure(Runnable action,String path) {
        try {action.run();fail("Expected failure at "+path);throw new AssertionError();}
        catch(MappingException e){assertEquals(e.getMessage(),path,e.getPath());return e;}
    }
}
