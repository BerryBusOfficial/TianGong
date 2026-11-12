package org.wutong.tiangong.user.context;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import com.taixu.tiangong.annotations.annotation.definition.Lifecycle;
import com.taixu.tiangong.annotations.annotation.framework.Component;
import com.taixu.tiangong.annotations.annotation.framework.Scope;
import com.taixu.tiangong.annotations.annotation.framework.inject.Inject;
import com.taixu.tiangong.annotations.annotation.framework.inject.Value;
import com.taixu.tiangong.annotations.annotation.framework.trace.Trace;
import com.taixu.tiangong.common.agent.checkpoint.SnapshotIgnore;
import com.taixu.tiangong.common.agent.context.AgentContext;
import com.taixu.tiangong.common.enums.InjectSource;
import com.taixu.tiangong.common.enums.Namespace;
import com.taixu.tiangong.common.enums.ResourceType;
import com.taixu.tiangong.common.function.Arguments;
import com.taixu.tiangong.common.util.IdGenerator;
import com.taixu.tiangong.core.container.context.ContextHolder;
import com.taixu.tiangong.core.skill.SkillRegistry;
import com.taixu.tiangong.llm.message.Message;
import com.taixu.tiangong.llm.message.SystemMessage;
import org.wutong.tiangong.user.history.PetHistory;
import org.wutong.tiangong.user.memory.PetMemory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 桌宠的会话上下文。<br>
 * <br>
 * <b>必须标 {@code @Scope(PROTOTYPE)}</b>：{@code WorkflowScheduler.doStart} 用
 * {@code container.getBean(contextType)} 取得上下文。本应用有 STDIN 与 API 两条工作流
 * 同时常驻，单例会让两条链共用同一个实例 —— 对话串在一起，无法区分谁在跟谁说话。<br>
 * 原型作用域下每条链拿到独立实例，且<b>仍走完整注入流程</b>。<br>
 * <br>
 *
 * <h2>一条工作流 = 一段会话</h2>
 * 每个实例自带 {@link #sessionId}；历史据此区分发言来自哪条链。<br>
 * <br>
 *
 * <h2>为什么重写 {@code copy()}</h2>
 * API 入口每次请求都要 {@code context.copy()} fork 出独立副本。默认实现走
 * {@code JsonUtil} 序列化，而 {@code JsonUtil} 的共享 mapper <b>不认
 * {@code @SnapshotIgnore}</b>（那是 {@code JsonContextSnapshotter} 自己那份 mapper
 * 才装了识别器），于是它会去序列化 {@link #memory} 这类容器单例 —— 这些类没有 getter，
 * Jackson 直接抛「无可用属性」。<br>
 * 这里显式拷贝：只带状态，协作对象（记忆/历史/技能表）<b>仍指向同一个单例</b>，
 * 这正是我们要的 —— 它们是共享资源，不该被 fork 出副本。
 */
@Slf4j
@Getter
@Setter
@Component
@Scope(Lifecycle.PROTOTYPE)
public class PetContext implements AgentContext {

    /** STDIN 通道：说话要打到控制台 */
    public static final String CHANNEL_STDIN = "stdin";
    /** API 通道：说话要变成 HTTP 响应 */
    public static final String CHANNEL_API = "api";

    /**
     * 系统提示词的槽位，永远不被窗口淘汰。<br>
     * 窗口满了就从它后面开始删 —— 丢掉系统提示词等于让桌宠失忆人格，代价远大于丢一轮闲聊。
     */
    public static final int PROMPT_SLOT = 0;

    /** 每轮刷新的状态消息的名字标记，用于定位并替换上一轮的旧状态 */
    public static final String STATE_MESSAGE_NAME = "PetState";

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");

    /** 一条工作流 = 一段会话 */
    private final String sessionId = IdGenerator.generate(Namespace.AGENT, ResourceType.SESSION);

    @Getter
    private final List<Message> context = new CopyOnWriteArrayList<>();

    @Trace("node")
    private String nowNode;
    @Trace("node")
    private String lastestMessage;

    @Value(value = "context.windows", defaultValue = "24")
    private int windows;

    @Inject(
            value = "AGENT.md",
            source = InjectSource.RESOURCE
    )
    private String systemInput;

    /**
     * 记忆：容器单例，<b>不属于会话状态</b>（检查点契约 D1）。<br>
     * 快照还原出的对象没经过容器注入，这里是 null；由 {@link #afterRestore()} 取回。
     */
    @SnapshotIgnore
    @Inject
    private PetMemory memory;

    /** 历史：同 {@link #memory}，容器单例、不进快照 */
    @SnapshotIgnore
    @Inject
    private PetHistory history;

    /**
     * 技能注册表：把「可用技能目录」拼进系统提示词。<br>
     * 允许为 null（手工构造的上下文不经过容器）：此时只是没有技能可用，不阻断启动。<br>
     * 同样必须标 {@code @SnapshotIgnore}（容器单例 + 无可序列化属性）。
     */
    @SnapshotIgnore
    @Inject
    private SkillRegistry skillRegistry;

    /** 本条会话已展开过的技能 id：正文只在首次命中时注入一次 */
    @SnapshotIgnore
    private final Set<String> activatedSkills = new HashSet<>();

    /** 本轮输入来自哪个通道：{@link #CHANNEL_STDIN} / {@link #CHANNEL_API} */
    private String channel = CHANNEL_STDIN;

    /** 本轮用户输入 */
    private String input;
    /** 本轮桌宠的回复（最后一次 think 的产物） */
    private String reply;

    private String nowTool;
    private final Map<String, String> functionCallMap = new HashMap<>();
    private final Map<String, Arguments> functionArgsMap = new HashMap<>();

    /** 用普通 boolean 而非 AtomicBoolean：{@link #copy()} 走的是显式拷贝，用不上原子操作 */
    private boolean toolCall = false;

    /** 工作流实例 ID，由 WorkflowScheduler 启动时注入 */
    private volatile String workflowId;
    /** 协作式中断标志 */
    private volatile boolean cancelled;

    private final AtomicBoolean initiated = new AtomicBoolean(false);

    // ==================== AgentContext 契约 ====================

    @Override
    public String workflowId() {
        return workflowId;
    }

    @Override
    public void bindWorkflow(String workflowId) {
        this.workflowId = workflowId;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void cancel() {
        this.cancelled = true;
    }

    /**
     * 从检查点还原后的重新装配（检查点契约 D1）。<br>
     * 三个 {@code @SnapshotIgnore} 成员在还原出的对象里都是 null，这里从容器取回。
     */
    @Override
    public void afterRestore() {
        var app = ContextHolder.getApplication();
        if (app == null || app.getContainer() == null) {
            log.warn("还原后无法重新装配协作对象：应用上下文不可用（单元测试场景可忽略）");
            return;
        }
        PetMemory mem = app.getContainer().getBean(PetMemory.class);
        if (mem != null) {
            setMemory(mem);
        }
        PetHistory his = app.getContainer().getBean(PetHistory.class);
        if (his != null) {
            setHistory(his);
        }
        SkillRegistry skills = app.getContainer().getBean(SkillRegistry.class);
        if (skills != null) {
            setSkillRegistry(skills);
        }
    }

    /**
     * 装载系统提示词，并在其后附上技能目录。<br>
     * <b>只装载人格与技能，不装状态</b> —— 状态每轮都会变，由 {@link #refreshState()}
     * 单独刷新（见该方法的说明）。
     */
    @Override
    public void init() {
        if (initiated.get()) {
            return;
        }
        String prompt = systemInput;
        if (skillRegistry != null && !skillRegistry.catalog().isEmpty()) {
            prompt = systemInput + "\n\n" + skillRegistry.catalog()
                    + "\n需要某个技能的完整步骤时，调用 activate_skill 工具展开它，"
                    + "不要凭目录里的那句话猜步骤。";
        }
        context.add(new SystemMessage(prompt, "Personality Setting"));
        log.debug("上下文已装载系统提示词（技能目录 {} 字）",
                skillRegistry == null ? "未装配" : skillRegistry.catalog().length());
        initiated.set(true);
    }

    /**
     * 每轮结束的善后（框架在每个 END 节点后调用）。<br>
     * <br>
     * 三件事，顺序有讲究：<br>
     * <ol>
     *     <li><b>清本轮的工具残留</b>：nowTool / 调用表不清，下一轮若没触发工具调用，
     *         {@code decide} 会因为读到上一轮的残留而误判</li>
     *     <li><b>落盘历史</b>：一轮一次，不逐条写</li>
     *     <li><b>记忆演化</b>：扣精力、涨好感、心情随精力联动</li>
     * </ol>
     * 刻意<b>不清空 {@link #context}</b>：会话型工作流每轮都走到这里，清空就等于每轮失忆。
     */
    @Override
    public void afterEnd() {
        clearToolTrace();
        if (history != null) {
            history.save();
        }
        if (memory != null) {
            memory.afterTurn(LocalDateTime.now());
        }
        log.debug("本轮结束：session={}, channel={}", sessionId, channel);
    }

    /**
     * 整条工作流终止时释放。<br>
     * 由框架在 {@code AgentLoopExecutor.execute} 的 finally 里调用，
     * 覆盖正常结束 / 会话退出 / 取消 / 抛异常四条路径。<br>
     * <br>
     * {@code initiated} 与 {@code cancelled} <b>必须重置</b>：前者不重置则下次 init 被守卫挡掉，
     * 后者不重置则新工作流一启动就被判定为已取消。<br>
     * <br>
     * 不动 {@link #memory} / {@link #history} / {@link #skillRegistry}：容器单例，
     * 清它们会抹掉别的会话的数据。
     */
    @Override
    public void destroy() {
        context.clear();
        activatedSkills.clear();
        clearToolTrace();
        setNowNode(null);
        setLastestMessage(null);
        setInput(null);
        setReply(null);
        if (memory != null) {
            memory.save();
        }
        if (history != null) {
            history.save();
        }
        initiated.set(false);
        cancelled = false;
        workflowId = null;
        log.debug("上下文已释放，下次工作流启动时重新初始化");
    }

    /**
     * fork 出本轮请求的独立副本（API 入口每次 HTTP 请求调一次）。<br>
     * 见类注释里「为什么重写 copy」。<br>
     * <br>
     * {@code initiated} 置为 true：副本<b>沿用已装载的系统提示词</b>，不能再走一次 init；
     * 但 {@code workflowId} 按契约留空（由调用方 {@code bindWorkflow} 重新绑定）。
     */
    @Override
    public AgentContext copy() {
        PetContext copy = new PetContext();
        copy.context.addAll(this.context);
        copy.windows = this.windows;
        copy.systemInput = this.systemInput;
        copy.memory = this.memory;
        copy.history = this.history;
        copy.skillRegistry = this.skillRegistry;
        copy.channel = this.channel;
        copy.initiated.set(true);
        return copy;
    }

    // ==================== 会话读写 ====================

    /**
     * 追加一条消息：进窗口、进历史。<br>
     * <br>
     * ⚠ <b>{@code content} 必须原样透传，别往里塞 JSON。</b>
     * 以前为了让模型「感知时间」，这里会把每条消息包成
     * {@code {"content":...,"dateTime":...}}。副作用是模型看到的历史消息全是 JSON，
     * 从第二轮起开始<b>模仿这个格式回话</b>（实测：助手回复变成
     * {@code {"dateTime":"...","content":"*点点头*..."}}，第三轮还嵌套了一层）。
     * 而且 {@code AGENT.md} 只承诺包装<b>用户消息</b>，这里却对所有角色一视同仁，
     * 连助手自己的历史回复也被包了 —— 实现早已和文档不符。<br>
     * 时间感改由 {@link #stateText()} 在系统状态消息里给：系统消息的权威性强于
     * 模仿历史格式，还不会污染每一条消息。<br>
     * <br>
     * <b>窗口淘汰跳过 {@link #PROMPT_SLOT}</b>：系统提示词被挤掉等于人格丢失，
     * 那比少看一轮历史严重得多。
     */
    public Message append(Message message) {
        while (context.size() >= windows && context.size() > PROMPT_SLOT + 1) {
            context.remove(PROMPT_SLOT + 1);
        }
        context.add(message);
        if (history != null) {
            history.add(sessionId, message);
        }
        setLastestMessage(message.getContent());
        return message;
    }

    /**
     * 刷新「当前状态 + 记得的事」这条系统消息。<br>
     * <br>
     * <h2>为什么不塞进系统提示词就完事</h2>
     * 状态每轮都会变（精力下降、好感度上升、心情联动）。若只在 {@link #init()} 里装一次，
     * STDIN 这种常驻会话里模型看到的永远是<b>启动那一刻</b>的状态 —— 桌宠会一直以为自己
     * 精力充沛，演化规则等于没生效。<br>
     * 因此每轮用 {@link #STATE_MESSAGE_NAME} 定位上一轮的状态消息、删掉、再追加最新的。
     * 提示词里因此永远只有一份状态，不会越聊越长。
     */
    public void refreshState() {
        String text = stateText();
        context.removeIf(m -> STATE_MESSAGE_NAME.equals(m.getName()));
        context.add(new SystemMessage(text, STATE_MESSAGE_NAME));
    }

    /**
     * 状态与记忆的提示词文本；未装配记忆时退化为空串（不阻断，只是桌宠不知道自己是谁）。<br>
     * <br>
     * 末尾附一行<b>当前时间</b>：模型需要时间感（「今天几号」「刚才那顿饭」），
     * 而给时间的正确位置是系统消息 —— 塞进每条消息的 content 会污染历史
     * （代价见 {@link #append}）。
     */
    public String stateText() {
        if (memory == null) {
            return "";
        }
        String facts = memory.recallText();
        String state = memory.stateText();
        String body = facts.isEmpty() ? state : state + "\n" + facts;
        return body + "\n当前时间：" + LocalDateTime.now().format(STAMP);
    }

    /** 清掉本轮的工具调用残留 */
    public void clearToolTrace() {
        functionCallMap.clear();
        functionArgsMap.clear();
        toolCall = false;
        setNowTool(null);
    }

    /** 记下模型请求的工具调用；由 think 节点在流式回调里调用 */
    public void markToolCall(String toolName, String callId, Arguments arguments) {
        this.nowTool = toolName;
        if (callId != null) {
            functionCallMap.put(toolName, callId);
        }
        if (arguments != null) {
            functionArgsMap.put(toolName, arguments);
        }
        this.toolCall = true;
    }
}
