package org.wutong.tiangong.annotations.annotation.framework.field;

import org.wutong.tiangong.annotations.annotation.framework.meta.Framework;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
@Framework
public @interface StorageRecords {
}