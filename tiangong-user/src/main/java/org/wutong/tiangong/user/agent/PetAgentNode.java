package org.wutong.tiangong.user.agent;

import lombok.extern.slf4j.Slf4j;
import org.wutong.tiangong.annotations.annotation.framework.Component;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.AgentNode;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.NodeNext;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.entrance.ApiEntranceNode;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.entrance.ApiExportNode;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.entrance.StdinEntranceNode;
import org.wutong.tiangong.annotations.annotation.framework.inject.Inject;
import org.wutong.tiangong.common.enums.InjectSource;
import org.wutong.tiangong.common.enums.RequestMethod;
import org.wutong.tiangong.common.enums.RequestType;
import org.wutong.tiangong.core.function.AgentFunctionDefinition;
import org.wutong.tiangong.core.function.FunctionRegistry;
import org.wutong.tiangong.core.llm.ChatRequestBuilder;
import org.wutong.tiangong.llm.client.OpenaiLlmClient;
import org.wutong.tiangong.llm.message.AssistantMessage;
import org.wutong.tiangong.llm.message.HumanMessage;
import org.wutong.tiangong.user.context.PetContext;
import org.wutong.tiangong.user.model.PetChatRequest;
import org.wutong.tiangong.user.model.PetReply;
import org.wutong.tiangong.user.tool.PetToolNames;
import org.wutong.tiangong.user.tool.SystemToolNames;
import org.wutong.tiangong.user.util.ConsoleColor;

import java.util.Arrays;
import java.util.stream.Stream;

/**
 * 桌宠的节点图：<b>两条 START 链，共用中段</b>。<br>
 * <pre>
 *   stdin_start → listen(STDIN 入口) ┐
 *                                    ├→ recall → think → route_out ─┬→ speak   → stdin_end
 *   api_start   → serve(API 入口)   ┘                               └→ reply   → api_end
 * </pre>
 *
 * <h2>为什么要两条链而不是一条链加 if</h2>
 * 两条通道的<b>生命期不同</b>：STDIN 是 {@code SESSION_LOOP}（一轮跑到 END 再回到入口等下一句），
 * API 是 {@code FORK_PER_INPUT}（每次请求 fork 一个副本跑完就丢）。把它们塞进同一条链，
 * 就要在节点里反复判断「我现在是在哪种模式下」—— 那正是框架该替应用承担的分岔。<br>
 * 中段（recall / think）是<b>纯对话逻辑</b>，与通道无关，因此共用；
 * 只有最后一跳不同：STDIN 打到控制台，API 变成 HTTP 响应。
 *
 * <h2>工具循环去哪了</h2>
 * 旧版这里有一条 {@code think → decide → call_tool → think} 的手动工具环（节点里登记调用、
 * 分支判断、再回传工具结果）。现在工具执行由框架在 {@code think} 的<b>流内自动完成</b>：
 * 模型请求工具 → 按 {@code get_sys_time} 这类名字查注册表执行 → 结果回放续轮，
 * 模型不再调工具时流才结束。节点图里因此不再需要工具骨架。
 *
 * <h2>共用的代价：通道要记在上下文里</h2>
 * {@code route_out} 按 {@code context.getChannel()} 分流 —— 两条链共用中段，
 * 就只能靠上下文里这个字段区分「我从哪儿来」。这是共用换来的唯一代价，可以接受。
 */
@Slf4j
@Component
public class PetAgentNode {

    /**
     * 想给模型的工具。<br>
     * <b>刻意不用 {@code ToolParam.ALL}</b>：桌宠用不上 Bash、读文件这些通用工具，
     * 全量塞进去只会让模型在无关工具上分心（工具越多，选错的概率越高），
     * 也白烧 token。这里是「按需披露」—— 与技能正文的渐进式披露同一思路。
     */
    private static final String[] WANTED_TOOLS = {
            SystemToolNames.GET_SYSTEM_TIME,
            SystemToolNames.NOTICE,
            SystemToolNames.READ,
            PetToolNames.REMEMBER_FACT,
            PetToolNames.FORGET_FACT,
            PetToolNames.RECALL_FACTS,
            PetToolNames.SET_MOOD,
            // 来自 tiangong-skill 的 SkillTool：它在 @EnableSkill 打开时才注册，
            // 而且那个模块刻意不参与编译期生成（ShellTool 同理，出于安全边界手动注册）
            "activate_skill"
    };

    /**
     * 客户端名来自 agent.yml 的 {@code agent.models[].name}，改模型不用动代码。<br>
     * ⚠ 字段类型必须是<b>具体 provider 类</b>（{@code OpenaiLlmClient}），不能写
     * {@code LlmClient<?,?>}：注入是反射赋值，类型不符会抛 IllegalArgumentException。
     * 代价是换 provider 要改这一行 —— 这是框架目前的设计缺口。
     * （流内工具循环由框架负责，节点不再需要 tool call 的具体类型，但注入约束仍在。）
     */
    @Inject(value = "pet-llm", source = InjectSource.LLM)
    private OpenaiLlmClient client;

    @Inject(value = "pet-llm", source = InjectSource.REQUEST, request = RequestType.CHAT)
    private ChatRequestBuilder builder;

    /**
     * 函数注册表：用来过滤掉<b>当前没注册</b>的工具名。<br>
     * {@code ChatRequestBuilder} 对未注册的名字是<b>直接抛异常</b>的（不是跳过），
     * 而 {@code activate_skill} 只在 {@code @EnableSkill} 打开时才存在 ——
     * 硬编码名单会让关掉技能开关时整条链起不来。这里先查后传。
     */
    @Inject
    private FunctionRegistry functions;

    // ==================== STDIN 链 ====================

    @AgentNode(value = "stdin_start", type = NodeType.START)
    @NodeNext("listen")
    public PetContext stdinStart(PetContext context) {
        context.setChannel(PetContext.CHANNEL_STDIN);
        context.setNowNode("stdin_start");
        log.info("桌宠已就位（STDIN 通道），等待输入");
        return context;
    }

    /**
     * STDIN 入口：阻塞等一行输入。<br>
     * 用字段注入而非形参注入依赖 —— ENTRANCE 节点的第二个形参要用来接输入。
     */
    @StdinEntranceNode(value = "listen", inputShow = "你> ")
    @NodeNext("recall")
    public PetContext listen(PetContext context, String input) {
        context.setInput(input);
        context.append(new HumanMessage(input));
        context.setNowNode("listen");
        return context;
    }

    // ==================== API 链 ====================

    @AgentNode(value = "api_start", type = NodeType.START)
    @NodeNext("serve")
    public PetContext apiStart(PetContext context) {
        context.setChannel(PetContext.CHANNEL_API);
        context.setNowNode("api_start");
        log.info("桌宠已就位（API 通道），等待 HTTP 请求");
        return context;
    }

    /**
     * API 入口：注册路由 {@code /{workflowId}/chat}，阻塞等请求。<br>
     * 第二个形参由 JSON body 反序列化；body 为空时框架传 null，所以要判空。
     */
    @ApiEntranceNode(value = "serve", route = "/chat", method = RequestMethod.POST)
    @NodeNext("recall")
    public PetContext serve(PetContext context, PetChatRequest request) {
        String text = request == null || request.getMessage() == null ? "" : request.getMessage();
        context.setInput(text);
        context.append(new HumanMessage(text));
        context.setNowNode("serve");
        return context;
    }

    // ==================== 共用中段 ====================

    /**
     * 刷新「当前状态 + 记得的事」再进 think。<br>
     * 每轮都刷：状态会随交互演化，只在 init 时装一次的话，常驻会话里模型永远看到启动时的数据。
     */
    @AgentNode("recall")
    @NodeNext("think")
    public PetContext recall(PetContext context) {
        context.refreshState();
        context.setNowNode("recall");
        return context;
    }

    /**
     * 说话：调 LLM。<br>
     * STDIN 通道边收边打印（有打字机感）；API 通道不打印 —— 那会污染服务端 stdout。<br>
     * <br>
     * <b>工具循环由框架在流内完成</b>：请求里下发的工具若被模型调用，框架自动按名字
     * 执行注册表里的函数、把结果回放给模型续轮 —— 本方法只管把 onData 的正文拼起来，
     * 拿到的已经是「模型不再调工具」的完整答复；工具执行日志由框架打（{@code 工具调用 [名] 参数 -> 结果}）。
     */
    @AgentNode("think")
    @NodeNext("route_out")
    public PetContext think(PetContext context) {
        boolean console = PetContext.CHANNEL_STDIN.equals(context.getChannel());
        StringBuilder text = new StringBuilder();

        var response = client.stream(
                builder.build(context.getContext(), true, availableTools())
        );
        response
                .onReasoning(reason -> {
                    if (console) {
                        System.out.print(ConsoleColor.loadColor(ConsoleColor.YELLOW, reason));
                    }
                })
                // ⚠ 必须判空：onData 收到的是<b>原始 chunk</b>，末尾那个「choices 为空、
                // 只带 usage」的块 content 是 null。不判的话控制台会多打一个 "null"，
                // 而且 StringBuilder.append(null) 会把字面量 "null" 拼进回复正文 ——
                // 实测回复结尾变成「～null」。框架内部的 accumulate() 判了空，
                // 回调层没判，这个坑得调用方自己兜。
                .onData(data -> {
                    String chunk = data.getContent();
                    if (chunk == null) {
                        return;
                    }
                    if (console) {
                        System.out.print(ConsoleColor.loadColor(ConsoleColor.BLUE, chunk));
                    }
                    text.append(chunk);
                })
                .onComplete(() -> {
                    if (console) {
                        System.out.println();
                    }
                    String content = text.toString();
                    context.setReply(content);
                    // 空回复不进上下文：纯工具调用轮次里助手没有说话，
                    // 塞一条空消息进去只会占窗口、还可能让下一轮模型跟着输出空
                    if (!content.isBlank()) {
                        context.append(new AssistantMessage(content));
                    }
                })
                // 流失败不能是静默的：没有 onError 时异常只会被塞进 future，
                // 表现是「模型一句话没说」，查不出是网络、模型还是参数的问题
                .onError(error -> log.error("LLM 流失败: {}", error.getMessage(), error))
                .start()
                // ⚠ 必须等流结束：start() 只是把读取丢进虚拟线程就返回了。
                // 不等的话节点会在模型还在流式输出时就往下走 —— reply 是空的、
                // 工具轮次也还没跑完，而且**没有任何报错**。STDIN 下表现为
                //「下一句提示符已经打出来了，上一句的回复才慢慢冒出来」。
                .await();
        context.setNowNode("think");
        return context;
    }

    /** BRANCH：按通道决定「说完的话」往哪儿送 */
    @AgentNode(value = "route_out", type = NodeType.BRANCH)
    @NodeNext({"speak", "reply"})
    public String routeOut(PetContext context) {
        return PetContext.CHANNEL_API.equals(context.getChannel()) ? "reply" : "speak";
    }

    // ==================== 两条链的尾巴 ====================

    /** STDIN：把状态打在下面，让人看得见它在演化 */
    @AgentNode("speak")
    @NodeNext("stdin_end")
    public PetContext speak(PetContext context) {
        String state = context.stateText();
        if (state != null && !state.isBlank()) {
            System.out.println(ConsoleColor.loadColor(ConsoleColor.YELLOW,
                    state.lines().findFirst().orElse("")));
        }
        context.setNowNode("speak");
        return context;
    }

    /** API：把回复与状态一起作为 HTTP 响应回传 */
    @ApiExportNode(value = "reply", status = 200)
    @NodeNext("api_end")
    public PetReply reply(PetContext context) {
        var state = context.getMemory() == null ? null : context.getMemory().state();
        return new PetReply(
                context.getReply() == null ? "" : context.getReply(),
                state == null ? "" : state.getMood().label(),
                state == null ? 0 : state.getEnergy(),
                state == null ? 0 : state.getAffinity(),
                state == null ? 0 : state.getTotalTurns(),
                context.getSessionId()
        );
    }

    @AgentNode(value = "stdin_end", type = NodeType.END)
    public PetContext stdinEnd(PetContext context) {
        context.setNowNode("stdin_end");
        return context;
    }

    @AgentNode(value = "api_end", type = NodeType.END)
    public PetContext apiEnd(PetContext context) {
        log.info("API 一轮完成: session={}", context.getSessionId());
        context.setNowNode("api_end");
        return context;
    }

    /** 实际可用的工具名：固定名单 + 注册表里查得到的 MCP 工具 */
    private String[] availableTools() {
        if (functions == null) {
            return WANTED_TOOLS;
        }
        return Stream.concat(
                Arrays.stream(WANTED_TOOLS).filter(name -> functions.get(name).isPresent()),
                mcpToolNames()
        ).toArray(String[]::new);
    }

    /**
     * 注册表里的 MCP 工具（取<b>全名</b> {@code serverName.toolName}，避开短名撞车）。<br>
     * 判据：MCP 工具没有本地 {@code Method} —— 它由 {@code McpToolInvoker} 包装，
     * 既不指向 Bean 实例也不指向反射方法。<br>
     * ⚠ 下发给模型的名字是<b>短名</b>（{@code ToolSchemaConverter} 取的是 {@code getName()}），
     * 所以两个 server 若有同名工具，{@code FunctionRegistry} 会打「短名冲突」warn 并保留首个 ——
     * 真撞了认那条日志。
     */
    private Stream<String> mcpToolNames() {
        return functions.values().stream()
                .filter(def -> def.getMethod() == null && def.getInvoker() != null)
                .map(AgentFunctionDefinition::fullName);
    }
}
