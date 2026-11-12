package com.taixu.tiangong.core.agent.processor;

import lombok.extern.slf4j.Slf4j;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import com.taixu.tiangong.common.agent.context.AgentContext;
import com.taixu.tiangong.common.exception.SystemRuntimeException;
import com.taixu.tiangong.core.agent.AgentNodeRegistry;
import com.taixu.tiangong.core.agent.AgentToolInvoker;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;

/**
 * 工具节点处理器：处理 {@link NodeType#TOOL}。<br>
 * 返回类型：void 或 AgentContext。<br>
 * next 约束：与 COMMON 一致，仅允许 0 或 1 个。<br>
 * <br>
 * <h2>额外校验：必须声明 {@link AgentToolInvoker} 参数</h2>
 * TOOL 节点的语义就是「这个节点要调工具」，没有调用器就没有工具节点。
 * 在<b>注册阶段</b>报错，而不是等到执行时拿到 null 调用器才炸。<br>
 * <br>
 * <h2>为什么单独一个 Processor</h2>
 * 与 {@link CommandNodeProcessor} 同理：返回类型与 next 约束跟 COMMON 一样，
 * 本可并入 {@code support()}，但 TOOL 有额外的签名校验，单独放才不必在
 * {@code CommonNodeProcessor} 里塞 {@code if (type == TOOL)} 分支。
 *
 * <h2>与其他参数的关系</h2>
 * {@code AgentToolInvoker} 的注入本身是<b>通用机制</b>（见
 * {@code AgentNodeDefinition#bindParameters}），任何节点类型声明该形参都能收到。
 * 本 Processor 只是把它变成 TOOL 节点的<b>强制要求</b>，
 * 顺带让工作流图上「哪个节点在调工具」一目了然。
 */
@Slf4j
public class ToolNodeProcessor extends AbstractNodeProcessor {

    public ToolNodeProcessor(AgentNodeRegistry registry) {
        super(registry);
    }

    @Override
    public boolean support(NodeType type) {
        return type == NodeType.TOOL;
    }

    @Override
    public List<Class<?>> supportReturnType() {
        return List.of(void.class, AgentContext.class);
    }

    @Override
    protected void validateNextCount(NodeType type, String[] next, Method method) {
        requireMaxOneNext(type, next, method);
    }

    /**
     * 先跑基类校验，再校验工具签名 —— 保证错误顺序稳定
     * （返回类型错优先于参数错）。
     */
    @Override
    protected void validateReturnType(Method method) {
        super.validateReturnType(method);
        validateToolSignature(method);
    }

    /**
     * 校验 TOOL 节点方法签名。<br>
     * <br>
     * 要求：
     * <ul>
     *     <li>{@link AgentToolInvoker} —— <b>必须有且仅有一个</b></li>
     *     <li>{@code AgentContext} 或其子类 —— 可选，最多一个</li>
     *     <li>其他类型不在此校验 —— 它们走通用容器注入，取不到即为 null，
     *         由用户在使用处自行处理</li>
     * </ul>
     *
     * @throws SystemRuntimeException 缺少 AgentToolInvoker、重复声明，或多个上下文参数
     */
    private void validateToolSignature(Method method) {
        Parameter[] parameters = method.getParameters();

        int invokerCount = 0;
        int contextCount = 0;
        for (Parameter param : parameters) {
            Class<?> type = param.getType();
            if (AgentToolInvoker.class.isAssignableFrom(type)) {
                invokerCount++;
            } else if (AgentContext.class.isAssignableFrom(type)) {
                contextCount++;
            }
        }

        if (invokerCount == 0) {
            throw new SystemRuntimeException(
                    "TOOL 节点 [%s] 缺少 AgentToolInvoker 参数；"
                            .formatted(methodName(method))
                            + "请在方法签名中声明它（由框架注入，无需 @Inject）");
        }
        if (invokerCount > 1) {
            throw new SystemRuntimeException(
                    "TOOL 节点 [%s] 声明了 %d 个 AgentToolInvoker 参数，只允许 1 个"
                            .formatted(methodName(method), invokerCount));
        }
        if (contextCount > 1) {
            throw new SystemRuntimeException(
                    "TOOL 节点 [%s] 声明了 %d 个 AgentContext 参数，只允许 1 个"
                            .formatted(methodName(method), contextCount));
        }

        log.debug("TOOL 节点 [{}] 签名校验通过", methodName(method));
    }

    private String methodName(Method method) {
        return method.getDeclaringClass().getSimpleName() + "." + method.getName();
    }
}
