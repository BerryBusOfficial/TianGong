package com.taixu.tiangong.core.agent.entrance;

import lombok.extern.slf4j.Slf4j;
import com.taixu.tiangong.common.agent.context.AgentContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * STDIN 入口通道：把 {@code System.in} 的唯一读取与行队列搬出执行器。<br>
 * <br>
 * 设计要点（迁移自原 {@code AgentLoopExecutor}）：
 * <ul>
 *     <li><b>唯一输入泵</b>：整个框架一个守护线程读 {@code System.in}，把行投入
 *         {@link #lines}；多会话共享同一队列，避免各自包一层 {@code BufferedReader}
 *         抢同一个底层流</li>
 *     <li><b>平台守护线程而非虚拟线程</b>：控制台 {@code readLine()} 会 pin 住
 *         虚拟线程的载体线程，不该占用虚拟线程调度器的 worker</li>
 *     <li><b>协作式取消 + 关闭通过"带超时轮询"实现</b>：{@code await()} 每
 *         {@link #POLL_MILLIS} 轮询一次队列，让 {@link AgentContext#isCancelled()}
 *         与通道 {@link #closing} 信号在 ≤ 200ms 内生效；直接阻塞在 {@code readLine()}
 *         上会让取消与关机信号都打不进来</li>
 *     <li><b>EOF 哨兵</b>：泵读到流末尾或抛异常后向队列投一条 {@link #EOF_SENTINEL}
 *         （用 {@code ==} 比较），等待中的 {@code await()} 据此返回 {@code null}
 *         结束会话</li>
 * </ul>
 *
 * <h2>不变量</h2>
 * <ul>
 *     <li>{@link #start()} 幂等：多次调用只起一个泵</li>
 *     <li>{@link #close()} 后所有进行中的 {@code await()} 在 ≤ {@link #POLL_MILLIS}
 *         内返回 {@code null}</li>
 *     <li>{@code await()} 不感知也不处理工作流检查点（那是执行器的事）</li>
 * </ul>
 */
@Slf4j
public class StdinEntranceChannel implements EntranceChannel<String> {

    /** 行队列轮询粒度：协作式取消与 {@link #close()} 信号的最迟生效窗口 */
    static final long POLL_MILLIS = 200L;

    /** 泵退出时投入队列的 EOF 哨兵（用 {@code ==} 判等，区分于普通行） */
    static final String EOF_SENTINEL = new String("<stdin-eof>");

    private static final String PUMP_THREAD_NAME = "tiangong-stdin-pump";

    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();

    /** 关闭标记：{@code true} 后 {@link #await} 立即返回 {@code null}，且泵不再读取 */
    private final AtomicBoolean closing = new AtomicBoolean(false);

    /** 守护线程引用；{@code null} 表示尚未启动 */
    private volatile Thread pump;

    @Override
    public String name() {
        return "stdin";
    }

    @Override
    public void start() {
        ensurePump();
    }

    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;     // 已关闭
        }
        Thread pump = this.pump;
        if (pump != null) {
            pump.interrupt();
        }
        lines.offer(EOF_SENTINEL);
        log.info("STDIN 已请求关闭：等待中的会话将立即退出");
    }

    /** 通道是否已进入关闭状态（供外部状态查询） */
    public boolean isClosing() {
        return closing.get();
    }

    /**
     * 阻塞取得下一行输入。<br>
     * <br>
     * 返回 {@code null} 的三种情形：
     * <ul>
     *     <li>{@link AgentContext#isCancelled()} 变为 {@code true}</li>
     *     <li>通道已被 {@link #close()}</li>
     *     <li>泵已退出且投了 {@link #EOF_SENTINEL}</li>
     * </ul>
     * 这三种都让执行器结束入口节点的等待循环。
     *
     * @throws InterruptedException 等待线程被中断
     */
    @Override
    public String await(String workflowId, AgentContext context) throws InterruptedException {
        while (!closing.get() && !context.isCancelled()) {
            String line = lines.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
            if (line == null) {
                continue;       // 超时：重新检查取消/关闭
            }
            if (line == EOF_SENTINEL) {
                return null;    // EOF：通道结束
            }
            if (line.isBlank()) {
                continue;       // 空行：跳过（不构成一轮对话），重新打印提示符
            }
            return line;
        }
        return null;            // 取消 / 关闭：通道结束
    }

    @Override
    public String toBody(String input) {
        // STDIN 拿到的就是用户原始文本，直接透传
        return input;
    }

    @Override
    public boolean isJsonBody() {
        // STDIN 不走 JSON 解析：用户输入的文本应当原样注入节点方法
        return false;
    }

    /**
     * 确保 STDIN 输入泵已启动（幂等）。<br>
     * 泵是 {@code System.in} 的唯一消费者：读到的行投入 {@link #lines}，读到 EOF 时投入
     * {@link #EOF_SENTINEL}。
     */
    private void ensurePump() {
        if (pump != null) {
            return;
        }
        synchronized (this) {
            if (pump != null) {
                return;
            }
            Thread t = new Thread(this::pumpLoop, PUMP_THREAD_NAME);
            t.setDaemon(true);
            t.start();
            pump = t;
            log.debug("STDIN 输入泵已启动");
        }
    }

    /**
     * 泵主循环：从 {@code System.in} 持续读行投入队列。<br>
     * 退出条件：流末尾（{@code readLine()} 返回 {@code null}）/ 抛异常 / 线程被中断。
     * 退出后向队列投 EOF 哨兵，唤醒所有等待中的 {@link #await}。
     */
    private void pumpLoop() {
        try (var reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while (!closing.get() && (line = reader.readLine()) != null) {
                lines.put(line);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (!closing.get()) {
                log.warn("STDIN 读取异常，输入泵退出", e);
            }
        }
        lines.offer(EOF_SENTINEL);
        log.debug("STDIN 输入泵已退出");
    }
}