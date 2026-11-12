package org.wutong.tiangong.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wutong.tiangong.core.agent.AgentLoopExecutor;
import org.wutong.tiangong.test.node.LinearWorkflow;
import org.wutong.tiangong.test.node.LoopWorkflow;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 上下文生命周期回调的调用时机验证：{@code init} / {@code afterEnd} / {@code destroy}。<br>
 * <br>
 * 三者的区别很容易被抹平 —— 尤其 {@code END} 节点在 SESSION_LOOP 下是<b>每轮</b>的终点
 * 而非整条工作流的终点（{@code runEntrance} 的循环每轮都跑到 END）。把「善后」和
 * 「销毁」混为一谈，会让多轮会话在第一轮结束时就丢光状态。<br>
 * <br>
 * 这里用计数断言把三个回调的次数钉死，防止将来有人把调用点挪错。
 */
class ContextLifecycleTest {

    /** 记录三个生命周期回调各自被调用几次 */
    static final class CountingContext extends TestContext {
        int initCount;
        int afterEndCount;
        int destroyCount;

        @Override
        public void init() {
            initCount++;
        }

        @Override
        public void afterEnd() {
            afterEndCount++;
        }

        @Override
        public void destroy() {
            destroyCount++;
        }
    }

    @Test
    @DisplayName("init：整条工作流只调用一次（在进入第一个节点之前）")
    void initCalledOncePerWorkflow() {
        AgentLoopExecutor executor = TestHarness.executorFor(new LinearWorkflow());
        CountingContext ctx = new CountingContext();

        executor.execute("linear-start", ctx);

        assertEquals(1, ctx.initCount,
                "整条工作流只应初始化一次；多次说明调用点被放进了节点方法之类会重复执行的位置");
        // 顺序：init 必须早于任何节点执行
        assertEquals(List.of("start", "step1", "step2", "end"), ctx.steps,
                "初始化不应干扰节点执行轨迹");
    }

    @Test
    @DisplayName("afterEnd：走到 END 节点后调用一次")
    void afterEndCalledAfterEndNode() {
        AgentLoopExecutor executor = TestHarness.executorFor(new LinearWorkflow());
        CountingContext ctx = new CountingContext();

        executor.execute("linear-start", ctx);

        assertEquals(1, ctx.afterEndCount, "走到 END 节点后应触发一次本轮善后");
    }

    @Test
    @DisplayName("destroy：整条工作流终止时调用一次")
    void destroyCalledOnceAtWorkflowEnd() {
        AgentLoopExecutor executor = TestHarness.executorFor(new LinearWorkflow());
        CountingContext ctx = new CountingContext();

        executor.execute("linear-start", ctx);

        assertEquals(1, ctx.destroyCount, "工作流终止时应销毁一次");
    }

    @Test
    @DisplayName("destroy：工作流抛异常时仍会执行（finally 保证）")
    void destroyRunsEvenWhenWorkflowThrows() {
        AgentLoopExecutor executor = TestHarness.executorFor(new LoopWorkflow());
        CountingContext ctx = new CountingContext();

        assertThrows(IllegalStateException.class,
                () -> executor.execute("loop-start", ctx),
                "死循环应超过最大迭代次数后抛异常");

        assertEquals(1, ctx.initCount, "异常路径下 init 仍应执行过一次");
        assertEquals(1, ctx.destroyCount,
                "工作流抛异常时 destroy 仍须执行 —— 这是释放外部资源的最后机会");
        assertEquals(0, ctx.afterEndCount,
                "未走到 END 节点，不应触发 afterEnd");
    }

    @Test
    @DisplayName("destroy：工作流被取消时同样执行")
    void destroyRunsWhenCancelled() {
        AgentLoopExecutor executor = TestHarness.executorFor(new LoopWorkflow());
        CountingContext ctx = new CountingContext();

        // 取消后进入循环首部的 isCancelled 检查即抛出，属异常退出路径
        ctx.cancel();

        assertThrows(Exception.class, () -> executor.execute("loop-start", ctx));

        assertEquals(1, ctx.destroyCount, "取消路径下 destroy 也应执行");
    }

    /**
     * 示范：带幂等守卫的 init 与「会重置守卫」的 destroy。<br>
     * <br>
     * 框架<b>不</b>替应用决定释放什么（{@code AgentContext.destroy} 默认空实现），
     * 只保证调用时机。但应用一旦选择「释放后重启再初始化」，就<b>必须</b>在
     * destroy 里重置幂等守卫，否则第二次 init 直接 return ——
     * 这条测试把该陷阱固化成可执行的示范，避免将来有人写 destroy 时漏掉。
     */
    static final class ReinitializableContext extends TestContext {
        private final AtomicBoolean initiated = new AtomicBoolean(false);
        /** init 真正生效的次数（被守卫挡掉时不增加） */
        int initEffectCount;

        @Override
        public void init() {
            if (initiated.get()) {
                return;
            }
            initEffectCount++;
            initiated.set(true);
        }

        @Override
        public void destroy() {
            // 少了这一句，「工作流重启时再次初始化」就是空话
            initiated.set(false);
        }
    }

    @Test
    @DisplayName("示范：应用选择「释放后重启再初始化」时，destroy 须重置 init 的幂等守卫")
    void destroyMustResetInitGuard() {
        AgentLoopExecutor executor = TestHarness.executorFor(new LinearWorkflow());
        ReinitializableContext ctx = new ReinitializableContext();

        executor.execute("linear-start", ctx);
        assertEquals(1, ctx.initEffectCount, "第一条工作流应初始化一次");

        // 复用同一对象再跑一条（真实场景：容器单例上下文被下一条工作流复用）
        executor.execute("linear-start", ctx);

        assertEquals(2, ctx.initEffectCount,
                "第二条工作流应重新初始化；若 destroy 未重置幂等守卫，"
                        + "init 会被挡掉，表现为新会话没有系统提示词等初始状态");
    }

    @Test
    @DisplayName("默认实现为空：未覆写时不产生任何副作用")
    void defaultImplementationsAreNoOp() {
        AgentLoopExecutor executor = TestHarness.executorFor(new LinearWorkflow());

        // TestContext 未覆写三个回调 → 走接口 default 空实现
        TestContext result = assertDoesNotThrow(
                () -> executor.execute("linear-start", new TestContext()));

        assertEquals(List.of("start", "step1", "step2", "end"), result.steps,
                "默认空实现不应影响工作流正常执行");
    }
}
