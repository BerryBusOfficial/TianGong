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

## 第二轮 P1（已完成，2026-11-12）

* **检索/记忆 SPI**：`Retriever` 只负责召回；`RetrievalService` 统一做 ACL（scope + tenant）、
  排序截断、citation 事件与 `toPromptBlock`。换向量库/图扩散不改业务。
* **模型路由 + 成本**：`ModelRouter` 按任务给候选链；`ModelRouteExecutor` 做同步降级、
  空回答重试与成本记账；`StreamingModelRouteExecutor` 做流式降级——只要首个可见事件
  （正文/思考/工具/引用/产物）还没发出，就允许换候选；一旦可见即锁定，失败只报错不切换，
  避免客户端收到两段正文。`TenantBudgetGuard` 在调用前做原子预算预留/释放（内存账本），
  并发请求不会同时穿透同一租户预算。`ChatRequest.maxReasoningTokens` 由流层
  `ReasoningBudgetChatStream` 执行：思考超限即取消上游并以异常结束。跨进程强一致租户扣减
  仍未实现（见文档边界）。
* **可观测与回放**：`TraceRecorder` 记录每次模型调用的 prompt hash、脱敏 prompt/结果、
  token/成本，以及工具参数/结果摘要；`ReplaySession` 用 golden trace 校验 prompt hash
  与结果摘要是否漂移。完整工作流重执行仍依赖检查点，不假装凭 trace 还原状态。
* **测试脚手架**：`@AgentTest` 是 `@TianGongApplicationTestStarter` 的短别名，
  自动启动上下文并注入依赖；`WorkflowGraphSnapshot` 输出稳定的节点/边/START/REPLAY 快照，
  `assertMatches` 与 golden 文件比对，图结构漂移会直接失败。

## Maven Central

坐标（groupId 为 `io.github.berrybus-official`，版本 `0.0.1`）：

```xml
<dependency>
    <groupId>io.github.berrybus-official</groupId>
    <artifactId>tiangong-core</artifactId>
    <version>0.0.1</version>
</dependency>
```

常用模块：`tiangong-common`、`tiangong-protocol`、`tiangong-llm`、`tiangong-retrieval`、
`tiangong-trace`、`tiangong-core`、`tiangong-mcp`、`tiangong-skill`、`tiangong-spring`；
版本统一由 `tiangong-bom` 管理：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.github.berrybus-official</groupId>
            <artifactId>tiangong-bom</artifactId>
            <version>0.0.1</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

发布前准备：

* 在 Central Portal 获取 token，写入 `~/.m2/settings.xml` 的 `central` server；
* 生成/导入 GPG 签名密钥，并上传公钥到 keyserver（如 `keyserver.ubuntu.com`）；
* 确认 GitHub 账号 `berrybus-official` 拥有 `io.github.berrybus-official` 命名空间。

Windows 上 GPG 通常不在 Maven 进程 PATH 里，先补 PATH：

```powershell
$env:PATH = "C:\Program Files\GnuPG\bin;$env:PATH"
gpg --list-secret-keys --keyid-format LONG
# 没有密钥时：
# gpg --full-generate-key
# gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
```

签名口令由 gpg-agent / Kleopatra pinentry 处理。CI 无交互时可在 `settings.xml` 配置
`gpg.passphrase`，或用 `-Dgpg.passphrase=...`（不要提交到仓库）。

```bash
# 正常构建不会签名/发布
mvn clean test

# 发布：sources + javadoc + GPG + Central Portal bundle（autoPublish=false）
mvn -Pcentral-publish clean deploy
```

`central-publish` profile 只在这个命令下生效；发布后在 Central Portal 手动确认（或将
`autoPublish` 改为 `true`）。父 POM 已改为独立根 POM，不再依赖外部 `taixu-parent` 的
`1.0-SNAPSHOT`。

## 设计原则

* **状态与身份分离**：`AgentContext` 是可检查点的工作流状态；`AgentCallContext` 是请求身份，
  不写快照，避免恢复时把上一个用户的权限带进新请求。
* **同一判据**：工具「是否下发给模型」与「能否执行」共用 `ToolAccessPolicy`，不出现两套规则。
* **失败默认拒绝**：审批处理器缺失/异常按拒绝处理；工具执行失败把原因回放给模型，不炸整条流。
* **协议层不依赖宿主**：事件协议/SSE 不依赖 `@ApiEntranceNode`，可挂到任意 HTTP 端口。

## 开源协议

本项目采用 [Apache License 2.0](LICENSE)。
