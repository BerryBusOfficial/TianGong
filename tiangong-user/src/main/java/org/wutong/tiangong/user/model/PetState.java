package org.wutong.tiangong.user.model;

import lombok.Data;

/**
 * 桌宠的 persistent 状态：跨会话、跨重启存活的那部分「宠物性」。<br>
 * <br>
 * <h2>为什么状态要独立于上下文</h2>
 * 上下文（{@code PetContext}）是 PROTOTYPE，每条工作流一个实例、终止即释放。
 * 若把心情好感度放在上下文里，工作流一结束宠物就「失忆」，陪伴感无从谈起。<br>
 * 因此状态由容器单例 {@code PetMemory} 持有，落盘保存。<br>
 * <br>
 *
 * <h2>为什么数值用 int 而不用浮点</h2>
 * 演化靠规则（加减），浮点会有累积误差且落盘后不直观；int 加 clamp 足够，
 * 也便于在文件里直接看懂。
 */
@Data
public class PetState {

    public static final int MAX = 100;
    public static final int MIN = 0;

    /** 每轮对话消耗的精力 */
    public static final int ENERGY_COST_PER_TURN = 2;
    /** 每小时自然恢复的精力 */
    public static final int ENERGY_RECOVER_PER_HOUR = 10;

    private Mood mood = Mood.CALM;

    /** 精力 0-100：随对话消耗，随时间恢复 */
    private int energy = 80;

    /** 好感度 0-100：随正向互动缓慢上升 */
    private int affinity = 50;

    /** 累计对话轮数 */
    private long totalTurns = 0;

    /** 上次互动时间（ISO 字符串，避免 Jackson 时间模块的额外依赖） */
    private String lastInteractionAt = "";

    public void setMood(Mood mood) {
        this.mood = mood == null ? Mood.CALM : mood;
    }

    /** 带边界的增减：不做静默截断，clamp 到此方法一处 */
    public void adjustEnergy(int delta) {
        this.energy = clamp(this.energy + delta);
    }

    public void adjustAffinity(int delta) {
        this.affinity = clamp(this.affinity + delta);
    }

    /**
     * 按距上次互动的时长恢复精力。<br>
     * 只在有上次时间记录时生效 —— 首次运行没有基准，凭空恢复会让数值不可解释。
     *
     * @return 是否发生了恢复
     */
    public boolean recoverIdleEnergy(java.time.LocalDateTime now) {
        if (lastInteractionAt == null || lastInteractionAt.isBlank()) {
            return false;
        }
        long hours;
        try {
            hours = java.time.Duration.between(
                    java.time.LocalDateTime.parse(lastInteractionAt), now).toHours();
        } catch (java.time.format.DateTimeParseException e) {
            return false;   // 文件被手改坏了：不恢复，也不要崩
        }
        if (hours <= 0) {
            return false;
        }
        int before = energy;
        adjustEnergy((int) (hours * ENERGY_RECOVER_PER_HOUR));
        return energy != before;
    }

    /** 精力耗尽时心情自动转困倦 —— 状态之间要有联动，否则数值只是摆设 */
    public void syncMoodWithEnergy() {
        if (energy <= 20 && mood != Mood.SLEEPY) {
            this.mood = Mood.SLEEPY;
        }
    }

    public static int clamp(int value) {
        return Math.max(MIN, Math.min(MAX, value));
    }
}
