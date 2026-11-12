package org.wutong.tiangong.spring.test;

import org.wutong.tiangong.spring.annotation.EntranceCall;
import org.wutong.tiangong.spring.annotation.ServiceAgent;

/**
 * Spring 侧的声明式契约：由桥生成代理并注册为 Bean（可直接 {@code @Autowired}）。
 */
@ServiceAgent
public interface EchoAgent {

    @EntranceCall(url = "agent://test/echo")
    EchoReply echo(EchoCmd cmd);

    @EntranceCall(url = "agent://test/boom")
    EchoReply boom(EchoCmd cmd);

    /** void：受理即返回（fire-and-forget） */
    @EntranceCall(url = "agent://test/ping")
    void ping(EchoCmd cmd);

    /** 实例桥验证：对侧工作流用 @SpringWrite 持有 Spring 服务 */
    @EntranceCall(url = "agent://test/spring-echo")
    EchoReply springEcho(EchoCmd cmd);
}
