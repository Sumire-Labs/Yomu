package com.sumirelabs.yomu.mapper;

import java.lang.annotation.*;

/** Selects a constructor or static factory. Parameters use Json5Field names or -parameters metadata. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.CONSTRUCTOR, ElementType.METHOD})
public @interface Json5Creator { }
