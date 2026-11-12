package org.wutong.tiangong.annotations.annotation.framework.agent.loop;

import org.wutong.tiangong.annotations.annotation.Alias;
import org.wutong.tiangong.annotations.annotation.framework.agent.interfaces.StartCondition;
import org.wutong.tiangong.annotations.annotation.framework.meta.Agent;

import java.lang.annotation.*;

/**
 * 注册一个方法为一个节点，若value()/name()不传入，则使用方法名为对应的value()/name()，type()决定node的具体类型。<br>
 * <br>
 * 各类型的返回值与@NodeNext约束：
 * <ul>
 *     <li>START/COMMON/ENTRANCE/EXPORT/MERGE：返回 void 或 AgentContext；@NodeNext 仅允许 0 或 1 个</li>
 *     <li>END：返回 void 或 AgentContext；不允许 @NodeNext</li>
 *     <li>BRANCH：返回 String（下一个节点名，必须在 @NodeNext 声明范围内）；@NodeNext 必须 ≥ 2 个</li>
 *     <li>PARALLEL：返回 void 或 AgentContext；@NodeNext 必须 ≥ 2 个（并发执行）</li>
 * </ul>
 */
@Agent
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
public @interface AgentNode {

    @Alias("name")
    String value() default "";

    @Alias("value")
    String name() default "";

    /**
     * 节点类型
     */
    NodeType type() default NodeType.COMMON;

    /**
     * 起始节点启动条件（仅 NodeType=START 有效）。<br>
     * 存在多个 START 节点时，框架按此条件选择起始节点；未指定时恒为 true。
     */
    Class<? extends StartCondition> start() default StartCondition.class;

    /**
     * 崩溃恢复时的可重放性声明（检查点契约 A5）。<br>
     * <br>
     * 默认 {@link ReplayPolicy#ALWAYS}：允许恢复时补跑。<br>
     * 含外部副作用的节点（调外部 API、写文件、发消息、MCP 工具）应声明
     * {@link ReplayPolicy#NEVER}，避免"已开始但未完成"状态下被重复执行。
     * <br>
     * 注意：本属性只影响恢复路径，不影响节点在正常运行中的执行。
     */
    ReplayPolicy replay() default ReplayPolicy.ALWAYS;

}
