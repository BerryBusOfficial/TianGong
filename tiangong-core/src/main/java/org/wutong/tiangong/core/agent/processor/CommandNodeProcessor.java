package org.wutong.tiangong.core.agent.processor;

import lombok.extern.slf4j.Slf4j;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import org.wutong.tiangong.common.agent.context.AgentContext;
import org.wutong.tiangong.common.exception.SystemRuntimeException;
import org.wutong.tiangong.common.sandbox.CommandLine;
import org.wutong.tiangong.common.sandbox.Sandbox;
import org.wutong.tiangong.core.agent.AgentNodeRegistry;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;

/**
 * 命令节点处理器：处理 {@link NodeType#COMMAND}。<br>
 * 返回类型：void 或 AgentContext。<br>
 * next 约束：与 COMMON 一致，仅允许 0 或 1 个。<br>
 * <br>
 * <h2>额外校验：必须声明 {@link CommandLine} 参数</h2>
 * COMMON 节点的方法可以零参，但 COMMAND 节点<b>不行</b> —— 没有命令就没有命令节点。
 * 这条在<b>注册阶段</b>而非执行阶段报错，避免工作流跑起来才发现签名不对。
 *
 * <h2>为什么单独一个 Processor，不并入 {@link CommonNodeProcessor}</h2>
 * 返回类型与 next 约束跟 COMMON 完全一样，本可直接加进 {@code support()}。
 * 但 COMMAND 需要<b>额外的签名校验</b>，且将来还可能增加专属校验
 * （例如禁止 COMMAND 作为 END、要求必须标注 {@code @Sandbox} 等）。
 * 单独一个 Processor 让这些约束有地方落，不必在 {@code CommonNodeProcessor}
 * 里塞 {@code if (type == COMMAND)} 分支。
 */
@Slf4j
public class CommandNodeProcessor extends AbstractNodeProcessor {

    public CommandNodeProcessor(AgentNodeRegistry registry) {
        super(registry);
    }

    @Override
    public boolean support(NodeType type) {
        return type == NodeType.COMMAND;
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
     * 覆写以追加命令签名校验。<br>
     * <br>
     * 先跑基类的返回类型校验，再校验命令参数 —— 保证错误顺序稳定
     * （返回类型错优先于参数错，用户看到的是最根本的那个问题）。
     */
    @Override
    protected void validateReturnType(Method method) {
        super.validateReturnType(method);
        validateCommandSignature(method);
    }

    /**
     * 校验 COMMAND 节点方法签名。<br>
     * <br>
     * 合法参数（顺序任意，个数 ≥ 1）：
     * <ul>
     *     <li>{@link CommandLine} —— <b>必须有且仅有一个</b></li>
     *     <li>{@code AgentContext} 或其子类 —— 可选，注入工作流上下文</li>
     *     <li>{@link Sandbox} —— 可选，注入 {@code @Sandbox} 指定的实例</li>
     * </ul>
     *
     * @throws SystemRuntimeException 缺少 CommandLine 参数、CommandLine 重复、
     *                                或存在不被识别的参数类型
     */
    private void validateCommandSignature(Method method) {
        Parameter[] parameters = method.getParameters();

        if (parameters.length == 0) {
            throw new SystemRuntimeException(
                    "COMMAND 节点 [%s] 必须声明至少一个 CommandLine 参数".formatted(methodName(method)));
        }

        int commandCount = 0;
        for (Parameter param : parameters) {
            Class<?> type = param.getType();

            if (CommandLine.class.isAssignableFrom(type)) {
                commandCount++;
                continue;
            }
            if (type == Sandbox.class || Sandbox.class.isAssignableFrom(type)) {
                continue;
            }
            if (AgentContext.class.isAssignableFrom(type)) {
                continue;
            }
            throw new SystemRuntimeException(
                    "COMMAND 节点 [%s] 的参数 [%s %s] 类型不受支持；"
                            .formatted(methodName(method), type.getSimpleName(), param.getName())
                            + "仅支持 CommandLine / AgentContext / Sandbox");
        }

        if (commandCount == 0) {
            throw new SystemRuntimeException(
                    "COMMAND 节点 [%s] 缺少 CommandLine 参数；没有命令的命令节点毫无意义"
                            .formatted(methodName(method)));
        }
        if (commandCount > 1) {
            throw new SystemRuntimeException(
                    "COMMAND 节点 [%s] 声明了 %d 个 CommandLine 参数，只允许 1 个"
                            .formatted(methodName(method), commandCount));
        }

        log.debug("COMMAND 节点 [{}] 签名校验通过", methodName(method));
    }

    private String methodName(Method method) {
        return method.getDeclaringClass().getSimpleName() + "." + method.getName();
    }
}
