package com.taixu.tiangong.test;

import com.taixu.tiangong.annotations.annotation.framework.inject.Inject;
import com.taixu.tiangong.core.function.FunctionRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 验证 {@link AgentTest} 短别名能启动完整上下文并注入依赖。
 */
@AgentTest
class AgentTestSmokeTest {

    @Inject
    FunctionRegistry functionRegistry;

    @Test
    void agentTestStartsContextAndInjects() {
        assertNotNull(functionRegistry, "FunctionRegistry 应被注入");
    }
}
