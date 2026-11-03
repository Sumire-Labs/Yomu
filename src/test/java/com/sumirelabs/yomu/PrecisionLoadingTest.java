package com.sumirelabs.yomu;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import org.junit.Test;
import static org.junit.Assert.*;

public class PrecisionLoadingTest {
    private static final class TrackedLoader extends URLClassLoader {
        boolean precisionLoaded;
        TrackedLoader(URL url) {super(new URL[]{url},null);}
        protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
            if(name.equals("com.sumirelabs.yomu.ParsedNumbers"))precisionLoaded=true;
            return super.loadClass(name,resolve);
        }
    }
    @Test public void ordinaryNumbersDoNotLoadPrecisionSupport() throws Exception {
        try(TrackedLoader loader=new TrackedLoader(Yomu.class.getProtectionDomain().getCodeSource().getLocation())) {
            Method parse=loader.loadClass("com.sumirelabs.yomu.Yomu").getMethod("parse",String.class);
            parse.invoke(null,"{values:[1,-0,0xFF,1.25,1e5,NaN,Infinity,'text']}");
            assertFalse("Ordinary parsing loaded precision support",loader.precisionLoaded);
            assertEquals("9223372036854775808",parse.invoke(null,"9223372036854775808").toString());
            assertTrue("Large integer did not use precision support",loader.precisionLoaded);
        }
    }
}
