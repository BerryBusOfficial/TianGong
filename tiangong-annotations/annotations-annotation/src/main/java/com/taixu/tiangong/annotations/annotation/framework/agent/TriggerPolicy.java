package com.taixu.tiangong.annotations.annotation.framework.agent;

import com.taixu.tiangong.annotations.annotation.framework.agent.interfaces.StartCondition;
import com.taixu.tiangong.annotations.annotation.framework.agent.interfaces.TriggerPolicyType;
import com.taixu.tiangong.annotations.annotation.framework.agent.interfaces.TriggerStrategy;
import com.taixu.tiangong.annotations.annotation.framework.meta.Framework;

import java.lang.annotation.*;

/**
 * 标注在 {@link StartCondition} 实现类上，指定同 START 节点多次触发时的处理策略。<br>
 * <br>
 * 未标注时默认 {@link TriggerPolicyType#REJECT}（拒绝新触发，最安全）。
 */
@Framework
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface TriggerPolicy {

    /** 触发策略，默认 REJECT */
    TriggerPolicyType value() default TriggerPolicyType.REJECT;

    /**
     * 当 {@code value = CUSTOM} 时，指定自定义策略实现类。<br>
     * 该类须实现 {@link TriggerStrategy} 并可被框架实例化（无参构造或注册为 Bean）。
     */
    Class<? extends TriggerStrategy> strategy() default TriggerStrategy.class;
}
