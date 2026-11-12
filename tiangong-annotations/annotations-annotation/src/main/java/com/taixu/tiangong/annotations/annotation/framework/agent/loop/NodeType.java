package com.taixu.tiangong.annotations.annotation.framework.agent.loop;

public enum NodeType {

    START,  // 开始节点（Loop启动节点）
    COMMON,  // 一般节点（通用节点）
    END,  // 结束节点（Loop轮次结束节点）
    BRANCH,  // 分支节点（IF节点，仅路由到允许的节点）
    PARALLEL,  // 并行节点（多路共通节点）
    ENTRANCE,  // 入口节点（允许外部输入的节点）
    EXPORT,  // 出口节点（提供对外响应的节点）
    MERGE,  // 合并节点（合并并行节点的多个状态）
    COMMAND,  // 命令节点（执行外部命令；配合 @Sandbox 指定执行环境）
    TOOL,  // 工具节点（调用工具；AgentToolInvoker 由框架注入，用户不必 @Inject）

}
