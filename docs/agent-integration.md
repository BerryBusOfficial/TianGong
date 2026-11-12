# 天工集成侧契约（第一轮）

> 对照：`ReportHub` 的集成侧反馈（2026-11-12）。本轮先做图片里的三个 P0：
> **调用者上下文 + 工具治理**、**类型化流式事件 + SDK**、**结构化输出契约**。
> P1（检索/路由/可观测）与 P2（护栏/多租户/人机协同）留到下一轮，本文末尾列出边界。

## 1. 调用者上下文（AgentCallContext）

`AgentContext` 是**工作流状态**（历史、临时变量、取消标志），会被检查点序列化；
`AgentCallContext` 是**调用者身份**（userId / tenantId / scopes / traceId），属于请求边界，
**不写入检查点**。两者不能混用：把身份写进快照，恢复时会把上一个用户的权限带进新请求。

### HTTP 入口

网关/认证层校验身份后写入以下 header（框架不做认证，直接信任 header）：

```http
X-User-Id: u-123
X-Tenant-Id: tenant-a
X-Scopes: report:draft, report:read
X-Trace-Id: 4bf92f3577b34da6a3ce929d0e0e4736
```

也接受 W3C `traceparent`，取其中的 trace-id。`ApiHttpServer` 构造 `EntranceRequest`
时解析成 `AgentCallContext`，执行器在 fork 线程里 bind，请求结束 restore。

进程内服务调用沿用调用线程的 `AgentCallContext.current()`。

### 工具声明

```java
@AgentTool(scopes = {"report:read"})          // 类级默认权限
public class ReportTools {

    @AgentFunction(name = "draft_report", scopes = {"report:draft"},
                   requiresApproval = true)    // 需要审批
    public ReportDraft draft(@ToolParam(name = "text") String text,
                             AgentCallContext context) {   // 框架注入，不进 schema
        return ...;
    }
}
```

* scope 支持 `*` 与 `report:*` 通配；类级与方法级取并集，方法级不能放宽类级。
* 工具声明了 scope 而当前请求没有 `AgentCallContext` → **拒绝**（不是放行）。
* 没有声明 scope 的旧工具保持原行为（向后兼容）。
* 下发给模型的工具清单与执行期用同一条判据（`ToolAccessPolicy`）：模型看不到无权工具，
  也不会出现「看得到、执行被拒」的两套规则。

### 部署策略 / 审批 / 审计

```java
ToolGovernance.configure(
    ToolScope.allow("report:*").deny("admin:*"),      // 进程级工具白/黑名单
    request -> ToolApprovalDecision.APPROVED,        // 审批处理器；没有则声明审批的工具一律拒绝
    event -> auditRepository.save(event));           // 审计旁路，写失败不影响工具结果
```

审批处理器异常按**拒绝**处理，避免审批链路故障变成提权通道。审计先落痕再抛拒绝。

## 2. 类型化流式事件 + SDK

### 事件

`message.delta` / `reasoning.delta` / `tool.call` / `tool.result` / `citation` /
`artifact` / `usage` / `structured` / `error` / `done`。

每条事件带 `seq`（运行内从 1 单调递增）、`runId`、`turnId`、`timestamp`。
`done` 是唯一正常终止标记；`error` 之后仍有 `done=failed`，客户端不必靠连接关闭猜结束。

### 服务端

```java
AgentEventJournal journal = new AgentEventJournal(runId, turnId);
AgentEventScope.Binding binding = new AgentEventScope.Binding(journal, runId, turnId);

// 运行 Agent 的线程里显式绑定：流式读取在虚拟线程中执行，ThreadLocal 不会自动继承，
// 框架已在 ToolLoopSupport 里 capture + 搬运；应用侧自建线程同理。
AgentEventScope.runWith(binding, () -> agentRuntime.startWorkflow(...));

// 挂到任意 HTTP 端口（协议层不认识 @ApiEntranceNode）
try (AgentEventSseServer server = new AgentEventSseServer(8080,
        request -> new AgentEventSseServer.Session(journal))) {
    server.start();
}
```

支持 `Last-Event-ID`：客户端重连带最后收到的 seq，服务端回放 `seq > last` 的事件，
再挂实时订阅。客户端断开时调用 `Session.cancel`（或 `journal.cancel()`）取消上游。

### 客户端 SDK

```java
AgentEventClient client = new AgentEventClient(URI.create("http://host:8080/events"));
try (Connection connection = client.connect(lastSeq == 0 ? null : Long.toString(lastSeq))) {
    AgentEventEnvelope event;
    while ((event = connection.next()) != null) {
        switch (event.event()) {
            case AgentEvent.MessageDelta d -> ui.append(d.delta());
            case AgentEvent.ToolCall c -> ui.showToolCall(c.name());
            case AgentEvent.Structured s -> ui.renderCard(s.data());
            case AgentEvent.Done d -> { return; }
            default -> { }
        }
    }
}
```

`AgentEventClient.subscribe(onEvent, onError)` 会在断线后带 `Last-Event-ID` 自动重连。
内存日志不跨进程/重启；跨进程续传需要持久化日志（下一轮 P1 的 Trace 扩展）。

## 3. 结构化输出契约

```java
public record ReportDraft(String title, List<String> points,
                          Priority priority, Optional<String> note) {}

StructuredOutput spec = StructuredOutput.of(ReportDraft.class);
ChatRequest request = builder.build(messages, spec);          // 自动带 response_format
ReportDraft draft = StructuredOutputRunner.run(client, request, spec);
```

* `JsonSchemaGenerator` 从 record/POJO 生成 schema（必填、枚举、数组元素、嵌套对象、
  `Optional` 非必填）；不支持的形态降级为宽松 schema，不假装精确。
* OpenAI 下发 `response_format=json_schema`；Deepseek 下发 `json_object`，schema 由
  `StructuredOutputRunner` 注入提示词，框架侧再校验；Ollama 同理走提示词 + 校验。
* 解析时先做平衡括号提取（容忍 ```json 围栏/前后解释），再严格校验 schema；
  失败带具体路径（`$.points[0]`）和修复提示重试，默认最多 2 轮。
* 通过校验后发 `structured` 事件；正文不再泄漏哨兵标记，前端不必从散文里抠 JSON。
* `StreamingJsonExtractor` 提供流式提取（边收 delta 边判断完整 JSON），供 UI 提前渲染。

## 4. P1：检索/记忆 SPI + citation/ACL

```java
RetrieverRegistry registry = new RetrieverRegistry();
registry.register(new VectorRetriever(...));          // 只负责召回
RetrievalService retrieval = new RetrievalService(registry);

List<RetrievedDocument> docs = retrieval.retrieve("report-chunks",
        new RetrievalQuery("本周谁没交", 5, Map.of()));
String context = RetrievalService.toPromptBlock(docs);
```

* `Retriever` 只做召回（向量库/全文/图扩散/记忆事实都可以）；权限判断不写进实现。
* `DocumentAcl.scopeBased()` 在结果进入提示词前统一裁剪：
  `requiredScopes` 全部命中 + `metadata.tenantId` 与调用者租户一致。
  私有文档必须由 retriever 如实标注，框架不能猜密级。
* `CitationCollector` 按 `docId` 去重并发 `citation` 事件；同一篇文档被多个 retriever
  命中时前端只收到一次。
* `retrieve(List.of("vector", "graph"), query)` 支持多检索器合并：按 docId 去重并保留
  更高 score，适合「向量召回 + 图扩散 + 记忆事实」一次进上下文。
* `toPromptBlock` 输出带 `[1]` 编号、docId、来源 URL 与摘要的上下文块；引用编号稳定，
  模型可以在正文里写 `[1]`。

## 5. P1：模型路由 + 成本

```java
ModelRouter router = StaticModelRouter.builder()
        .route(ModelRoute.of("report", "deepseek-flash", "deepseek-pro", "local-ollama"))
        .defaultRoute("deepseek-pro")
        .build();

ModelRouteExecutor executor = new ModelRouteExecutor(router, clients, pricing, ledger,
        (name, messages, route) -> registry.buildChatRequest(name, messages, false, tools),
        1);                                        // 空回答同候选重试 1 次

RoutedResult result = executor.chat("report", messages);
```

* `ModelRoute` 是任务语义（report/chat/classify），候选链可替换；执行器负责按链降级、
  空回答重试、保留完整尝试链（为什么走了 pro）。
* `ChatRequest.maxReasoningTokens` 由 `ReasoningBudgetChatStream` 执行：统计 reasoning
  delta，超过预算立即取消上游并以 `ReasoningBudgetExceededException` 结束。token 用字符数
  近似（中文约 1 字≈1 token，英文偏高），不内置厂商 tokenizer；精确计费仍看 provider usage。
* `CostLedger` 记录 run/node/user/tenant/model/token/成本；`PricingTable` 用
  「微美元/百万 token」避免浮点累加误差。
* `TenantBudgetGuard` 在调用前做**原子预留**：`spent + 已预留 + 本次估算 ≤ limit` 才放行，
  调用结束释放预留；并发请求不会同时穿透预算。估算按 prompt 字符数 + `maxOutputTokens`，
  不是账单；跨进程/多实例仍需账本侧（DB/Redis）原子扣减。
* 默认请求工厂只用 clientName 当 model；生产应传入基于 `LlmRegistry` 的工厂，
  让 model/options/tools 由配置决定。

## 6. P1：可观测与回放

```java
InMemoryTraceStore traceStore = new InMemoryTraceStore();
TraceRecorder.install(traceStore);          // 进程级安装；不装则只构造记录、不落库

// 在节点/运行线程里（AgentCallContext 提供 runId/turnId/node）
TraceRecorder.recordLlm("deepseek-pro", messages, result,
        1000, 500, 1500, 2000, "USD", 1200, "success", null);

// 回放/回归：按 golden trace 校验 prompt hash 与结果摘要
ReplaySession session = new ReplaySession(traceStore, "run-1");
LlmTraceRecord recorded = session.expectLlm(messages);
session.verifyResult(recorded, actualResult);
session.assertExhausted();
```

* `LlmTraceRecord` 记录 prompt hash、脱敏/截断后的 prompt 与结果、token/成本、耗时、状态；
  `ToolTraceRecord` 记录工具名/调用 id/参数与结果摘要/状态/耗时。
* `TextRedactor` 抹掉 Bearer / sk- / github_pat_ / api_key / 邮箱等常见敏感串，
  `PromptHasher` 用 SHA-256 做精确比对；原文不落 trace。
* `ReplaySession` 是**校验回放**：验证同一 prompt 的结果/工具链是否漂移。要从失败点继续
  执行，仍需检查点状态（`CheckpointService`），trace 本身不还原上下文。
* 测试脚手架：`@AgentTest` 等价于 `@TianGongApplicationTestStarter`，自动启动上下文并注入；
  `WorkflowGraphSnapshot.snapshot(registry)` 输出按节点名排序的稳定图快照，
  `assertMatches(registry, goldenFile)` 与 golden 文件比对（节点/边/START/replay 策略）。

## 7. 本轮边界

* SSE 端点是独立的 `AgentEventSseServer`，尚未接进 `ApiHttpServer` 的
  `@ApiEntranceNode` 路由；下一轮把它做成入口的一种出口类型。
* 检索 ACL 只能校验 retriever 返回的 `requiredScopes/tenantId` 声明；召回前分区与
  文档密级标注是 retriever 的职责。
* 路由执行器目前只覆盖**同步**调用；流式降级（已吐出的正文不能撤回）未实现。
  `maxReasoningTokens` 已由流层硬截止（字符近似）。租户预算用内存账本做了原子预留/释放；
  跨进程/多实例的强一致扣减未做。
* `TraceRecorder` 提供 golden trace 校验回放；工作流级「从失败点继续执行」仍依赖检查点，
  trace 不还原上下文。`TextRedactor` 是规则脱敏，不是完备 DLP；敏感数据不应进入 trace。
* 指令注入/输出过滤/租户隔离、HITL 审批节点、工具超时/重试/熔断：P2。
* `ToolGovernance` 是进程级静态配置；多租户不同策略需要 P2 的租户级作用域。
