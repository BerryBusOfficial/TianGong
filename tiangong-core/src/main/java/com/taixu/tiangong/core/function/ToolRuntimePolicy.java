package com.taixu.tiangong.core.function;

import com.taixu.tiangong.common.thread.VirtualThreads;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工具运行时策略：超时、重试、按工具熔断。<br>
 * <br>
 * <b>默认不重试</b>（maxAttempts=1）：工具可能带副作用，未声明幂等的调用被静默重试是
 * 数据事故。需要重试的宿主显式调大 maxAttempts，并自行保证工具幂等。<br>
 * <br>
 * <b>超时</b>用虚拟线程 + Future 实现；超时会 cancel(true)，但工具若忽略中断，实际执行
 * 可能仍在后台跑完 —— 这里不假装能强杀 Java 线程，只保证调用方及时得到失败并触发熔断。
 * <br>
 * <b>熔断</b>按工具名统计连续失败；达到阈值后 open，在冷却期内直接拒绝，冷却结束进入
 * half-open 放一个探针，成功关闭、失败重新 open。
 */
public final class ToolRuntimePolicy {

    private static final ExecutorService CALL_EXECUTOR = VirtualThreads.newPerTaskExecutor();

    private final Duration timeout;
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final int failureThreshold;
    private final Duration openDuration;

    private final ConcurrentMap<String, Circuit> circuits = new ConcurrentHashMap<>();

    private ToolRuntimePolicy(Duration timeout, int maxAttempts, Duration initialBackoff,
                              int failureThreshold, Duration openDuration) {
        this.timeout = timeout;
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ToolRuntimePolicy defaults() {
        return builder().build();
    }

    /**
     * 执行一次工具调用。
     *
     * @param toolName 工具名（熔断分桶键）
     * @param action   实际调用
     */
    public Object execute(String toolName, Callable<Object> action) {
        String key = toolName == null ? "<unknown>" : toolName;
        Circuit circuit = circuits.computeIfAbsent(key,
                ignored -> new Circuit(failureThreshold, openDuration));
        if (!circuit.tryAcquire()) {
            throw new ToolCircuitOpenException(
                    "工具 [" + key + "] 熔断中，冷却后再试");
        }

        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                Object result = callWithTimeout(key, action);
                circuit.onSuccess();
                return result;
            } catch (RuntimeException e) {
                last = e;
                circuit.onFailure();
                if (attempt >= maxAttempts) {
                    break;
                }
                if (circuit.open()) {
                    break;
                }
                sleep(initialBackoff);
            }
        }
        throw last == null ? new ToolRuntimeException("工具 [" + key + "] 执行失败") : last;
    }

    private Object callWithTimeout(String toolName, Callable<Object> action) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            try {
                return action.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new ToolRuntimeException("工具 [" + toolName + "] 执行失败: " + e.getMessage(), e);
            }
        }
        Future<Object> future = CALL_EXECUTOR.submit(action);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ToolTimeoutException(
                    "工具 [" + toolName + "] 执行超时（" + timeout.toMillis() + "ms）", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ToolRuntimeException("工具 [" + toolName + "] 执行被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new ToolRuntimeException(
                    "工具 [" + toolName + "] 执行失败: " + (cause == null ? e : cause.getMessage()), cause);
        }
    }

    private static void sleep(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolRuntimeException("工具重试等待被中断", e);
        }
    }

    /** 清空熔断状态（测试/配置热更新用） */
    public void reset() {
        circuits.clear();
    }

    public Duration timeout() {
        return timeout;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public static class ToolRuntimeException extends RuntimeException {
        public ToolRuntimeException(String message) {
            super(message);
        }

        public ToolRuntimeException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class ToolTimeoutException extends ToolRuntimeException {
        public ToolTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class ToolCircuitOpenException extends ToolRuntimeException {
        public ToolCircuitOpenException(String message) {
            super(message);
        }
    }

    private static final class Circuit {

        private enum State {CLOSED, OPEN, HALF_OPEN}

        private final int failureThreshold;
        private final long openNanos;
        private State state = State.CLOSED;
        private int failures;
        private long openUntilNanos;

        private Circuit(int failureThreshold, Duration openDuration) {
            this.failureThreshold = Math.max(1, failureThreshold);
            this.openNanos = Math.max(1, openDuration.toNanos());
        }

        private synchronized boolean tryAcquire() {
            if (state == State.OPEN) {
                if (System.nanoTime() < openUntilNanos) {
                    return false;
                }
                state = State.HALF_OPEN;
                return true;
            }
            return state != State.HALF_OPEN;
        }

        private synchronized void onSuccess() {
            state = State.CLOSED;
            failures = 0;
        }

        private synchronized void onFailure() {
            failures++;
            if (state == State.HALF_OPEN || failures >= failureThreshold) {
                state = State.OPEN;
                failures = 0;
                openUntilNanos = System.nanoTime() + openNanos;
            }
        }

        private synchronized boolean open() {
            return state == State.OPEN;
        }
    }

    public static final class Builder {

        private Duration timeout = Duration.ofSeconds(60);
        private int maxAttempts = 1;
        private Duration initialBackoff = Duration.ofMillis(50);
        private int failureThreshold = 5;
        private Duration openDuration = Duration.ofSeconds(30);

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = Math.max(1, maxAttempts);
            return this;
        }

        public Builder initialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
            return this;
        }

        public Builder failureThreshold(int failureThreshold) {
            this.failureThreshold = Math.max(1, failureThreshold);
            return this;
        }

        public Builder openDuration(Duration openDuration) {
            this.openDuration = openDuration == null ? Duration.ofSeconds(30) : openDuration;
            return this;
        }

        public ToolRuntimePolicy build() {
            return new ToolRuntimePolicy(timeout, maxAttempts, initialBackoff,
                    failureThreshold, openDuration);
        }
    }
}
