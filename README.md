# 天工（TianGong / CraftEngine）

> 太虚生态的底层 Agent 框架：注解式节点编排 + 轻量 IoC 容器 + 多 Provider LLM 流式调用。

## 模块

| 模块 | 作用 |
| :--- | :--- |
| `tiangong-annotations` | 注解与注解处理器（组件/节点/工具/生命周期） |
| `tiangong-common` | 公共类型、异常、JSON、沙箱、调用者上下文（`AgentCallContext`） |
| `tiangong-protocol` | 类型化 Agent 流式事件协议：事件模型、SSE 编解码、内存日志、客户端 SDK |
| `tiangong-retrieval` | 检索/记忆 SPI：`Retriever`、ACL、citation 事件、提示词上下文拼装 |
| `tiangong-trace` | 可观测与回放：prompt hash、脱敏摘要、token/成本、工具摘要、golden trace 校验 |
| `tiangong-llm` | Deepseek / OpenAI / Ollama 客户端、流式工具循环、结构化输出请求 |
| `tiangong-core` | 容器、AgentLoop、工作流调度、工具治理、检查点、Trace、HTTP 入口 |
| `tiangong-memory` | 记忆扩展 |
| `tiangong-mcp` / `tiangong-skill` | MCP 与目录式技能接入 |
| `tiangong-spring` | Spring 宿主桥接 |
| `tiangong-user` / `tiangong-test` | 示例应用与集成测试 |

## 构建

要求 JDK 21、Maven 3.9+。

```bash
mvn test
mvn -DskipTests package
```

## 第一轮集成契约（2026-11-12）

针对 ReportHub 集成侧反馈，先落地三个 P0：

1. **调用者上下文 + 工具治理**
   `AgentCallContext`（userId / tenantId / scopes / traceId）从 HTTP 头注入 fork 线程；
   `@AgentFunction(scopes = ..., requiresApproval = ...)` 声明工具权限；
   请求构建时按 scope 过滤工具，执行期同一判据再校验；审批失败默认拒绝，审计旁路记录。
2. **类型化流式事件 + SDK**
   `message.delta` / `reasoning.delta` / `tool.call` / `tool.result` / `citation` /
   `artifact` / `usage` / `structured` / `error` / `done`，带 `seq`、`runId`、`turnId`；
   `AgentEventSseServer` 支持 `Last-Event-ID` 续传与断连取消，`AgentEventClient` 提供统一解析。
3. **结构化输出契约**
   `JsonSchemaGenerator` 从 record/POJO 生成 schema；OpenAI 下发 `json_schema`，
   Deepseek/Ollama 走 `json_object`/提示词 + 框架侧校验；失败带错误路径修复重试；
   校验通过发 `structured` 事件，不再用 `<<<REPORT_DRAFT>>>` 一类哨兵从正文抠 JSON。

完整用法与边界见 [docs/agent-integration.md](docs/agent-integration.md)。

## 第二轮 P1（进行中）

* **检索/记忆 SPI**：`Retriever` 只负责召回；`RetrievalService` 统一做 ACL（scope + tenant）、
  排序截断、citation 事件与 `toPromptBlock`。换向量库/图扩散不改业务。
* **模型路由 + 成本**：`ModelRouter` 按任务给候选链；`ModelRouteExecutor` 按链降级、
  空回答重试、记录 `RoutedAttempt`，并通过 `CostLedger` 记 token/成本；`TenantBudgetGuard`
  在调用前做租户软预算检查。当前只覆盖同步调用；流式路由、推理 token 硬预算与强一致租户
  扣减尚未实现（见文档边界）。
* **可观测与回放**：`TraceRecorder` 记录每次模型调用的 prompt hash、脱敏 prompt/结果、
  token/成本，以及工具参数/结果摘要；`ReplaySession` 用 golden trace 校验 prompt hash
  与结果摘要是否漂移。完整工作流重执行仍依赖检查点，不假装凭 trace 还原状态。

## 设计原则

* **状态与身份分离**：`AgentContext` 是可检查点的工作流状态；`AgentCallContext` 是请求身份，
  不写快照，避免恢复时把上一个用户的权限带进新请求。
* **同一判据**：工具「是否下发给模型」与「能否执行」共用 `ToolAccessPolicy`，不出现两套规则。
* **失败默认拒绝**：审批处理器缺失/异常按拒绝处理；工具执行失败把原因回放给模型，不炸整条流。
* **协议层不依赖宿主**：事件协议/SSE 不依赖 `@ApiEntranceNode`，可挂到任意 HTTP 端口。

## 协议

个人自研生态项目，暂不公开授权，保留所有权利。
