package org.wutong.tiangong.user.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 记忆的落盘结构。<br>
 * <br>
 * 单独一个快照类而不是直接序列化 {@code PetMemory}：持久化结构一旦等于运行期对象，
 * 以后给 {@code PetMemory} 加字段（哪怕只是缓存）都会改变文件格式。<br>
 * 有了这层，字段可以自由增删，且<b>老文件读回来时缺失字段取默认值</b>而不是解析失败。
 */
@Data
public class MemorySnapshot {

    private PetState state = new PetState();
    private List<MemoryFact> facts = new ArrayList<>();

    /** 防御：Jackson 读回来时集合字段可能是 null（手改过的文件） */
    public List<MemoryFact> safeFacts() {
        return facts == null ? new ArrayList<>() : facts;
    }

    public PetState safeState() {
        return state == null ? new PetState() : state;
    }
}
