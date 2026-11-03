package com.sumirelabs.yomu.mapper;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;

/** Modern interface-field access; kept separate so legacy reflection does not link invokeExact. */
final class FieldSetters {
    private FieldSetters() { }

    static Object create(Field field) throws IllegalAccessException {
        return MethodHandles.explicitCastArguments(MethodHandles.lookup().unreflectSetter(field),
                MethodType.methodType(void.class,Object.class,Object.class));
    }

    static void set(Object setter,Object target,Object value,String path) {
        try {((MethodHandle)setter).invokeExact(target,value);}
        catch(RuntimeException | Error e){throw e;}
        catch(Throwable e){throw new MappingException(path,"Cannot set property",e);}
    }
}
