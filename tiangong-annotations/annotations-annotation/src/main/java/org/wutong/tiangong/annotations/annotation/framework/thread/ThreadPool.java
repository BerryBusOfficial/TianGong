package org.wutong.tiangong.annotations.annotation.framework.thread;

import org.wutong.tiangong.annotations.annotation.framework.meta.Framework;

import java.lang.annotation.*;

@Framework
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ThreadPool {

    String value();  // 池名，@Async("xxx") 引用

}
