package com.taixu.tiangong.core.llm.routing;

import com.taixu.tiangong.common.agent.call.AgentCallContext;

/**
 * 模型路由契约：按任务名选候选链。<br>
 * 路由是实现方的职责（配置表/灰度/成本策略），执行器只负责「按链降级、空回答重试、
 * 记录 token/成本」——两者拆开，换路由策略不影响执行语义。
 */
@FunctionalInterface
public interface ModelRouter {

    ModelRoute route(String task, AgentCallContext context);
}
