---
name: new-nodetype
description: 扩展新的 NodeType 时的必查清单（用户要「加一种节点类型」「新增节点」时用）
triggers:
  - 节点类型
  - NodeType
  - 新增节点
allowed-tools:
  - Read
---

# 扩展 NodeType 的三处必查

这三处都曾**静默失效**——编译通过、测试通过、但节点行为不对：

1. **`CommonNodeProcessor.support()`** 是**列举式**的（START/COMMON/END/ENTRANCE/EXPORT/MERGE）。
   新类型不在这里加分支，节点**根本不会被注册，且没有任何报错**。
2. **`WorkflowGraphValidator`** 的 switch **没有 default 分支**。
   新类型会被**静默跳过全部校验**，编译器不会提醒。
3. **`AgentLoopExecutor.continueFrom`** 是 **if 链不是 switch**。
   先判断新类型能否直接落入末尾的「常规执行」：能落（如 TOOL，靠通用参数注入）就不必加分支；
   不能落（如 COMMAND，需要额外解析命令）**必须加**，否则静默出错。

## 参数注入

节点方法的形参：非 `AgentContext` 类型一律从容器取 Bean 注入（通用机制），
所以 `AgentToolInvoker` 之类**不需要** `@Inject`。

## 教训

机制变更会让既有隐患**换一种更隐蔽的症状**：COMMAND 节点漏了 `continueFrom` 分支时，
原本是抛「参数过多」异常，通用参数绑定上线后变成**静默注入 null**，
表现为「节点跑了但什么都没发生」。
