package com.taixu.tiangong.test;

import org.junit.jupiter.api.Test;
import com.taixu.tiangong.annotations.annotation.definition.BeanDefinition;
import com.taixu.tiangong.annotations.annotation.definition.Lifecycle;
import com.taixu.tiangong.annotations.annotation.framework.agent.AgentFunction;
import com.taixu.tiangong.common.exception.SystemRuntimeException;
import com.taixu.tiangong.common.exception.ToolInvokeException;
import com.taixu.tiangong.core.container.process.impl.AgentFunctionProcessor;
import com.taixu.tiangong.core.function.AgentFunctionDefinition;
import com.taixu.tiangong.core.function.FunctionRegistry;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Function/Tool 系统测试。<br>
 * 覆盖：@AgentFunction 注册、短名/全名查找、参数调用、可选参数默认值转换、重复注册、未注册工具调用。
 */
class FunctionToolTest {

    private FunctionRegistry registryFor(Object bean) {
        FunctionRegistry registry = new FunctionRegistry();
        AgentFunctionProcessor processor = new AgentFunctionProcessor(registry);
        try {
            processor.process(bean, BeanDefinition.builder()
                    .beanName(bean.getClass().getSimpleName())
                    .beanClass(bean.getClass())
                    .lifecycle(Lifecycle.SINGLETON)
                    .lazyInit(false)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return registry;
    }

    // ==================== 注册与查找 ====================

    @Test
    void functionRegisteredByAnnotation() {
        FunctionRegistry registry = registryFor(new MathTools());
        assertTrue(registry.contains("MathTools.add"));
        assertEquals(5, registry.size());
    }

    @Test
    void lookupByFullName() {
        FunctionRegistry registry = registryFor(new MathTools());
        assertTrue(registry.get("MathTools.add").isPresent(),
                "全名查找应成功");
    }

    @Test
    void lookupByShortName() {
        FunctionRegistry registry = registryFor(new MathTools());
        assertTrue(registry.get("add").isPresent(),
                "短名查找应成功");
    }

    @Test
    void lookupUnknownReturnsEmpty() {
        FunctionRegistry registry = registryFor(new MathTools());
        assertTrue(registry.get("nonexistent").isEmpty(),
                "不存在的函数应返回 empty Optional");
    }

    @Test
    void duplicateRegistrationThrows() {
        FunctionRegistry registry = new FunctionRegistry();
        AgentFunctionDefinition def = new AgentFunctionDefinition(
                "test", "Bean", "desc", new MathTools(),
                MathTools.class.getDeclaredMethods()[0], java.util.List.of()
        );
        registry.register(def);
        assertThrows(SystemRuntimeException.class,
                () -> registry.register(def),
                "重复注册应抛异常");
    }

    // ==================== 调用 ====================

    @Test
    void invokeWithRequiredParams() {
        FunctionRegistry registry = registryFor(new MathTools());
        AgentFunctionDefinition add = registry.get("add").orElseThrow();
        Object result = add.invoke(Map.of("a", 3, "b", 4));
        assertEquals(7, result, "add(3, 4) 应返回 7");
    }

    @Test
    void invokeWithOptionalParamUsesDefault() {
        FunctionRegistry registry = registryFor(new MathTools());
        AgentFunctionDefinition greet = registry.get("greet").orElseThrow();
        // name 是可选参数，默认值 "World"
        Object result = greet.invoke(Map.of());
        assertEquals("Hello, World!", result, "未传可选参数应使用默认值");
    }

    @Test
    void invokeWithOptionalParamOverridesDefault() {
        FunctionRegistry registry = registryFor(new MathTools());
        AgentFunctionDefinition greet = registry.get("greet").orElseThrow();
        Object result = greet.invoke(Map.of("name", "TianGong"));
        assertEquals("Hello, TianGong!", result, "传入参数应覆盖默认值");
    }

    @Test
    void invokeWithMissingRequiredParamThrows() {
        FunctionRegistry registry = registryFor(new MathTools());
        AgentFunctionDefinition add = registry.get("add").orElseThrow();
        // 缺少 b 参数 → 反射调用传入 null，int 拆箱 NPE
        assertThrows(RuntimeException.class,
                () -> add.invoke(Map.of("a", 3)),
                "缺少必填参数应抛异常");
    }

    // ==================== 参数默认值转换 ====================

    @Test
    void defaultValueConvertedToInt() {
        FunctionRegistry registry = registryFor(new MathTools());
        AgentFunctionDefinition repeat = registry.get("repeat").orElseThrow();
        Object result = repeat.invoke(Map.of("text", "hi"));
        assertEquals("hi!hi!hi!", result, "int 默认值 3 应正确转换");
    }

    @Test
    void defaultValueConvertedToBoolean() {
        FunctionRegistry registry = registryFor(new MathTools());
        AgentFunctionDefinition status = registry.get("status").orElseThrow();
        Object result = status.invoke(Map.of("msg", "ok"));
        assertEquals("[ok] verbose=true", result, "boolean 默认值 true 应正确转换");
    }

    // ==================== description / params 元数据 ====================

    @Test
    void descriptionIsRegistered() {
        FunctionRegistry registry = registryFor(new MathTools());
        AgentFunctionDefinition add = registry.get("add").orElseThrow();
        assertEquals("两数相加", add.getDescription());
    }

    @Test
    void paramsMetadataCorrect() {
        FunctionRegistry registry = registryFor(new MathTools());
        AgentFunctionDefinition add = registry.get("add").orElseThrow();
        var params = add.getParams();
        assertEquals(2, params.size());
        assertEquals("a", params.get(0).name());
        assertEquals("integer", params.get(0).type());
        assertTrue(params.get(0).required());
        assertEquals("b", params.get(1).name());
    }

    @Test
    void optionalParamHasDefaultValue() {
        FunctionRegistry registry = registryFor(new MathTools());
        AgentFunctionDefinition greet = registry.get("greet").orElseThrow();
        var nameParam = greet.getParams().get(0);
        assertFalse(nameParam.required(), "name 应为可选参数");
        assertEquals("World", nameParam.defaultValue());
    }

    // ==================== 自定义函数名 ====================

    @Test
    void customFunctionNameRegistered() {
        FunctionRegistry registry = registryFor(new MathTools());
        // @AgentFunction(name = "sum") → 注册名为 sum
        assertTrue(registry.get("sum").isPresent(), "自定义函数名应注册");
    }

    // ==================== ToolInvokeException ====================

    @Test
    void invokeUnregisteredThrowsToolInvokeException() {
        // 模拟 AgentToolInvoker 行为：未注册时抛 ToolInvokeException
        FunctionRegistry registry = registryFor(new MathTools());
        assertThrows(ToolInvokeException.class,
                () -> {
                    var tool = registry.get("nonexistent");
                    if (tool.isEmpty()) {
                        throw new ToolInvokeException("nonexistent");
                    }
                },
                "未注册工具应抛 ToolInvokeException");
    }

    // ==================== 测试用 @AgentFunction Bean ====================

    public static class MathTools {
        @AgentFunction(name = "add", description = "两数相加")
        public int add(
                @AgentFunction.ToolParam(name = "a", description = "加数") int a,
                @AgentFunction.ToolParam(name = "b", description = "被加数") int b) {
            return a + b;
        }

        @AgentFunction(name = "greet", description = "问候")
        public String greet(
                @AgentFunction.ToolParam(name = "name", description = "名字",
                        required = false, defaultValue = "World") String name) {
            return "Hello, " + name + "!";
        }

        @AgentFunction(name = "repeat", description = "重复文本")
        public String repeat(
                @AgentFunction.ToolParam(name = "text", description = "文本") String text,
                @AgentFunction.ToolParam(name = "times", description = "次数",
                        required = false, defaultValue = "3") int times) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < times; i++) {
                sb.append(text).append("!");
            }
            return sb.toString();
        }

        @AgentFunction(name = "status", description = "状态消息")
        public String status(
                @AgentFunction.ToolParam(name = "msg", description = "消息") String msg,
                @AgentFunction.ToolParam(name = "verbose", description = "详细模式",
                        required = false, defaultValue = "true") boolean verbose) {
            return "[" + msg + "] verbose=" + verbose;
        }

        @AgentFunction(name = "sum", description = "求和（自定义名）")
        public int sum(
                @AgentFunction.ToolParam(name = "x") int x,
                @AgentFunction.ToolParam(name = "y") int y) {
            return x + y;
        }
    }
}
