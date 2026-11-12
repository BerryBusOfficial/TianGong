package com.taixu.tiangong.core.agent.entrance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.taixu.tiangong.common.agent.context.AgentContext;
import com.taixu.tiangong.core.agent.AgentLoopExecutor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 入口通道注册表 + 它与 {@code AgentLoopExecutor} 的接线。<br>
 * <br>
 * <b>为什么补</b>：{@code @ApproveNode(channel = "...")} 这个属性以前<b>根本没被读取</b> ——
 * 执行器直接用硬编码的 approvalChannel 字段。注册表的存在就是为了让"按名引用 / 替换通道"
 * 这条文档承诺变成真的。下面 {@link #executorRegistersBuiltinChannels()} 是接线是否成立的
 * 最小验证。
 */
class EntranceChannelRegistryTest {

    /** 测试用通道：只关心名字，不需要真能取输入。 */
    private static final class DummyChannel implements EntranceChannel<String> {
        private final String name;

        DummyChannel(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String await(String workflowId, AgentContext context) {
            throw new UnsupportedOperationException("测试通道不提供输入");
        }

        @Override
        public String toBody(String input) {
            return input;
        }
    }

    @Test
    @DisplayName("★ 执行器构造后，内置通道已按名注册（这就是 channel() 能生效的前提）")
    void executorRegistersBuiltinChannels() {
        AgentLoopExecutor executor = new AgentLoopExecutor(null);
        EntranceChannelRegistry registry = executor.channelRegistry();

        assertTrue(registry.contains("approval"), "审批通道必须已注册，否则 @ApproveNode 默认名会报错");
        assertTrue(registry.contains("stdin"), "STDIN 通道必须已注册");
        // HTTP 通道依赖 routeRegistry，是懒加载的 —— 没跑过 @ApiEntranceNode 就不该存在
        assertFalse(registry.contains("http"), "HTTP 通道是懒加载的，此时不应存在");
    }

    @Test
    @DisplayName("按名注册与查找")
    void registerAndGet() {
        EntranceChannelRegistry registry = new EntranceChannelRegistry();
        DummyChannel mine = new DummyChannel("mine");
        registry.register(mine);

        assertSame(mine, registry.get("mine"));
        assertTrue(registry.contains("mine"));
        assertEquals(1, registry.size());
    }

    @Test
    @DisplayName("同名注册视为替换（这才谈得上「用我自己的通道替掉默认的」）")
    void sameNameReplaces() {
        EntranceChannelRegistry registry = new EntranceChannelRegistry();
        DummyChannel first = new DummyChannel("approval");
        DummyChannel second = new DummyChannel("approval");
        registry.register(first);
        registry.register(second);

        assertSame(second, registry.get("approval"), "后注册的应替换掉先注册的");
        assertEquals(1, registry.size(), "替换不应增加通道数量");
    }

    @Test
    @DisplayName("名字为空/通道为 null 直接报错，别把脏数据放进注册表")
    void rejectsBadInput() {
        EntranceChannelRegistry registry = new EntranceChannelRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.register(null));
        assertThrows(IllegalArgumentException.class, () -> registry.register(new DummyChannel("")));
        assertThrows(IllegalArgumentException.class, () -> registry.register(new DummyChannel("   ")));
    }

    @Test
    @DisplayName("未注册时返回 null（由调用方报错，不静默回退）")
    void missingReturnsNull() {
        EntranceChannelRegistry registry = new EntranceChannelRegistry();
        assertNull(registry.get("nope"));
        assertNull(registry.get(""));
        assertNull(registry.get(null));
        assertFalse(registry.contains("nope"));
    }

    @Test
    @DisplayName("names() 排序，便于报错信息稳定可读")
    void namesSorted() {
        EntranceChannelRegistry registry = new EntranceChannelRegistry();
        registry.register(new DummyChannel("zeta"));
        registry.register(new DummyChannel("alpha"));
        registry.register(new DummyChannel("mid"));

        assertEquals(List.of("alpha", "mid", "zeta"), registry.names());
    }
}
