package com.taixu.tiangong.core.function;

import lombok.Getter;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 工具函数注册项：元数据 + 统一调用执行器。<br>
 * 本地函数（@AgentFunction）由 {@link MethodInvoker} 包装；外部工具（MCP）由自定义 ToolInvoker 包装。
 */
@Getter
public class AgentFunctionDefinition {

    private final String name;            // 函数短名（注解 name || 方法名 / MCP tool 名）
    private final String beanName;        // 所属 Bean / MCP server 名
    private final String description;
    private final Object target;          // 本地函数所属 Bean 实例（MCP 为 null）
    private final Method method;          // 本地函数方法（MCP 为 null）
    private final List<ParamDefinition> params;
    private final ToolInvoker invoker;    // 统一调用执行器
    /** 调用该工具所需的 scope；空集合 = 无需额外权限（兼容既有工具） */
    private final Set<String> scopes;
    /** 是否必须审批；没有审批处理器时按拒绝处理 */
    private final boolean requiresApproval;
    /**
     * 原始 JSON Schema —— <b>只有外部工具（MCP）会带</b>，本地函数恒为 null。<br>
     * <br>
     * <h2>为什么不能只靠 {@link ParamDefinition}</h2>
     * {@code ParamDefinition} 是<b>扁平</b>的（name / type / description / required），
     * 为本地方法的反射调用而设计。MCP 的 schema 可能有嵌套对象、{@code oneOf} 分叉、
     * {@code enum} 可选值，硬压进扁平模型等于把这些全丢掉 —— 模型拿到的是个残缺工具，
     * 不知道该传什么，于是干脆不调、直接编答案（实测：桌宠编了 5 条 Firefly 流水）。<br>
     * 所以外部工具的完整 schema 原样保留在这里，下发时<b>优先</b>用它。
     */
    private final Map<String, Object> rawSchema;

    public AgentFunctionDefinition(String name, String beanName, String description,
                                   Object target, Method method, List<ParamDefinition> params) {
        this(name, beanName, description, target, method, params,
                target != null && method != null ? new MethodInvoker(target, method, params) : null);
    }

    public AgentFunctionDefinition(String name, String beanName, String description,
                                   Object target, Method method, List<ParamDefinition> params,
                                   ToolInvoker invoker) {
        this(name, beanName, description, target, method, params, null, invoker);
    }

    public AgentFunctionDefinition(String name, String beanName, String description,
                                   Object target, Method method, List<ParamDefinition> params,
                                   Set<String> scopes, boolean requiresApproval) {
        this(name, beanName, description, target, method, params, null,
                target != null && method != null ? new MethodInvoker(target, method, params) : null,
                scopes, requiresApproval);
    }

    public AgentFunctionDefinition(String name, String beanName, String description,
                                   Object target, Method method, List<ParamDefinition> params,
                                   Map<String, Object> rawSchema, ToolInvoker invoker) {
        this(name, beanName, description, target, method, params, rawSchema, invoker, Set.of(), false);
    }

    public AgentFunctionDefinition(String name, String beanName, String description,
                                   Object target, Method method, List<ParamDefinition> params,
                                   Map<String, Object> rawSchema, ToolInvoker invoker,
                                   Set<String> scopes, boolean requiresApproval) {
        this.name = name;
        this.beanName = beanName;
        this.description = description;
        this.target = target;
        this.method = method;
        this.params = params;
        this.rawSchema = rawSchema;
        this.invoker = invoker;
        this.scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        this.requiresApproval = requiresApproval;
    }

    /**
     * 全名 = BeanName.函数名
     */
    public String fullName() {
        return beanName + "." + name;
    }

    /**
     * 执行工具调用（必须配置了 invoker）
     */
    public Object invoke(Map<String, Object> arguments) {
        if (invoker == null) {
            throw new IllegalStateException("工具未配置执行器: " + fullName());
        }
        return invoker.invoke(arguments);
    }

    public record ParamDefinition(
            String name,
            String type,                 // JSON schema 类型
            String description,
            boolean required,
            String defaultValue          // required=false 时的默认值字符串（由 MethodInvoker 做类型转换）
    ) {
    }
}
