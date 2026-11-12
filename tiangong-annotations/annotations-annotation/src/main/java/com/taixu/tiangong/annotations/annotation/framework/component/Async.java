package com.taixu.tiangong.annotations.annotation.framework.component;

import com.taixu.tiangong.annotations.annotation.framework.meta.Framework;

import java.lang.annotation.*;

/**
 * 方法异步执行注解。<br>
 * 标记在框架组件类的方法上，调用时自动提交到指定 TaskExecutor 执行。
 * 返回值语义：
 * <ul>
 *   <li>void → 异步执行（fire-and-forget）</li>
 *   <li>Future → 返回 Future，调用方等待/捕获异常</li>
 *   <li>其他返回类型 → 警告并同步执行</li>
 * </ul>
 */
@Framework
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Async {

    /**
     * TaskExecutor 注册名，空 = 默认（虚拟线程池）
     */
    String value() default "";
}
