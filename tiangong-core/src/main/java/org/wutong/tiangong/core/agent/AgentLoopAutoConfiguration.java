package org.wutong.tiangong.core.agent;

import org.wutong.tiangong.annotations.annotation.framework.AutoConfiguration;
import org.wutong.tiangong.annotations.annotation.framework.enable.EnableAgentLoop;
import org.wutong.tiangong.annotations.scanner.EnableMetadata;
import org.wutong.tiangong.common.agent.context.AgentContext;
import org.wutong.tiangong.core.agent.runtime.AgentRuntime;
import org.wutong.tiangong.core.container.context.framework.FrameworkConfiguration;
import org.wutong.tiangong.core.container.context.framework.FrameworkConfigurator;

/**
 * Agent Loop 自动配置：用户标注 @EnableAgentLoop 后，
 * 创建 AgentRuntime 并注册，框架在容器初始化完成后自动启动。
 */
@AutoConfiguration
public class AgentLoopAutoConfiguration implements FrameworkConfiguration {

    @Override
    public void configure(FrameworkConfigurator configurator) {
        EnableMetadata metadata = findEnableAgentLoop(configurator);
        if (metadata == null || !metadata.enabled()) {
            return;
        }
        Class<? extends AgentContext> contextType = resolveContextType(metadata);
        if (contextType == null) {
            throw new IllegalStateException(
                    "@EnableAgentLoop 未指定 contextClass");
        }
        AgentRuntime runtime = new AgentRuntime(
                configurator.agentLoopExecutor().registry(),
                configurator.agentLoopExecutor(),
                configurator.container(),
                contextType
        );
        configurator.registerAgentRuntime(runtime);
    }

    /**
     * 从扫描元数据中查找 @EnableAgentLoop
     */
    private static EnableMetadata findEnableAgentLoop(FrameworkConfigurator configurator) {
        return configurator.scanner().getEnableMetadata().stream()
                .filter(meta -> meta.enableAnnotation() == EnableAgentLoop.class)
                .findFirst()
                .orElse(null);
    }

    /**
     * 从用户类上的 @EnableAgentLoop 注解读取 contextClass。
     * 直接标注 → 取注解的 contextClass；组合标注 → 无 contextClass（需用户显式标注）。
     */
    @SuppressWarnings("unchecked")
    private static Class<? extends AgentContext> resolveContextType(EnableMetadata metadata) {
        EnableAgentLoop annotation = metadata.userClass().getAnnotation(EnableAgentLoop.class);
        if (annotation != null) {
            return annotation.contextClass();
        }
        // 组合标注（如 @TianGongApplicationStarter 组合 @EnableAgentLoop）
        // → 从元注解链读取（但 contextClass 无法在组合处指定，必须直接标注）
        for (java.lang.annotation.Annotation ann : metadata.userClass().getAnnotations()) {
            EnableAgentLoop meta = ann.annotationType().getAnnotation(EnableAgentLoop.class);
            if (meta != null) {
                throw new IllegalStateException(
                        "@EnableAgentLoop 需直接标注并指定 contextClass，" +
                                "不支持通过组合注解继承（组合处无法设置 contextClass）。" +
                                "请在主启动类上直接标注: @EnableAgentLoop(contextClass = XxxContext.class)");
            }
        }
        return null;
    }
}
