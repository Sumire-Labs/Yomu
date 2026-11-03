package com.sumirelabs.yomu.benchmark;

import java.util.*;

/** Independent Java values and JSON5 source generation; never calls a measured library. */
public final class MatrixFixtures {
    static final String[] NAMES={"config","repeated","distinct","numbers","escapedLong","largeArray","deep","wide"};
    public static final class Config {
        public boolean enabled; public int rate; public String name,serial; public List<Integer> colors; public World world;
    }
    public static final class World {public String dimension;public int height;public double scale;}
    static int operations(String name) {
        if(name.equals("config"))return 4096;if(name.equals("escapedLong"))return 32;
        if(name.equals("largeArray"))return 8;if(name.equals("deep")||name.equals("wide"))return 256;return 512;
    }
    static boolean typed(String name){return name.equals("config")||name.equals("repeated")||name.equals("distinct");}
    static Class<?> model(String name){return name.equals("config")?Config.class:CurrentBenchmark.Document.class;}
    static String serial(long id){String s=Long.toHexString(id);return "0000000000000000".substring(s.length())+s;}
    static Map<String,Object> object(){return new LinkedHashMap<String,Object>();}
    static Map<String,Object> values(String name,long id) {
        String serial=serial(id);Map<String,Object> root=object();
        if(name.equals("config")) {
            root.put("enabled",true);root.put("rate",20+(id%8));root.put("name","すみれ Mod");root.put("serial",serial);
            root.put("colors",Arrays.asList(255L,1194684L));Map<String,Object> world=object();
            world.put("dimension","minecraft:overworld");world.put("height",256L);world.put("scale",0.5);root.put("world",world);return root;
        }
        if(name.equals("deep")) {
            Map<String,Object> at=root;for(int i=0;i<64;i++){Map<String,Object> next=object();at.put("next",next);at=next;}
            at.put("value",serial);at.put("enabled",true);return root;
        }
        if(name.equals("wide")) {for(int i=0;i<128;i++)root.put("key"+i,i%3==0?serial+"/"+i:i%3==1?(Object)(long)i:(Object)true);return root;}
        List<Object> entries=new ArrayList<Object>();root.put("entries",entries);
        if(name.equals("numbers")) {
            for(int i=0;i<64;i++)entries.add(Arrays.asList(0L,(long)i-32,(id%1024)+i,9007199254740991L,-9007199254740991L,0.125,-0.5,1.0e9,true,null));return root;
        }
        if(name.equals("escapedLong")) {
            for(int i=0;i<4;i++){StringBuilder s=new StringBuilder();for(int j=0;j<1024;j++)s.append("abc\n\tあ\"\\");entries.add(s.append('/').append(serial).append('/').append(i).toString());}return root;
        }
        int count=name.equals("largeArray")?1024:64;
        for(int i=0;i<count;i++) {
            String suffix="/"+serial+(!name.equals("repeated")?"/"+(i<10?"0":"")+i:"");
            Map<String,Object> row=object();row.put("text","escaped\nline\tvalueAB"+suffix);row.put("path","assets\\sumire"+suffix);row.put("label","日本語とEnglish"+suffix);entries.add(row);
        }
        return root;
    }
    static Object pojo(String name,long id) {
        Map<String,Object> value=values(name,id);
        if(!name.equals("config"))return CurrentBenchmark.document(value);
        Config c=new Config();c.enabled=true;c.rate=((Number)value.get("rate")).intValue();c.name=(String)value.get("name");c.serial=(String)value.get("serial");c.colors=Arrays.asList(255,1194684);
        c.world=new World();c.world.dimension="minecraft:overworld";c.world.height=256;c.world.scale=0.5;return c;
    }
    static Object plainPojo(Object value) {
        if(!(value instanceof Config))return value;Config c=(Config)value;Map<String,Object> m=object();
        m.put("enabled",c.enabled);m.put("rate",c.rate);m.put("name",c.name);m.put("serial",c.serial);m.put("colors",c.colors);
        Map<String,Object> w=object();if(c.world!=null){w.put("dimension",c.world.dimension);w.put("height",c.world.height);w.put("scale",c.world.scale);m.put("world",w);}else m.put("world",null);return m;
    }
    static void assertModel(String name,Object value) {
        if(!model(name).isInstance(value))throw new AssertionError("Wrong root Java class");
        if(value instanceof Config) {
            Config c=(Config)value;if(c.world==null)throw new AssertionError("Missing World");
            for(Object color:c.colors)if(!(color instanceof Integer))throw new AssertionError("List<Integer> element is "+color.getClass().getName());
        }else for(Object row:((CurrentBenchmark.Document)value).entries)if(!(row instanceof CurrentBenchmark.Row))throw new AssertionError("List<Row> element is "+row.getClass().getName());
    }
    static String source(String name,long id) {
        if(name.equals("repeated")||name.equals("distinct"))return LifecycleBenchmark.input(name,id);
        StringBuilder out=new StringBuilder();append(out,values(name,id));out.insert(1,"/* matrix */");return out.toString();
    }
    static void append(StringBuilder out,Object value) {
        if(value instanceof Map) {out.append('{');boolean first=true;for(Map.Entry<?,?> e:((Map<?,?>)value).entrySet()){if(!first)out.append(',');first=false;out.append(e.getKey()).append(':');append(out,e.getValue());}out.append('}');}
        else if(value instanceof List) {out.append('[');boolean first=true;for(Object x:(List<?>)value){if(!first)out.append(',');first=false;append(out,x);}out.append(']');}
        else if(value instanceof String) {
            out.append('"');String s=(String)value;for(int i=0;i<s.length();i++){char c=s.charAt(i);switch(c){case '"':out.append("\\\"");break;case '\\':out.append("\\\\");break;case '\n':out.append("\\n");break;case '\r':out.append("\\r");break;case '\t':out.append("\\t");break;default:if(c<32){String hex=Integer.toHexString(c);out.append("\\u");for(int j=hex.length();j<4;j++)out.append('0');out.append(hex);}else out.append(c);}}out.append('"');
        }else out.append(value);
    }
}
