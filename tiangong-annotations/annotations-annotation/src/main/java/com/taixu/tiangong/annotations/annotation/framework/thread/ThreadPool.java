package com.taixu.tiangong.annotations.annotation.framework.thread;

import com.taixu.tiangong.annotations.annotation.framework.meta.Framework;

import java.lang.annotation.*;

@Framework
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ThreadPool {

    String value();  // 池名，@Async("xxx") 引用

}
