package org.wutong.tiangong.user.model;

import lombok.Data;

/**
 * 一条被记住的用户事实。<br>
 * <br>
 * 刻意保持为「键值对 + 时间」的扁平结构：一期不做向量检索、不做自动提炼，
 * 写入由模型通过工具显式触发。扁平结构让「记住了什么」在文件里一眼可查，
 * 也让去重与覆盖规则可以非常简单（同 key 覆盖）。<br>
 * <br>
 * 时间用 ISO 字符串而非 {@code LocalDateTime}：落盘 JSON 不依赖 Jackson 的
 * 时间模块，文件也不会因为时区配置不同而读不回来。
 */
@Data
public class MemoryFact {

    private String key;
    private String value;
    private String updatedAt;

    public MemoryFact() {
    }

    public MemoryFact(String key, String value) {
        this.key = key;
        this.value = value;
        this.updatedAt = java.time.LocalDateTime.now().toString();
    }

    public void touch() {
        this.updatedAt = java.time.LocalDateTime.now().toString();
    }
}
