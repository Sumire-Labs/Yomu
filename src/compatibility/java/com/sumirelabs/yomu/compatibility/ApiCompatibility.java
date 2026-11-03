package com.sumirelabs.yomu.compatibility;

import java.io.File;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import java.util.jar.*;

/** Checks that every previously exported class/member remains available, using isolated loaders. */
public final class ApiCompatibility {
    private static boolean exported(int modifiers) { return Modifier.isPublic(modifiers)||Modifier.isProtected(modifiers); }
    private static String type(Class<?> type) { return type.getName(); }
    private static Set<String> api(Class<?> cls) {
        Set<String> result=new TreeSet<String>();
        result.add("class "+Modifier.toString(cls.getModifiers())+" "+cls.getName()+" extends "+(cls.getSuperclass()==null?"":type(cls.getSuperclass())));
        for(Class<?> iface:cls.getInterfaces())result.add("interface "+type(iface));
        for(Constructor<?> member:cls.getDeclaredConstructors())if(exported(member.getModifiers()))result.add(member.toGenericString());
        for(Method member:cls.getDeclaredMethods())if(exported(member.getModifiers())&&!member.isSynthetic())result.add(member.toGenericString());
        for(Field member:cls.getDeclaredFields())if(exported(member.getModifiers())&&!member.isSynthetic())result.add(member.toGenericString());
        return result;
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=4)throw new IllegalArgumentException("before-core before-mapper after-core after-mapper");
        int classes=0,members=0;
        try(URLClassLoader before=new URLClassLoader(new URL[]{new File(args[0]).toURI().toURL(),new File(args[1]).toURI().toURL()},null);
            URLClassLoader after=new URLClassLoader(new URL[]{new File(args[2]).toURI().toURL(),new File(args[3]).toURI().toURL()},null)) {
            for(int jarIndex=0;jarIndex<2;jarIndex++)try(JarFile jar=new JarFile(args[jarIndex])) {
                Enumeration<JarEntry> entries=jar.entries();
                while(entries.hasMoreElements()) {
                    String name=entries.nextElement().getName();if(!name.endsWith(".class"))continue;
                    name=name.substring(0,name.length()-6).replace('/','.');
                    Class<?> oldClass=Class.forName(name,false,before);if(!exported(oldClass.getModifiers()))continue;
                    Set<String> expected=api(oldClass),actual=api(Class.forName(name,false,after));
                    Set<String> missing=new TreeSet<String>(expected);missing.removeAll(actual);
                    if(!missing.isEmpty())throw new AssertionError("API removed or changed: "+missing);
                    classes++;members+=expected.size();
                }
            }
        }
        System.out.println("Preserved "+classes+" exported classes and "+members+" signatures");
    }
}
