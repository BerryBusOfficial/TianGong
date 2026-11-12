package org.wutong.tiangong.annotations.annotation.framework.agent.loop.entrance;

import org.wutong.tiangong.annotations.annotation.Alias;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.AgentNode;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.NodeType;

import java.lang.annotation.*;

/**
 * 定时入口节点：工作流执行到此节点后阻塞等待 cron 触发，每触发一次跑一轮。<br>
 * <br>
 * <h2>语义</h2>
 * 与 {@code @StdinEntranceNode} 同属 {@code SESSION_LOOP}（会话循环）：取一次触发 →
 * 跑节点链到 END → 回到本节点等待下一次触发。<b>本节点不会自己往下走</b>，
 * 工作流会一直驻留在这里，直到被取消或通道关闭。<br>
 * <br>
 * <h2>cron 方言</h2>
 * 复用框架唯一的 cron 实现 {@code org.wutong.tiangong.core.scheduled.CronExpression}
 * （cron-utils / QUARTZ 方言，<b>6 段：秒 分 时 日 月 周</b>）。三条易踩点：
 * <ul>
 *     <li>周日是 <b>1</b> 不是 0（Unix cron 才是 0）</li>
 *     <li>日与周同时给具体值会报错（Quartz 要求其中一个为 {@code ?}）；
 *         想表达"或"就写两条表达式，{@link #cron()} 是数组正是为此</li>
 *     <li>第 7 段「年」合法但很少用</li>
 * </ul>
 * 例子：{@code "0 &#42;/5 * * * ?"} 每 5 分钟；{@code "0 0 9 * * ?"} 每天 9 点；
 * {@code "0 0 9 L * ?"} 每月最后一天 9 点。
 *
 * <h2>触发的三条铁律（框架保证）</h2>
 * <ol>
 *     <li><b>不重叠</b>：一轮没跑完不会并发起第二轮（单线程串行执行）</li>
 *     <li><b>不补发</b>：一轮跑太久，中间错过的拍<b>直接丢弃</b>，
 *         下一拍按"当前时刻"往后算 —— 不会因为停了 1 小时就补 60 次</li>
 *     <li><b>至多 1 个待触发</b>：上一拍还没被取走时新的触发同样丢弃，
 *         避免"积压一批 tick 然后连续空跑"</li>
 * </ol>
 *
 * <h2>方法签名约定</h2>
 * <pre>
 * &#64;CronNode(cron = "0 &#42;/5 &#42; &#42; &#42; ?", zone = "Asia/Shanghai")
 * void everyFiveMinutes(AgentContext ctx, CronTick tick) { ... }
 * </pre>
 * 第一个参数固定为 AgentContext；第二个参数可选，类型为
 * {@code org.wutong.tiangong.core.agent.entrance.CronTick}
 * （含命中的表达式、计划触发时刻、序号）或 {@code String}（该对象的 JSON）。
 * 不写第二个参数也可以 —— 只要"被叫醒"就够的场景不必接参数。
 *
 * <h2>时区必须显式</h2>
 * {@link #zone()} 留空 = {@code ZoneId.systemDefault()}。定时任务跨机器部署时
 * 系统默认时区是不可靠输入，线上环境建议写死（如 {@code "Asia/Shanghai"}）。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@AgentNode(type = NodeType.ENTRANCE)
public @interface CronNode {

    @Alias("name")
    String value() default "";

    @Alias("value")
    String name() default "";

    /**
     * cron 表达式数组（QUARTZ 方言，6 段：秒 分 时 日 月 周）。<br>
     * 多条之间是「或」的关系：任一表达式到点即触发，取最近的一拍。
     */
    String[] cron();

    /**
     * 时区 ID（如 {@code "Asia/Shanghai"}）。留空表示 {@code ZoneId.systemDefault()}。
     */
    String zone() default "";
}
