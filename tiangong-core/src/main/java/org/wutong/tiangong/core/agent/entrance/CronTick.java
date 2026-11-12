package org.wutong.tiangong.core.agent.entrance;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 一次 cron 触发的载荷：工作流被定时叫醒时，节点方法拿到的就是这个。<br>
 * <br>
 * 字段全部显式 {@code @JsonProperty}：框架全局 {@code JsonUtil} 是 SNAKE_CASE，
 * 而通道 → 节点方法这条链路是"序列化后再反序列化"的往返，两边必须严格同名。
 * 不写显式名字时大小写策略一变就会静默取不到值（历史教训）。
 *
 * @param expression    命中的那条 cron 表达式（{@code @CronNode(cron = {...})} 里的一条）
 * @param scheduledTime 计划触发时刻（带时区，ISO-8601）
 * @param epochMillis   计划触发时刻的毫秒时间戳
 * @param zone          计算所用时区
 * @param sequence      本订阅的第几次触发（从 1 开始）
 */
public record CronTick(
        @JsonProperty("expression") String expression,
        @JsonProperty("scheduledTime") String scheduledTime,
        @JsonProperty("epochMillis") long epochMillis,
        @JsonProperty("zone") String zone,
        @JsonProperty("sequence") long sequence
) {
}
