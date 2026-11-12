package org.wutong.tiangong.test.node;

import org.wutong.tiangong.annotations.annotation.framework.agent.loop.AgentNode;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.NodeNext;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.entrance.ApproveNode;
import org.wutong.tiangong.test.TestContext;

/**
 * START → ApproveNode → END：用于回归测试 WAIT_ONCE 模式行为。<br>
 * <br>
 * ApproveNode 阻塞等待一次审批决定，决定反序列化到 {@code ApprovalDecision}
 * 参数并保存到 {@code ctx.steps}，然后 continueFrom 跑到 END。
 *
 * <p>测试需要在执行 {@code execute("approve-start", ctx)} 之前通过
 * ApprovalEntranceChannel.submit(...) 投递决定。
 */
public class ApproveNodeWorkflow {

    @AgentNode(value = "approve-start", type = NodeType.START)
    @NodeNext("approve-decision")
    public TestContext start(TestContext ctx) {
        ctx.steps.add("start");
        return ctx;
    }

    @ApproveNode(value = "approve-decision")
    @NodeNext("approve-end")
    public TestContext decide(TestContext ctx,
                              org.wutong.tiangong.core.agent.entrance.ApprovalDecision decision) {
        ctx.steps.add("decision:" + decision.isApproved() + ":" + decision.getApprover());
        return ctx;
    }

    @AgentNode(value = "approve-end", type = NodeType.END)
    public TestContext end(TestContext ctx) {
        ctx.steps.add("end");
        return ctx;
    }
}