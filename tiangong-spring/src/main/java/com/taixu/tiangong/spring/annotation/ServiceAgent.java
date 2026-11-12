package com.taixu.tiangong.spring.annotation;

import java.lang.annotation.*;

/**
 * 声明式服务契约接口：接口方法经 {@link EntranceCall} 映射到天工服务入口，
 * 由桥生成 JDK 代理（类 Feign 的用法：可直接标在接口上，也可作为元注解组合使用）。<br>
 * <br>
 * 契约校验在<b>启动期</b>完成（不拖到调用期）：必须是接口；除 default/static 外的方法
 * 必须有 {@code @EntranceCall}；方法至多一个参数；url 当前仅支持 {@code agent://} 同进程寻址。<br>
 * <br>
 * 注册后即是一个 Spring Bean：{@code @Autowired} / {@code @TianGongWrite} 均可注入。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ServiceAgent {
}
