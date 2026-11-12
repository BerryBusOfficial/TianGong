package com.taixu.tiangong.core.agent;

import lombok.extern.slf4j.Slf4j;
import com.taixu.tiangong.annotations.annotation.framework.Component;
import com.taixu.tiangong.common.exception.ToolInvokeException;
import com.taixu.tiangong.core.container.context.ContextHolder;
import com.taixu.tiangong.core.function.FunctionRegistry;
import com.taixu.tiangong.core.function.ToolGovernance;

import java.util.Map;

@Slf4j
@Component
public class AgentToolInvoker {

    /**
     * 懒取而非字段初始化：原先在字段初始化时就读 {@code ContextHolder.getApplication()}，
     * 一旦本 Bean 的创建时机早于 {@code ContextHolder.setApplication} 就会 NPE。
     * 现在正确顺序（setApplication 早于 container.refresh）恰好能跑，但那是巧合不是契约。
     */
    private FunctionRegistry registry() {
        return ContextHolder.getApplication().getFunctionRegistry();
    }

    /**
     *
     * @param toolName 工具名，当工具名未注册或不存在时抛出异常
     * @param arguments 调用工具
     * @throws ToolInvokeException 工具调用失败
     * @return 工具调用结果
     */
    public Object invoke(String toolName, Map<String, Object> arguments) {
        var tool = registry().get(toolName);
        if (tool.isPresent()) {
            return ToolGovernance.invoke(tool.get(), arguments, toolName);
        }
        throw new ToolInvokeException(toolName);
    }

}
