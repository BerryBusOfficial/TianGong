package org.wutong.tiangong.core.agent.entrance;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.time.Instant;

/**
 * 人工审批决定：{@link ApprovalEntranceChannel} 通道的输入类型。<br>
 * <br>
 * 三个核心字段：
 * <ul>
 *     <li>{@link #approved}：是否通过</li>
 *     <li>{@link #approver}：审批人标识（用户 ID / 邮箱 / IM 账号），用于审计</li>
 *     <li>{@link #comment}：审批意见，可选</li>
 * </ul>
 *
 * <p>{@link #timestamp} 由构造时自动填入；序列化为 JSON 后由
 * {@link ApprovalEntranceChannel#toBody} 传给入口节点方法，按节点参数类型
 * （{@code ApprovalDecision} / {@code Map<String,Object>} / 自定义 DTO）反序列化。
 *
 * <p>{@code @JsonCreator} + {@code @JsonProperty} 让 Jackson 用四参构造器反序列化，
 * 无需无参构造器 + setter，因此 {@code ApprovalDecision} 可保持不可变。
 */
@Getter
public final class ApprovalDecision {

    private final boolean approved;
    private final String approver;
    private final String comment;
    private final Instant timestamp;

    public ApprovalDecision(boolean approved, String approver, String comment) {
        this(approved, approver, comment, Instant.now());
    }

    /**
     * Jackson 反序列化构造器（{@code @JsonProperty} 绑定 JSON 字段名）。
     * 由执行器按节点参数类型反序列化时调用。
     */
    @JsonCreator
    public static ApprovalDecision fromJson(
            @JsonProperty("approved") boolean approved,
            @JsonProperty("approver") String approver,
            @JsonProperty("comment") String comment,
            @JsonProperty("timestamp") Instant timestamp) {
        return new ApprovalDecision(approved, approver, comment, timestamp);
    }

    private ApprovalDecision(boolean approved, String approver, String comment, Instant timestamp) {
        this.approved = approved;
        this.approver = approver;
        this.comment = comment;
        this.timestamp = timestamp;
    }
}