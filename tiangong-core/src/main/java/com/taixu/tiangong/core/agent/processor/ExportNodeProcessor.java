package com.taixu.tiangong.core.agent.processor;

import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import com.taixu.tiangong.common.exception.SystemRuntimeException;
import com.taixu.tiangong.core.agent.AgentNodeRegistry;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 出口节点处理器：只处理 {@link NodeType#EXPORT}。<br>
 * <br>
 *
 * <h2>为什么 EXPORT 要从 {@link CommonNodeProcessor} 里拆出来</h2>
 * {@code @ApiExportNode} 的契约是「<b>方法返回值序列化为 JSON 作为 HTTP 响应体</b>」，
 * 而 {@code CommonNodeProcessor} 把返回类型限制在 {@code void / AgentContext} ——
 * 两者直接冲突，结果是<b>文档承诺的能力实际上一个都写不出来</b>：
 * 只要出口方法返回一个响应对象，容器在 refresh 阶段就会因返回类型不符而拒绝建 Bean，
 * 整个应用起不来（且不报在节点定义处，报在 Bean 创建处，很难定位）。<br>
 * 拆出来之后，EXPORT 的返回类型由应用自己定，与它的职责一致。<br>
 * <br>
 *
 * <h2>允许的返回类型</h2>
 * <ul>
 *     <li>{@code void}：回 204（无正文）</li>
 *     <li>{@code String}：原文回传（框架按 JSON 的 Content-Type 送出）</li>
 *     <li>其它引用类型：序列化为 JSON 回传</li>
 *     <li><b>基本类型（int / boolean …）不允许</b>：它们无法表达「无值」，
 *         序列化出来也不是合法 JSON 文档，宁可在启动期报错也不要在运行期回一个畸形响应</li>
 * </ul>
 */
public class ExportNodeProcessor extends AbstractNodeProcessor {

    public ExportNodeProcessor(AgentNodeRegistry registry) {
        super(registry);
    }

    @Override
    public boolean support(NodeType type) {
        return type == NodeType.EXPORT;
    }

    /** 任意引用类型：真正的判定在 {@link #validateReturnType} */
    @Override
    public List<Class<?>> supportReturnType() {
        return List.of(Object.class);
    }

    @Override
    protected void validateReturnType(Method method) {
        Class<?> returnType = method.getReturnType();
        if (returnType == void.class || returnType == Void.class) {
            return;
        }
        if (returnType.isPrimitive()) {
            throw new SystemRuntimeException(
                    "节点方法 [" + method.getDeclaringClass().getSimpleName() + "." + method.getName()
                            + "] 是 EXPORT 节点，返回值会作为 HTTP 响应体，不支持基本类型 ["
                            + returnType.getSimpleName() + "]；"
                            + "请改为包装类型或自定义响应对象");
        }
        super.validateReturnType(method);
    }

    /** 与 {@link CommonNodeProcessor} 一致：出口之后最多再走一个节点 */
    @Override
    protected void validateNextCount(NodeType type, String[] next, Method method) {
        requireMaxOneNext(type, next, method);
    }
}
