package org.wutong.tiangong.test;

import org.junit.jupiter.api.Test;
import org.wutong.tiangong.core.agent.runtime.ResourceOwnershipRegistry;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ResourceOwnershipRegistry 资源占用检测测试。<br>
 * 覆盖：tryAcquire/release/owner/releaseAll、冲突抛异常。
 */
class ResourceOwnershipRegistryTest {

    @Test
    void acquireSucceedsWhenFree() {
        ResourceOwnershipRegistry reg = new ResourceOwnershipRegistry();
        assertTrue(reg.tryAcquire("stdin", "wf-1"));
    }

    @Test
    void acquireFailsWhenOccupied() {
        ResourceOwnershipRegistry reg = new ResourceOwnershipRegistry();
        reg.tryAcquire("stdin", "wf-1");
        assertThrows(IllegalStateException.class,
                () -> reg.tryAcquire("stdin", "wf-2"),
                "资源被占用时应抛异常");
    }

    @Test
    void releaseAllowsReacquire() {
        ResourceOwnershipRegistry reg = new ResourceOwnershipRegistry();
        reg.tryAcquire("stdin", "wf-1");
        reg.release("stdin", "wf-1");
        assertTrue(reg.tryAcquire("stdin", "wf-2"));
    }

    @Test
    void ownerQuery() {
        ResourceOwnershipRegistry reg = new ResourceOwnershipRegistry();
        reg.tryAcquire("stdout", "wf-1");
        Optional<String> owner = reg.owner("stdout");
        assertEquals("wf-1", owner.orElseThrow());
        assertTrue(reg.owner("stdin").isEmpty());
    }

    @Test
    void releaseAllForWorkflow() {
        ResourceOwnershipRegistry reg = new ResourceOwnershipRegistry();
        reg.tryAcquire("stdin", "wf-1");
        reg.tryAcquire("stdout", "wf-1");
        reg.tryAcquire("stderr", "wf-2");
        reg.releaseAll("wf-1");
        assertTrue(reg.owner("stdin").isEmpty());
        assertTrue(reg.owner("stdout").isEmpty());
        assertEquals("wf-2", reg.owner("stderr").orElseThrow());
    }

    @Test
    void sameWorkflowReacquireIsIdempotent() {
        ResourceOwnershipRegistry reg = new ResourceOwnershipRegistry();
        reg.tryAcquire("stdin", "wf-1");
        // 同一 workflow 再次 acquire 应成功（幂等）
        assertTrue(reg.tryAcquire("stdin", "wf-1"));
    }
}
