package com.sumirelabs.yomu.mapper;

import java.lang.reflect.*;
import java.util.*;

import static com.sumirelabs.yomu.mapper.MappingTypes.*;
import static com.sumirelabs.yomu.mapper.MappingValues.*;

/** Explicit polymorphic type selection and generic compatibility checks. */
final class RegisteredTypeMapping {
    private RegisteredTypeMapping() { }

    static RegisteredTypes.Entry registeredType(Json5Mapper mapper,Object id,Type requested,Map<TypeVariable<?>,Type> variables,String path) {
        if(!(id instanceof String))throw error(path,"Expected registered type ID string");
        RegisteredTypes.Entry entry=mapper.registeredTypes.byId.get(id);
        if(entry==null)throw error(path,"Unknown registered type ID: "+id);
        checkRegisteredType(entry,requested,variables,path);
        return entry;
    }
    static void checkRegisteredType(RegisteredTypes.Entry entry,Type requested,Map<TypeVariable<?>,Type> variables,String path) {
        checkRegisteredType(entry.type,entry.raw,requested,variables,path);
    }
    static void checkRegisteredType(Type concrete,Class<?> actualClass,Type requested,Map<TypeVariable<?>,Type> variables,String path) {
        requested=substitute(requested,variables,path);
        Class<?> base=raw(requested,variables,path);
        if(!boxed(base).isAssignableFrom(actualClass))throw error(path,"Registered type is incompatible with target "+requested);
        if(requested instanceof GenericArrayType) {
            checkRegisteredType(actualClass.getComponentType(),actualClass.getComponentType(),((GenericArrayType)requested).getGenericComponentType(),variables,path);
        }
        if(requested instanceof ParameterizedType) {
            Map<TypeVariable<?>,Type> actual=bindings(concrete,Collections.<TypeVariable<?>,Type>emptyMap(),path);
            TypeVariable<?>[] parameters=base.getTypeParameters();
            Type[] arguments=((ParameterizedType)requested).getActualTypeArguments();
            for(int i=0;i<parameters.length;i++) {
                Type found=actual.get(parameters[i]);
                if(found==null || !TypeKeys.of(substitute(found,actual,path)).equals(TypeKeys.of(arguments[i])))
                    throw error(path,"Registered type has incompatible generic arguments for "+requested);
            }
        }
    }
    static RegisteredTypes.Entry outputType(Json5Mapper mapper,Object value,Type declared,Map<TypeVariable<?>,Type> variables,String path) {
        if(value==null || mapper.registeredTypes.byId.isEmpty())return null;
        declared=writeType(declared,variables,value.getClass(),path);
        Class<?> base=raw(declared,variables,path);
        RegisteredTypes.Entry entry=mapper.registeredTypes.byClass.get(value.getClass());
        if(entry!=null)checkRegisteredType(entry,declared,variables,path);
        else if(base!=Object.class && base!=value.getClass()) {
            // Unregistered values can still use the existing explicit base-type codec contract.
            if(mapper.readCodec(declared,base,variables,path)!=null)return null;
            for(RegisteredTypes.Entry known:mapper.registeredTypes.byId.values())if(base.isAssignableFrom(known.raw))
                throw error(path,"Unregistered runtime subtype: "+value.getClass().getName());
        }
        return entry;
    }

}
