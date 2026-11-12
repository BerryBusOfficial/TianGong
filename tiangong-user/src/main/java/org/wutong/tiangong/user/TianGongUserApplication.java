package org.wutong.tiangong.user;

import org.wutong.tiangong.annotations.annotation.framework.TianGongApplicationStarter;
import org.wutong.tiangong.annotations.annotation.framework.enable.EnableAgentLoop;
import org.wutong.tiangong.annotations.annotation.framework.enable.EnableApi;
import org.wutong.tiangong.annotations.annotation.framework.enable.EnableCheckpoint;
import org.wutong.tiangong.annotations.annotation.framework.enable.EnableMcp;
import org.wutong.tiangong.annotations.annotation.framework.enable.EnableSkill;
import org.wutong.tiangong.common.exception.SystemException;
import org.wutong.tiangong.boot.TianGongApplication;
import org.wutong.tiangong.user.context.PetContext;

// API 通道开关：打开后 ApiHttpServer 监听 agent.api.port，
// 工作流跑到 @ApiEntranceNode 时动态注册 /{workflowId}/chat 路由。
@EnableApi
@TianGongApplicationStarter
// 桌宠有两个 START 节点（STDIN / API），框架为每条链各起一条常驻工作流，
// 触发策略默认 REJECT —— 同一条链不会重复启动，正好是「常驻单实例」语义。
@EnableAgentLoop(contextClass = PetContext.class)
// 检查点开关（docs/checkpoint-contract.md）。删掉或改成 value = false 即关闭。
// 刻意不并入 @TianGongApplicationStarter —— 开启会改变失败语义（快照失败即中断工作流），
// 且要求上下文可完整往返。PetContext 满足：三个协作对象都标了 @SnapshotIgnore，
// 并在 afterRestore() 里从容器取回。
@EnableCheckpoint
// 目录式技能开关：扫描 agent.skills.path（默认 skills/）下的 SKILL.md。
// 关闭不影响 Bean 形态的技能（ReminderSkill 由框架核心直接收集）。
@EnableSkill
// MCP 开关：读 agent.mcp 下的 server 列表，连上后把它们的工具注册进 FunctionRegistry。
// 桌宠侧在 think 节点里把这些工具一并披露给模型（见 PetAgentNode#availableTools）。
// 关掉这个开关 = 只留本地工具，agent.mcp 配置整段不生效。
@EnableMcp
public class TianGongUserApplication {

    public static void main(String[] args) throws SystemException {
        TianGongApplication.run(TianGongUserApplication.class, args);
    }

}
