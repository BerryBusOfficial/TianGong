package org.wutong.tiangong.core.function;

import lombok.extern.slf4j.Slf4j;
import org.wutong.tiangong.annotations.processor.registry.Registry;
import org.wutong.tiangong.common.exception.SystemRuntimeException;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 函数注册表：BeanName.方法名 → 可调用定义
 */
@Slf4j
public class FunctionRegistry implements Registry<AgentFunctionDefinition> {

    /**
     * 注册进容器时的 Bean 名。<br>
     * 需要在<b>运行期</b>按名字查工具的组件（如技能的工具映射）才能注入它 ——
     * 否则拿不到注册表，只能到处传参。
     */
    public static final String BEAN_NAME = "functionRegistry";

    private final Map<String, AgentFunctionDefinition> functions = new ConcurrentHashMap<>();  // fullName → def
    private final Map<String, String> shortIndex = new ConcurrentHashMap<>();                  // 短名 → fullName

    @Override
    public void register(AgentFunctionDefinition element) {
        String fullName = element.fullName();
        if (functions.putIfAbsent(fullName, element) != null) {
            throw new SystemRuntimeException("重复注册 Agent 函数: " + fullName, new IllegalArgumentException());
        }
        // 短名索引：冲突保留首个 + warn（全名仍精确可用）
        String previous = shortIndex.putIfAbsent(element.getName(), fullName);
        if (previous != null) {    // ← 冲突判断就是这里
            log.warn("短名冲突: [{}] 已注册给 [{}]，本次 [{}] 短名访问保留首个（可用全名精确访问）",
                    element.getName(), previous, fullName);
        }
    }


    @Override
    public void unregister(String name) {
        functions.remove(name);
    }

    @Override
    public Optional<AgentFunctionDefinition> get(String name) {
        if (name.contains(".")) {                          // 带前缀 → 全名精确查
            return Optional.ofNullable(functions.get(name));
        }
        String fullName = shortIndex.get(name);            // 无前缀 → 短名索引查
        return Optional.ofNullable(fullName == null ? null : functions.get(fullName));
    }

    @Override
    public Collection<AgentFunctionDefinition> values() {
        return functions.values();
    }

    @Override
    public boolean contains(String name) {
        return functions.containsKey(name);
    }

    @Override
    public int size() {
        return functions.size();
    }

    @Override
    public Iterator<AgentFunctionDefinition> iterator() {
        return functions.values().iterator();
    }
}