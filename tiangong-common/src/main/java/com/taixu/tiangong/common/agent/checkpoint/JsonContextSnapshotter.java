package com.taixu.tiangong.common.agent.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taixu.tiangong.common.agent.context.AgentContext;
import com.taixu.tiangong.common.exception.CheckpointException;
import com.taixu.tiangong.common.util.JsonUtil;

/**
 * 默认快照器：基于 Jackson 的 JSON 序列化（office/checkpoint-contract.md B2）。<br>
 * <br>
 * 与 {@link AgentContext#copy()} 的关键区别是<b>失败语义</b>：<br>
 * <ul>
 *     <li>{@code copy()} 是 PARALLEL 分支副本机制，失败时曾静默返回 {@code this}
 *         （分支退化为共享状态），现已改为抛异常；</li>
 *     <li>本类所有失败路径一律抛 {@link CheckpointException}——快照失败意味着
 *         "写了坏检查点"，绝不能默默继续。</li>
 * </ul>
 * <br>
 * 上下文需要满足 Jackson 的还原要求（默认构造器或 {@code @JsonCreator}）。
 * 不满足时请换用自定义 {@link ContextSnapshotter}，不要放宽这里的检查。
 */
public class JsonContextSnapshotter implements ContextSnapshotter {

    private final ObjectMapper mapper;

    /**
     * 默认构造：使用「共享配置的副本 + {@link SnapshotIgnore} 支持」的 mapper。<br>
     * 之所以 copy 而不是直接用 {@code JsonUtil} 的共享实例：快照器需要额外挂
     * {@link SnapshotIgnoreIntrospector}，直接改共享 mapper 会污染 LLM 请求构建等链路。
     */
    public JsonContextSnapshotter() {
        this(defaultMapper());
    }

    /** 使用自定义 mapper（需自行保证与还原侧的配置一致） */
    public JsonContextSnapshotter(ObjectMapper mapper) {
        if (mapper == null) {
            throw new IllegalArgumentException("mapper 不能为 null");
        }
        this.mapper = mapper;
    }

    private static ObjectMapper defaultMapper() {
        ObjectMapper copy = JsonUtil.mapper().copy();
        copy.setAnnotationIntrospector(new SnapshotIgnoreIntrospector());
        return copy;
    }

    @Override
    public ContextSnapshot capture(AgentContext context) {
        if (context == null) {
            throw new CheckpointException("无法捕获快照：context 为 null");
        }
        String json;
        try {
            json = mapper.writeValueAsString(context);
        } catch (Exception e) {
            throw new CheckpointException(
                    "上下文快照序列化失败（类型: " + context.getClass().getName()
                            + "）。若该上下文含有不可 JSON 序列化的字段，"
                            + "请实现自定义 ContextSnapshotter 并声明为 Bean。", e);
        }
        if (json == null || json.isBlank()) {
            // 注意：不把 "{}" 视为非法——所有字段为 null 是合法状态（例如刚创建的空上下文），
            // 只有 null/空串才说明序列化确实产出了空载荷
            throw new CheckpointException(
                    "上下文快照为空（类型: " + context.getClass().getName()
                            + "），拒绝写入非法快照");
        }
        return new ContextSnapshot(schemaVersion(), context.getClass().getName(), json);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <C extends AgentContext> C restore(ContextSnapshot snapshot) {
        if (snapshot == null) {
            throw new CheckpointException("无法还原快照：snapshot 为 null");
        }
        if (snapshot.schemaVersion() != schemaVersion()) {
            throw new CheckpointException(
                    "快照结构版本不匹配：快照为 v" + snapshot.schemaVersion()
                            + "，当前快照器为 v" + schemaVersion()
                            + "。请提供 SchemaMigrator 或自定义 ContextSnapshotter 处理旧快照，"
                            + "框架不做自动迁移。");
        }

        Class<?> type = resolveType(snapshot.contextType());
        if (!AgentContext.class.isAssignableFrom(type)) {
            throw new CheckpointException(
                    "快照中的 contextType 不是 AgentContext 实现: " + snapshot.contextType());
        }

        Object restored;
        try {
            restored = mapper.readValue(snapshot.payload(), type);
        } catch (Exception e) {
            throw new CheckpointException(
                    "上下文快照反序列化失败（类型: " + snapshot.contextType()
                            + "）。该类型需满足 Jackson 还原要求："
                            + "字段为 final 时须显式声明 @JsonCreator（参见 Message 的写法）。", e);
        }
        if (restored == null) {
            throw new CheckpointException(
                    "上下文快照反序列化结果为 null（类型: " + snapshot.contextType() + "）");
        }
        return (C) restored;
    }

    /**
     * 按全限定名加载上下文实现类。<br>
     * 优先用线程上下文类加载器（框架类与用户类可能处于不同 ClassLoader）。
     */
    private Class<?> resolveType(String className) {
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        try {
            return Class.forName(className, true, tccl != null ? tccl : getClass().getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new CheckpointException(
                    "快照中的上下文类型不存在: " + className
                            + "（可能是应用升级后类被重命名或移除）", e);
        }
    }

    /**
     * 让 Jackson 识别 {@link SnapshotIgnore}：标记的成员在序列化与反序列化时都被跳过。<br>
     * 挂在 {@link com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector} 上
     * 而不是自建一套 introspector，是为了保留 Jackson 自身的注解语义。
     */
    static final class SnapshotIgnoreIntrospector
            extends com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector {

        @Override
        public boolean hasIgnoreMarker(com.fasterxml.jackson.databind.introspect.AnnotatedMember member) {
            return member.getAnnotation(SnapshotIgnore.class) != null || super.hasIgnoreMarker(member);
        }
    }
}
