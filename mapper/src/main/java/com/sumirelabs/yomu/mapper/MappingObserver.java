package com.sumirelabs.yomu.mapper;

import java.util.Map;

/** Optional per-operation observations; ordinary sessions keep this null. */
interface MappingObserver {
    Object onRead(Object source,Object value,boolean codec);
    Object codecInput(Object source);
    void onWrite(Object value,Object wire,String path,boolean codec);
    void onReference(Map<String,Object> reference,int id);
}
