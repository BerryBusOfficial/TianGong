package org.wutong.tiangong.user.model;

/**
 * 桌宠心情。<br>
 * <br>
 * 做成枚举而不是自由字符串：心情要参与状态演化的规则判断，
 * 自由文本会让「什么算心情好」散落在提示词里，无法在本体侧收敛。
 */
public enum Mood {

    HAPPY("开心", "语气上扬，多用感叹号"),
    EXCITED("兴奋", "话多，动作幅度大"),
    CALM("平静", "温和简短，偶尔关心一句"),
    CURIOUS("好奇", "主动提问，追问细节"),
    SLEEPY("困倦", "反应慢半拍，句子短，带哈欠"),
    GRUMPY("闹别扭", "话少，简短回应，但不真的生气（不超过 3 秒）");

    private final String label;
    private final String actingNote;

    Mood(String label, String actingNote) {
        this.label = label;
        this.actingNote = actingNote;
    }

    /** 中文名：进提示词用 */
    public String label() {
        return label;
    }

    /** 演戏提示：告诉模型这个心情该怎么演 */
    public String actingNote() {
        return actingNote;
    }
}
