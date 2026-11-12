package com.taixu.tiangong.core.checkpoint;

import lombok.extern.slf4j.Slf4j;
import com.taixu.tiangong.common.agent.checkpoint.ContextSnapshot;
import com.taixu.tiangong.common.agent.checkpoint.ContextSnapshotter;
import com.taixu.tiangong.common.agent.context.AgentContext;
import com.taixu.tiangong.common.exception.CheckpointException;
import com.taixu.tiangong.core.agent.AgentNodeDefinition;
import com.taixu.tiangong.core.config.ConfigHolder;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 检查点服务：检查点语义的唯一落点（office/checkpoint-contract.md）。<br>
 * <br>
 * 不变量在此集中实现，执行器只负责在节点边界调用 {@link #begin} / {@link #complete} /
 * {@link #fail}：<br>
 * <ul>
 *     <li><b>A1</b> 恢复点只认 COMPLETED；STARTED 未完成视为"执行中崩溃或等待输入"</li>
 *     <li><b>A2</b> 记录 {@code fromNode} 与 {@code selectedNext}，恢复时重放决策而非重算</li>
 *     <li><b>A3</b> 快照经 {@link ContextSnapshotter} 产出，失败即抛，禁止静默降级</li>
 *     <li><b>A4</b> 负载自带 {@code schemaVersion} 与 {@code contextType}</li>
 *     <li><b>A5</b> 恢复时按节点的 {@code ReplayPolicy} 决定是否允许重跑</li>
 *     <li><b>A6</b> {@code workflowId} 为 null 的上下文（PARALLEL 分支副本、HTTP fork 副本之外的场景）
 *         不产生检查点</li>
 *     <li><b>A7</b> 主键 {@code (workflowId, nodePath, seq)}，seq 由本服务分配</li>
 * </ul>
 * <br>
 * <b>关闭时的行为</b>：{@link #setEnabled(boolean)} 为 false 时所有方法退化为空操作，
 * 执行器无需做分支判断。
 */
@Slf4j
public class CheckpointService {

    /** nodePath 的层级分隔符（不变量 A7） */
    public static final String NODE_PATH_SEPARATOR = "/";

    /**
     * 快照负载的告警阈值（字符）。超过即告警，提示"状态可能无界"（契约 D2）。
     * <p>为什么只告警不阻断：快照偏大本身不是错误，但它会让检查点成本随会话长度
     * 二次增长；阻断会直接把可用的长会话打死，而告警能把问题暴露到日志里。</p>
     * <p>可通过配置 {@value #CONFIG_SNAPSHOT_WARN_CHARS} 覆盖，或调用
     * {@link #setSnapshotWarnChars(int)}；{@code <= 0} 表示关闭告警。</p>
     */
    public static final int DEFAULT_SNAPSHOT_WARN_CHARS = 64 * 1024;

    /** 告警阈值的配置键（{@code agent.checkpoint.snapshot-warn-chars}） */
    public static final String CONFIG_SNAPSHOT_WARN_CHARS = "agent.checkpoint.snapshot-warn-chars";

    private final CheckpointStore store;
    private final ContextSnapshotter snapshotter;
    private final SnapshotPolicy snapshotPolicy;

    /** 每个工作流的 seq 分配器，首次使用时从存储中的最大 seq 续起 */
    private final ConcurrentHashMap<String, AtomicLong> seqCounters = new ConcurrentHashMap<>();

    /** 已就"快照过大"告警过的工作流，避免每个节点刷一条日志 */
    private final java.util.Set<String> oversizeWarned = ConcurrentHashMap.newKeySet();

    private volatile int snapshotWarnChars = DEFAULT_SNAPSHOT_WARN_CHARS;

    private volatile boolean enabled = true;

    /** 使用默认快照粒度策略（{@link SnapshotPolicy#ALWAYS}） */
    public CheckpointService(CheckpointStore store, ContextSnapshotter snapshotter) {
        this(store, snapshotter, SnapshotPolicy.ALWAYS);
    }

    /**
     * @param store          存储实现
     * @param snapshotter    快照器
     * @param snapshotPolicy 快照粒度策略；null 时回退 {@link SnapshotPolicy#ALWAYS}
     */
    public CheckpointService(CheckpointStore store, ContextSnapshotter snapshotter,
                             SnapshotPolicy snapshotPolicy) {
        if (store == null) {
            throw new IllegalArgumentException("store 不能为 null");
        }
        if (snapshotter == null) {
            throw new IllegalArgumentException("snapshotter 不能为 null");
        }
        this.store = store;
        this.snapshotter = snapshotter;
        this.snapshotPolicy = snapshotPolicy != null ? snapshotPolicy : SnapshotPolicy.ALWAYS;
        this.snapshotWarnChars = readWarnCharsFromConfig();
    }

    /**
     * 读取告警阈值配置。<br>
     * 配置未加载（例如手工 new 出来的服务、测试环境）时退回默认值而不是抛异常 ——
     * 阈值只影响诊断，不该拖垮服务可用性。
     */
    private static int readWarnCharsFromConfig() {
        try {
            Integer value = ConfigHolder.getValueOrDefault(
                    CONFIG_SNAPSHOT_WARN_CHARS, Integer.class, DEFAULT_SNAPSHOT_WARN_CHARS);
            return value == null || value < 0 ? DEFAULT_SNAPSHOT_WARN_CHARS : value;
        } catch (Throwable t) {
            return DEFAULT_SNAPSHOT_WARN_CHARS;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 关闭后所有检查点操作退化为空操作 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public CheckpointStore store() {
        return store;
    }

    public ContextSnapshotter snapshotter() {
        return snapshotter;
    }

    /** 当前生效的快照粒度策略 */
    public SnapshotPolicy snapshotPolicy() {
        return snapshotPolicy;
    }

    /** 快照负载告警阈值（字符）；<= 0 表示关闭告警 */
    public int snapshotWarnChars() {
        return snapshotWarnChars;
    }

    public void setSnapshotWarnChars(int snapshotWarnChars) {
        this.snapshotWarnChars = snapshotWarnChars;
    }

    // ==================== 节点边界 ====================

    /**
     * 节点开始执行：写入 STARTED 记录，负载为<b>节点执行前</b>的状态。<br>
     * 该负载用于"重跑本节点"（A1 的 replay 分支）。
     *
     * @param node     即将执行的节点
     * @param context  当前上下文
     * @param fromNode 迁移来源节点名；工作流首个节点传 null
     * @return 写入的记录；未启用或上下文不归属工作流时返回 null
     */
    public CheckpointRecord begin(AgentNodeDefinition node, AgentContext context, String fromNode) {
        if (!enabled) {
            return null;
        }
        String workflowId = workflowIdOf(context);
        if (workflowId == null) {
            // A6：PARALLEL 分支副本 / 无归属上下文不产生检查点
            return null;
        }
        if (!snapshotPolicy.shouldSnapshot(node, context)) {
            // 粒度策略决定跳过本节点边界：不产生记录，也不影响执行
            return null;
        }

        // A3：快照失败会抛 CheckpointException。开启检查点即接受该语义（禁止静默降级）。
        ContextSnapshot snapshot = snapshotter.capture(context);
        warnIfOversize(workflowId, node.name(), snapshot);

        CheckpointRecord record = new CheckpointRecord(
                workflowId,
                nodePathOf(node),
                nextSeq(workflowId),
                node.name(),
                node.type().name(),
                fromNode,
                node.replayable(),
                snapshot.schemaVersion(),
                snapshot.contextType(),
                snapshot.payload());

        try {
            return store.append(record);
        } catch (Exception e) {
            throw new CheckpointException(
                    "检查点写入失败: " + record.getId(), e);
        }
    }

    /**
     * 节点执行完成：状态置为 COMPLETED，记录选中的出边，并把负载替换为
     * <b>节点执行后</b>的状态（恢复点需要的是"从 {@code selectedNext} 继续"的输入状态）。
     *
     * @param record       由 {@link #begin} 返回的记录；null 时空操作
     * @param context      节点执行后的上下文
     * @param selectedNext 本轮选中的出边；END 节点传 null
     */
    public void complete(CheckpointRecord record, AgentContext context, String selectedNext) {
        if (record == null || !enabled) {
            return;
        }
        ContextSnapshot postState = snapshotter.capture(context);
        record.markCompleted(postState, selectedNext);
        try {
            store.update(record);
        } catch (Exception e) {
            throw new CheckpointException(
                    "检查点状态更新失败: " + record.getId(), e);
        }
        if (log.isTraceEnabled()) {
            log.trace("检查点已提交 {}", record);
        }
    }

    /**
     * 节点执行失败：状态置为 FAILED。<br>
     * 这里记录失败不抛异常——否则会掩盖节点本身的原始异常。
     */
    public void fail(CheckpointRecord record, Throwable error) {
        if (record == null || !enabled) {
            return;
        }
        String message = error == null
                ? "unknown"
                : error.getClass().getName() + ": " + error.getMessage();
        record.markFailed(message);
        try {
            store.update(record);
        } catch (Exception e) {
            log.warn("检查点失败状态写入失败: {}", record.getId(), e);
        }
    }

    // ==================== 恢复与回看（P2） ====================

    /**
     * 解析工作流的恢复点（A1 + A2 + A5）。
     *
     * @return 无检查点或已走到 END 时返回 empty
     * @throws CheckpointException 未完成节点声明了 {@code ReplayPolicy.NEVER}（拒绝自动重放），
     *                             或同一 workflowId 存在多条未完成记录（并发写入异常）
     */
    public Optional<ResumePoint> resumePoint(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            return Optional.empty();
        }

        List<CheckpointRecord> unfinished = store.unfinished(workflowId);
        if (unfinished.size() > 1) {
            throw new CheckpointException(
                    "工作流 [" + workflowId + "] 存在 " + unfinished.size()
                            + " 条未完成检查点，说明同一 workflowId 被并发写入（违反单写者假设）: "
                            + unfinished.stream().map(CheckpointRecord::getId).toList());
        }

        if (unfinished.size() == 1) {
            CheckpointRecord pending = unfinished.get(0);
            if (!pending.replayable()) {
                throw new CheckpointException(
                        "节点 [" + pending.nodeName() + "] 声明了 ReplayPolicy.NEVER 且处于"
                                + "「已开始但未完成」状态（seq=" + pending.seq()
                                + "），框架拒绝自动重放以避免重复副作用。"
                                + "需要人工确认外部副作用是否已发生后再决定。");
            }
            return Optional.of(new ResumePoint(
                    workflowId, pending, pending.nodeName(), true, restore(pending)));
        }

        Optional<CheckpointRecord> last = store.latestCompleted(workflowId);
        if (last.isEmpty()) {
            return Optional.empty();
        }
        CheckpointRecord completed = last.get();
        String next = completed.selectedNext();
        if (next == null || next.isBlank()) {
            // 最后完成的节点是 END：工作流已正常结束，无恢复点
            return Optional.empty();
        }
        return Optional.of(new ResumePoint(
                workflowId, completed, next, false, restore(completed)));
    }

    /** 时间旅行：按 seq 升序返回某工作流的全部检查点记录（只读） */
    public List<CheckpointRecord> history(String workflowId) {
        return store.history(workflowId);
    }

    /**
     * 工作流的起始节点名（START 节点）。<br>
     * 依据：{@code doExecute} 的第一个节点必然是该工作流的 START 节点，
     * 因此 {@code history} 的首条记录的 {@code nodeName} 即为起始节点。<br>
     * 供恢复时确定该工作流归属的 START（触发策略与并发账务都挂在 START 上）。
     */
    public Optional<String> startNodeOf(String workflowId) {
        List<CheckpointRecord> all = store.history(workflowId);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0).nodeName());
    }

    /**
     * 放弃未完成的记录：全部标记为 FAILED。<br>
     * <br>
     * 使用场景：决定<b>重跑</b>未完成节点之前。否则旧记录会一直停留在 STARTED，
     * 下次 {@link #resumePoint} 会看到两条未完成记录，被误判为"同一 workflowId 被并发写入"。
     * 标记为 FAILED 而不是删除，是为了在历史中如实保留"这一次尝试被放弃"。
     *
     * @return 被放弃的记录数
     */
    public int abandonUnfinished(String workflowId, String reason) {
        List<CheckpointRecord> unfinished = store.unfinished(workflowId);
        for (CheckpointRecord record : unfinished) {
            record.markFailed("恢复时放弃: " + reason);
            try {
                store.update(record);
            } catch (Exception e) {
                log.warn("放弃未完成检查点失败: {}", record.getId(), e);
            }
        }
        return unfinished.size();
    }

    /**
     * 时间旅行：还原任意一条历史检查点所记录的状态。<br>
     * 只读回看，不产生新的检查点，也不影响运行中的工作流。
     */
    public AgentContext stateAt(CheckpointRecord record) {
        return restore(record);
    }

    /** 列出所有存在检查点的工作流 ID */
    public List<String> workflowIds() {
        return store.workflowIds();
    }

    /** 清除某工作流的检查点 */
    public int purge(String workflowId) {
        seqCounters.remove(workflowId);
        oversizeWarned.remove(workflowId);
        return store.purge(workflowId);
    }

    /** 底层存储是否具备跨进程存活能力 */
    public boolean durable() {
        return store.durable();
    }

    // ==================== 内部 ====================

    private AgentContext restore(CheckpointRecord record) {
        AgentContext context = snapshotter.restore(new ContextSnapshot(
                record.schemaVersion(), record.contextType(), record.contextPayload()));
        // D1：还原出的对象是一个普通对象，没有经过容器注入。被 @SnapshotIgnore 排除的
        // 成员（容器单例 Bean、外部句柄等）此刻是未初始化的，必须由应用重新装配。
        try {
            context.afterRestore();
        } catch (Exception e) {
            throw new CheckpointException(
                    "上下文还原后的重新装配失败（类型: " + record.contextType()
                            + "，afterRestore 抛异常）", e);
        }
        return context;
    }

    /**
     * 快照过大时的告警（契约 D2：状态必须是有界的）。<br>
     * <br>
     * 负载随会话长度线性增长 → 单轮成本线性 → 整场会话总成本二次增长。
     * 每个工作流只告警一次，避免逐节点刷屏。
     */
    private void warnIfOversize(String workflowId, String nodeName, ContextSnapshot snapshot) {
        int limit = snapshotWarnChars;
        if (limit <= 0) {
            return;
        }
        int size = snapshot.payload().length();
        if (size <= limit) {
            return;
        }
        if (!oversizeWarned.add(workflowId)) {
            return;
        }
        log.warn("检查点快照过大：工作流 [{}] 在节点 [{}] 处的负载为 {} 字符（阈值 {}）。"
                        + "这通常意味着状态无界（例如把无上限的历史集合纳入了快照），"
                        + "会让检查点成本随会话长度二次增长。"
                        + "请检查上下文中是否存在无界成员，必要时用 @SnapshotIgnore 排除"
                        + "（检查点契约 D2）。",
                workflowId, nodeName, size, limit);
    }

    private String workflowIdOf(AgentContext context) {
        if (context == null) {
            return null;
        }
        String workflowId = context.workflowId();
        return (workflowId == null || workflowId.isBlank()) ? null : workflowId;
    }

    /**
     * nodePath（不变量 A7）。当前恒等于节点名；层级形态（如 {@code parent/sub}）
     * 预留给将来的"节点内嵌子工作流"，届时无需迁移历史数据。
     */
    private String nodePathOf(AgentNodeDefinition node) {
        return node.name();
    }

    /**
     * 分配 seq（不变量 A7）：同一 workflowId 内单调递增，从 1 开始。<br>
     * 首次使用时从存储中的最大 seq 续起，因此恢复后继续执行不会与历史记录冲突。
     */
    private long nextSeq(String workflowId) {
        return seqCounters
                // 初值 = 「下一个要分配的号」；空仓时 maxSeq 返回 -1，故下限取 1
                .computeIfAbsent(workflowId, id -> new AtomicLong(Math.max(store.maxSeq(id) + 1, 1)))
                .getAndIncrement();
    }
}
