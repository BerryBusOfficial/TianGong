package org.wutong.tiangong.spring.test;

import org.springframework.stereotype.Component;
import org.wutong.tiangong.spring.annotation.TianGongWrite;

/**
 * Spring Bean 经 {@code @TianGongWrite} 持有天工侧实例：<br>
 * <ul>
 *     <li>{@code tianGongBean}：具体类 → 启动期占位（ByteBuddy）+ 就绪后回填真实例</li>
 *     <li>{@code echoAgent}：{@code @ServiceAgent} 契约接口 → 即时写服务代理</li>
 * </ul>
 */
@Component
public class BridgeProbe {

    @TianGongWrite
    private YunXiWorkflowBean tianGongBean;

    @TianGongWrite
    private EchoAgent echoAgent;

    public YunXiWorkflowBean tianGongBean() {
        return tianGongBean;
    }

    public EchoAgent echoAgent() {
        return echoAgent;
    }
}
