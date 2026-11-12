package org.wutong.tiangong.bytebuddy;

import org.junit.jupiter.api.Test;
import org.wutong.tiangong.common.exception.SystemRuntimeException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 延迟实例占位测试：不触发构造器、就绪前明确报错、就绪后转发；
 * 以及 final 类拒绝与 final 方法清单。
 */
class DeferredInstancePlaceholderTest {

    /** 目标类：故意<b>没有无参构造</b>（验证 Unsafe 分配、不执行构造器） */
    public static class NoDefaultCtorTarget {

        public final String createdBy;

        public NoDefaultCtorTarget(String createdBy) {
            this.createdBy = createdBy;
            throw new IllegalStateException("构造器不应被执行（占位实例必须绕开它）");
        }

        public String hello() {
            return "real:" + createdBy;
        }
    }

    /** 普通目标：验证转发与 Object 方法安全值 */
    public static class Sample {

        private int calls;

        public String hello() {
            calls++;
            return "real";
        }

        public int calls() {
            return calls;
        }
    }

    public static final class FinalSample {
        public String hello() {
            return "final";
        }
    }

    public static class WithFinalMethod {
        public final String locked() {
            return "locked";
        }

        public String open() {
            return "open";
        }
    }

    @Test
    void placeholderDoesNotRunConstructorAndFailsLoudlyBeforeReady() {
        Object placeholder = DeferredInstancePlaceholder.create(NoDefaultCtorTarget.class);

        assertInstanceOf(NoDefaultCtorTarget.class, placeholder, "占位实例必须可赋值给目标类型");
        SystemRuntimeException ex = assertThrows(SystemRuntimeException.class,
                () -> ((NoDefaultCtorTarget) placeholder).hello());
        assertTrue(ex.getMessage().contains("尚未就绪"), ex.getMessage());
    }

    @Test
    void delegatesToTargetAfterSet() {
        Sample placeholder = (Sample) DeferredInstancePlaceholder.create(Sample.class);
        Sample real = new Sample();

        DeferredInstancePlaceholder.setTarget(placeholder, real);

        assertSame(real, DeferredInstancePlaceholder.targetOf(placeholder));
        assertEquals("real", placeholder.hello());
        assertEquals(1, placeholder.calls(), "调用必须转发到真实实例（状态在真实实例上）");
    }

    @Test
    void objectMethodsAreSafeBeforeReady() {
        Sample placeholder = (Sample) DeferredInstancePlaceholder.create(Sample.class);

        assertDoesNotThrow(placeholder::toString);
        assertDoesNotThrow(placeholder::hashCode);
        assertEquals(placeholder, placeholder);
        assertNotEquals(placeholder, new Object());
    }

    @Test
    void finalClassIsRejectedWithReason() {
        assertFalse(DeferredInstancePlaceholder.isSupported(FinalSample.class));
        SystemRuntimeException ex = assertThrows(SystemRuntimeException.class,
                () -> DeferredInstancePlaceholder.create(FinalSample.class));
        assertTrue(ex.getMessage().contains("final"), ex.getMessage());
    }

    @Test
    void finalMethodsAreReported() {
        assertEquals(java.util.List.of("locked"),
                DeferredInstancePlaceholder.finalMethods(WithFinalMethod.class));
    }
}
