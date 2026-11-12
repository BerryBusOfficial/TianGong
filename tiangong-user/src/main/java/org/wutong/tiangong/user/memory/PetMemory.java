package org.wutong.tiangong.user.memory;

import lombok.extern.slf4j.Slf4j;
import org.wutong.tiangong.annotations.annotation.framework.Component;
import org.wutong.tiangong.annotations.annotation.framework.inject.Value;
import org.wutong.tiangong.common.agent.capability.AgentCapability;
import org.wutong.tiangong.user.model.MemoryFact;
import org.wutong.tiangong.user.model.MemorySnapshot;
import org.wutong.tiangong.user.model.Mood;
import org.wutong.tiangong.user.model.PetState;
import org.wutong.tiangong.user.store.JsonFileStore;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 桌宠的记忆系统：状态层 + 事实层。<br>
 * <br>
 * <h2>两层为什么要放一起</h2>
 * <ul>
 *     <li><b>状态层</b>（{@link PetState}）：宠物自己的心情/精力/好感度，随交互演化</li>
 *     <li><b>事实层</b>（{@link MemoryFact}）：关于用户的事实，跨会话留存</li>
 * </ul>
 * 二者都是「跨会话存活、要落盘」的东西，生命周期与失败处理完全一致，
 * 拆成两个单例只会让落盘时机分裂成两处。<br>
 * <br>
 *
 * <h2>一期刻意不做的事</h2>
 * <ul>
 *     <li><b>不做自动提炼</b>：不额外调 LLM 从对话里抽事实。写入一律由模型通过
 *         工具显式触发 —— 自动提炼的召回与准确率都没有保障，还会悄悄写入错误事实，
 *         而错误事实比没记住更糟（它会一直误导后续对话）</li>
 *     <li><b>不做向量检索</b>：事实量级在几十条，全量进提示词比检索简单且更可控</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * 单例被 STDIN 与 API 两条工作流共享，写入方法全部 {@code synchronized}。
 */
@Slf4j
@Component
public class PetMemory implements AgentCapability {

    public static final String DEFAULT_PATH = "data/memory.json";

    /** 事实条数上限：超出淘汰最久未更新的 —— 记忆无限增长会拖垮提示词 */
    public static final int MAX_FACTS = 100;

    /** 单次注入提示词的事实条数上限 */
    public static final int MAX_RECALL = 20;

    @Value(value = "pet.memory.path", defaultValue = DEFAULT_PATH)
    private String memoryPath = DEFAULT_PATH;

    private volatile PetState state = new PetState();
    private final Map<String, MemoryFact> facts = new LinkedHashMap<>();
    private volatile boolean loaded = false;

    /** 显式指定落盘路径（测试与特殊部署用） */
    public synchronized void setMemoryPath(Path path) {
        this.memoryPath = path == null ? DEFAULT_PATH : path.toString();
        this.loaded = false;
    }

    public Path path() {
        return Path.of(memoryPath == null ? DEFAULT_PATH : memoryPath);
    }

    /**
     * 加载。幂等；重复调用只在实际读到文件时刷新内存态。<br>
     * 懒加载而非构造时加载：{@code @Value} 在容器 refresh 阶段才注入，
     * 构造器中读路径拿到的还是默认值。
     */
    public synchronized void load() {
        if (loaded) {
            return;
        }
        MemorySnapshot snapshot = JsonFileStore.read(path(), MemorySnapshot.class).orElse(null);
        if (snapshot == null) {
            log.info("未找到记忆文件，以初始状态启动: {}", path());
        } else {
            this.state = snapshot.safeState();
            for (MemoryFact fact : snapshot.safeFacts()) {
                if (fact != null && fact.getKey() != null && !fact.getKey().isBlank()) {
                    facts.put(fact.getKey(), fact);
                }
            }
            log.info("已加载记忆：{} 条事实，心情={}", facts.size(), state.getMood());
        }
        loaded = true;
    }

    /** 落盘。写入失败只告警（见 {@link JsonFileStore#write}） */
    public synchronized void save() {
        if (!loaded) {
            return;   // 从未加载过就不要写，避免用默认值覆盖真实文件
        }
        MemorySnapshot snapshot = new MemorySnapshot();
        snapshot.setState(state);
        snapshot.setFacts(new ArrayList<>(facts.values()));
        JsonFileStore.write(path(), snapshot);
    }

    // ---- 事实层 ----

    /** 记住一条事实。同 key 覆盖（用户的偏好会变，保留旧值没有意义） */
    public synchronized void remember(String key, String value) {
        load();
        if (key == null || key.isBlank() || value == null || value.isBlank()) {
            return;
        }
        MemoryFact fact = facts.get(key.trim());
        if (fact == null) {
            fact = new MemoryFact(key.trim(), value.trim());
        } else {
            fact.setValue(value.trim());
            fact.touch();
        }
        facts.put(fact.getKey(), fact);
        evictIfNeeded();
        save();
        log.info("记住事实: {} = {}", fact.getKey(), fact.getValue());
    }

    /** 忘掉一条事实；不存在时返回 null */
    public synchronized String forget(String key) {
        load();
        if (key == null) {
            return null;
        }
        MemoryFact removed = facts.remove(key.trim());
        if (removed != null) {
            save();
        }
        return removed == null ? null : removed.getValue();
    }

    public synchronized String recall(String key) {
        load();
        MemoryFact fact = key == null ? null : facts.get(key.trim());
        return fact == null ? null : fact.getValue();
    }

    public synchronized List<MemoryFact> facts() {
        load();
        return List.copyOf(facts.values());
    }

    /**
     * 拼成给模型看的文本。<br>
     * 限制条数：这是常驻提示词的一部分，事实变多会挤占对话预算。
     */
    public synchronized String recallText() {
        load();
        if (facts.isEmpty()) {
            return "";
        }
        List<MemoryFact> recent = facts.values().stream()
                .sorted(Comparator.comparing(MemoryFact::getUpdatedAt).reversed())
                .limit(MAX_RECALL)
                .toList();
        StringBuilder sb = new StringBuilder("## 你记得的关于用户的事\n");
        for (MemoryFact fact : recent) {
            sb.append("- ").append(fact.getKey()).append(": ").append(fact.getValue()).append('\n');
        }
        return sb.toString();
    }

    // ---- 状态层 ----

    public synchronized PetState state() {
        load();
        return state;
    }

    /** 状态文本：进提示词，告诉模型现在该用什么心情说话 */
    public synchronized String stateText() {
        load();
        PetState s = state;
        return "## 你当前的状态\n"
                + "- 心情: " + s.getMood().label() + "（" + s.getMood().actingNote() + "）\n"
                + "- 精力: " + s.getEnergy() + "/100\n"
                + "- 好感度: " + s.getAffinity() + "/100\n"
                + "- 已陪伴: " + s.getTotalTurns() + " 轮\n";
    }

    public synchronized void setMood(Mood mood) {
        load();
        state.setMood(mood);
        save();
    }

    /**
     * 一轮对话结束时的状态演化。<br>
     * 固定扣精力、涨好感：不做情感分析（那需要额外一次 LLM 调用，
     * 且判断错了会往反方向演化，代价高于收益）。
     */
    public synchronized void afterTurn(LocalDateTime now) {
        load();
        state.recoverIdleEnergy(now);
        state.adjustEnergy(-PetState.ENERGY_COST_PER_TURN);
        state.adjustAffinity(1);
        state.setTotalTurns(state.getTotalTurns() + 1);
        state.setLastInteractionAt(now.toString());
        state.syncMoodWithEnergy();
        save();
    }

    private void evictIfNeeded() {
        while (facts.size() > MAX_FACTS) {
            String oldest = facts.values().stream()
                    .min(Comparator.comparing(MemoryFact::getUpdatedAt))
                    .map(MemoryFact::getKey)
                    .orElse(null);
            if (oldest == null) {
                return;
            }
            facts.remove(oldest);
            log.debug("事实数超上限，淘汰最久未更新: {}", oldest);
        }
    }
}
