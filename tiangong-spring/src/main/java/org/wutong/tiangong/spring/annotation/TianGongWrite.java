package org.wutong.tiangong.spring.annotation;

import java.lang.annotation.*;

/**
 * 把天工侧的实例写入 Spring Bean（字段 / setter）。<br>
 * <br>
 * <b>注入目标只允许是 Spring 管理的 Bean</b>（该注入点只在 Spring 容器下处理，
 * 天工容器不认）。解析规则对齐 Spring 语义：
 * <ul>
 *     <li>给了名字（{@code @TianGongWrite("beanName")}，或字段上的天工 {@code @Qualifier} 优先）
 *         → 按名解析</li>
 *     <li>没给名字 → 按类型；多候选且无 {@code @Primary} 时<b>报错</b>，不静默选第一个</li>
 * </ul>
 * <br>
 * <b>时序</b>：Spring Bean 创建早于天工自举，所以具体类先写
 * {@code DeferredInstancePlaceholder}（ByteBuddy 占位：可安全持有、调用明确报错），
 * 天工容器就绪后回填真实实例；{@code @ServiceAgent} 接口直接给服务代理（调用时才寻址）。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface TianGongWrite {

    /** 天工 Bean 名（可选）；为空则按类型解析。 */
    String value() default "";
}
