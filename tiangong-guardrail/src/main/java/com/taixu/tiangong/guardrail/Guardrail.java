package com.taixu.tiangong.guardrail;

/**
 * 护栏扩展点。实现应保持无状态/线程安全；有状态策略由上层按租户/运行作用域创建。
 */
public interface Guardrail {

    String name();

    GuardrailResult inspect(GuardrailContext context);
}
