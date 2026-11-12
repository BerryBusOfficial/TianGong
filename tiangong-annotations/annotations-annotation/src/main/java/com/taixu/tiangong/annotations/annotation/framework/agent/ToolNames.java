package com.taixu.tiangong.annotations.annotation.framework.agent;

import com.taixu.tiangong.annotations.annotation.framework.meta.Agent;

import java.lang.annotation.*;

/**
 * 编译期为工具类生成「工具名常量类」，省得手写那一串 {@code public static final String}。<br>
 * <br>
 * 标在类上，处理器收集本类所有 {@link AgentFunction} 方法，生成一个同包的常量类：
 * <pre>{@code
 * @AgentTool
 * @ToolNames
 * public class SystemTool {
 *
 *     @AgentFunction(name = "get_sys_time", description = "获取系统当前时间")
 *     public String getSystemTime() { ... }
 * }
 *
 * // 自动生成 org.wutong.tiangong.user.tool.SystemToolNames：
 * public final class SystemToolNames {
 *     public static final String GET_SYSTEM_TIME = "get_sys_time";
 * }
 * }</pre>
 * 于是调用方可以写 {@code SystemToolNames.GET_SYSTEM_TIME}，而不是到处散落字符串字面量。
 *
 * <h2>常量名与常量值分别怎么来的</h2>
 * <ul>
 *     <li><b>常量名</b> = 方法名转 {@code UPPER_SNAKE}：{@code getSystemTime} → {@code GET_SYSTEM_TIME}</li>
 *     <li><b>常量值</b> = {@link AgentFunction#name()}；留空时退回方法名 ——
 *         这条必须和运行期保持一致，否则生成的常量和运行期注册的工具名对不上，
 *         按生成的名字去查注册表会查空（且不报错）</li>
 * </ul>
 *
 * <h2>为什么它是 SOURCE 而 {@link AgentTool} 不是</h2>
 * 本注解只服务于编译期代码生成，运行期没有任何地方读它，没必要进字节码。<br>
 * ⚠ 别照着它把 {@link AgentTool} 也改成 {@code SOURCE}：{@code TianGongScanner} 靠
 * ClassGraph 扫<b>字节码</b>里的 {@code @AgentTool} 来发现 Bean，改成 SOURCE 之后
 * 工具类一个都扫不到 —— 实测同一份代码，SOURCE 命中 0 个、RUNTIME 命中 2 个，
 * 后果是整个类的 {@code @AgentFunction} 不再进 {@code FunctionRegistry}。
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
@Agent
public @interface ToolNames {

    /**
     * 生成的常量类<b>简单名</b>。<br>
     * 留空（默认）取 {@code <类名> + "Names"}。重名时才需要在这儿指定。
     */
    String value() default "";

}
