package com.taixu.tiangong.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.taixu.tiangong.annotations.annotation.framework.Component;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.AgentNode;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeNext;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.entrance.ApiEntranceNode;
import com.taixu.tiangong.common.exception.SystemRuntimeException;
import com.taixu.tiangong.core.config.ConfigLoader;
import com.taixu.tiangong.core.container.context.ContextHolder;
import com.taixu.tiangong.core.container.context.framework.ApplicationFrameworkContext;
import com.taixu.tiangong.core.container.context.framework.BootPolicy;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BootPolicy（宿主启动策略）测试：API 禁用模式的启动期校验与退化开关。<br>
 * <br>
 * 覆盖三态：报错（默认）/ 允许退化（开关关闭）/ 单机默认值不变。
 */
class BootPolicyTest {

    @AfterEach
    void cleanup() throws Exception {
        // 清掉测试写入的配置覆盖，避免 tiangong.entrance.api.error.enable 泄漏到其它测试
        ConfigLoader.loadFrom(Map.of());
        // 半自举失败的上下文不留给后续测试（正常情况下各测试自己 create 时会重设）
        ContextHolder.setApplication(null);
    }

    @Test
    void standaloneDefaultsUnchanged() {
        BootPolicy policy = BootPolicy.standalone();

        assertEquals(BootPolicy.ApiMode.ALLOW, policy.api(), "单机默认允许 API");
        assertEquals(BootPolicy.RuntimeStart.BLOCKING, policy.runtime(), "单机默认阻塞跑 AgentRuntime");
        assertTrue(policy.jvmShutdownHooks(), "单机默认注册 JVM 关机钩子");
    }

    @Test
    void forbidModeRejectsEnableApiAtStartup() {
        SystemRuntimeException ex = assertThrows(SystemRuntimeException.class, () ->
                        ApplicationFrameworkContext.create(
                                s -> s.acceptPackages("com.example.api"),   // ExternalApiApp 标了 @EnableApi
                                c -> {
                                },
                                new BootPolicy(BootPolicy.ApiMode.FORBID,
                                        BootPolicy.RuntimeStart.NONE, false)),
                "API 禁用模式下标注 @EnableApi 必须启动期报错");

        assertTrue(ex.getMessage().contains("@EnableApi"), ex.getMessage());
    }

    @Test
    void forbidModeRejectsApiEntranceNodesByDefault() {
        SystemRuntimeException ex = assertThrows(SystemRuntimeException.class, () ->
                        ApplicationFrameworkContext.create(
                                s -> {
                                },
                                c -> {
                                },
                                new BootPolicy(BootPolicy.ApiMode.FORBID,
                                        BootPolicy.RuntimeStart.NONE, false)),
                "API 禁用模式下含 @ApiEntranceNode 且未关闭报错开关，必须启动期报错"
                        + "（默认放行会让「我配的是 HTTP 入口」的误解静默存活到运行期）");

        assertTrue(ex.getMessage().contains("API 入口节点"), ex.getMessage());
        assertTrue(ex.getMessage().contains("tiangong.entrance.api.error.enable"), ex.getMessage());
    }

    @Test
    void errorFlagFalseAllowsDegradation() throws Exception {
        ConfigLoader.loadFrom(Map.of("tiangong", Map.of("entrance",
                Map.of("api", Map.of("error", Map.of("enable", false))))));

        ApplicationFrameworkContext ctx = ApplicationFrameworkContext.create(
                s -> {
                },
                c -> {
                },
                new BootPolicy(BootPolicy.ApiMode.FORBID, BootPolicy.RuntimeStart.NONE, false));

        assertNotNull(ctx);
        assertTrue(ctx.getAgentLoopExecutor().isApiForbidden(),
                "开关关闭时执行器应进入退化模式（@ApiEntranceNode 按服务入口语义处理）");
    }

    /**
     * 默认扫描下的 API 入口夹具：让"启动期校验"有可发现的节点。<br>
     * 标 {@code @Component} 才会被扫描为 Bean 并注册节点（否则校验根本看不到它）。
     */
    @Component
    public static class ApiModeWorkflow {

        @AgentNode(value = "api-mode-start", type = NodeType.START)
        @NodeNext("api-mode-entrance")
        public TestContext start(TestContext ctx) {
            return ctx;
        }

        @ApiEntranceNode(value = "api-mode-entrance", route = "/api-mode")
        public void entrance(TestContext ctx) {
        }
    }
}
