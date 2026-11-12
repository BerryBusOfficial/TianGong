package org.wutong.tiangong.spring.annotation;

import java.lang.annotation.*;

/**
 * 把 Spring 侧的实例写入天工 Bean（字段 / setter）。<br>
 * <br>
 * <b>注入目标只允许是天工管理的 Bean</b>（该注入点只在天工容器下处理）。
 * 解析规则对齐 Spring 语义：给了名字（或字段上的天工 {@code @Qualifier} 优先）按名；
 * 否则按类型，多候选且无 {@code @Primary} 时<b>报错</b>。<br>
 * <br>
 * <b>时序</b>：天工自举发生在 Spring 单例就绪之后，所以这里可以<b>直接写真实例</b>
 * （不需要占位/回填）；构造器注入不支持（跨容器构造时机无解，请用字段/setter）。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface SpringWrite {

    /** Spring Bean 名（可选）；为空则按类型解析。 */
    String value() default "";
}
