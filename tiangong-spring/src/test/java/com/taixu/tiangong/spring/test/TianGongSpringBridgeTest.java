package com.taixu.tiangong.spring.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import com.taixu.tiangong.core.api.entrance.ServiceEntranceException;
import com.taixu.tiangong.core.config.ConfigHolder;
import com.taixu.tiangong.spring.TianGongRuntime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 服务桥端到端测试：Spring Boot 宿主 + 天工自举（同进程、双容器）。<br>
 * <br>
 * 验证：{@code @ServiceAgent} 代理注册与调用、异常类型保真、void 受理即返回、
 * 配置覆盖（application.yml 优先 / agent.yaml 基础层保留）。
 */
class TianGongSpringBridgeTest {

    static ConfigurableApplicationContext spring;
    static TianGongRuntime runtime;

    @BeforeAll
    static void boot() {
        spring = new SpringApplicationBuilder(TestApp.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "tiangong.config=classpath:bridge-agent.yaml",
                        "agent.name=from-spring",      // 覆盖文件层的 agent.name=from-file
                        "spring.main.banner-mode=off",
                        "logging.level.root=WARN",
                        "logging.level.org.wutong.tiangong=INFO")
                .run();
        runtime = spring.getBean(TianGongRuntime.class);
    }

    @AfterAll
    static void shutdown() {
        if (spring != null) {
            spring.close();
        }
    }

    // ==================== 服务调用 ====================

    @Test
    void serviceAgentIsRegisteredAndCallsWorkflow() throws Exception {
        awaitUrl("agent://test/echo");
        EchoAgent agent = spring.getBean(EchoAgent.class);

        EchoReply reply = agent.echo(new EchoCmd("hi"));

        assertEquals("pong:hi", reply.text(), "出口节点的返回值应反序列化为契约返回类型");
        assertTrue(EchoWorkflow.RECEIVED.contains("echo:hi"), "入口方法应收到反序列化后的参数");
    }

    @Test
    void businessExceptionKeepsOriginalType() throws Exception {
        awaitUrl("agent://test/boom");
        EchoAgent agent = spring.getBean(EchoAgent.class);

        BridgeBizException ex = assertThrows(BridgeBizException.class,
                () -> agent.boom(new EchoCmd("x")),
                "业务异常必须按原始类型 rethrow（不被包装）");
        assertEquals("boom!", ex.getMessage());
    }

    @Test
    void voidMethodReturnsAfterSubmission() throws Exception {
        awaitUrl("agent://test/ping");
        EchoAgent agent = spring.getBean(EchoAgent.class);

        agent.ping(new EchoCmd("ping-me"));      // 不等出口

        long deadline = System.currentTimeMillis() + 3000;
        while (!PingWorkflow.PINGED.contains("ping-me") && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(PingWorkflow.PINGED.contains("ping-me"), "void 调用应被受理并最终处理");
    }

    @Test
    void unknownUrlFailsLoudly() {
        ServiceEntranceException ex = assertThrows(ServiceEntranceException.class,
                () -> runtime.registry().call("agent://nope", null, null, 1));
        assertTrue(ex.getMessage().contains("未注册"), ex.getMessage());
    }

    // ==================== 实例桥 ====================

    @Test
    void springWriteInjectsSpringBeanIntoTiangongWorkflow() throws Exception {
        awaitUrl("agent://test/spring-echo");

        EchoReply reply = spring.getBean(EchoAgent.class).springEcho(new EchoCmd("hi"));

        assertEquals("spring:hi", reply.text(),
                "天工节点里的 @SpringWrite 字段应直接持有 Spring 服务实例");
    }

    @Test
    void tianGongWriteBackfillsRealInstanceForConcreteType() {
        BridgeProbe probe = spring.getBean(BridgeProbe.class);

        YunXiWorkflowBean expected = runtime.context().getContainer().getBean(YunXiWorkflowBean.class);
        assertNotNull(expected, "天工容器应有 YunXiWorkflowBean");
        assertSame(expected, probe.tianGongBean(),
                "具体类依赖在自举后应回填为真实例（而不是停留在 ByteBuddy 占位）");
    }

    @Test
    void tianGongWriteInjectsServiceAgentProxy() throws Exception {
        awaitUrl("agent://test/echo");
        BridgeProbe probe = spring.getBean(BridgeProbe.class);

        assertEquals("pong:probe", probe.echoAgent().echo(new EchoCmd("probe")).text(),
                "@TianGongWrite 的 @ServiceAgent 字段应是可调用的服务代理");
    }

    // ==================== 配置桥 ====================

    @Test
    void springEnvironmentOverlaysFileLayer() {
        assertEquals("from-spring", ConfigHolder.getValueOrDefault("agent.name", String.class, null),
                "application.yml（Spring Environment）应覆盖 agent.yaml 的同名键");
        assertEquals(7, ConfigHolder.getValueOrDefault("context.windows", Integer.class, 0),
                "agent.yaml 基础层中未被子层覆盖的键应保留");
    }

    // ==================== 辅助 ====================

    private static void awaitUrl(String url) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (runtime.registry().findByUrl(url).isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(runtime.registry().findByUrl(url).isEmpty(),
                "工作流启动后服务地址应注册: " + url);
    }
}
