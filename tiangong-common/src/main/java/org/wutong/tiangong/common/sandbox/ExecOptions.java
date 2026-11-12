package org.wutong.tiangong.common.sandbox;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;

/**
 * 单次执行的<b>运行期参数</b> —— 只承载「这一次跟默认不一样」的东西。<br>
 * <br>
 * <h2>与 {@link Sandbox#defaultTimeout()} / {@code @Sandbox} 注解的关系</h2>
 * 超时有四个来源，优先级从低到高：
 * <ol>
 *     <li>{@link Sandbox#defaultTimeout()} —— 该沙箱实现的默认（接口 default 方法兜底）</li>
 *     <li>全局配置 {@code sandbox.<name>.timeout} —— 运维可调，不改代码</li>
 *     <li>{@code @Sandbox(timeoutMs = ...)} —— 节点级，写代码时定</li>
 *     <li><b>本类的 {@link #timeout()}</b> —— 单次调用，运行期定，优先级最高</li>
 * </ol>
 * 前三层都是静态的，但「这次命令要跑多久」只有方法体知道。
 * 所以本层必须存在，否则用户只能为了一个特例（{@code git clone} 要 5 分钟）
 * 去改注解或全局配置，那会拖累同一节点下的 {@code git status}。
 *
 * <h2>只包含可选覆盖项</h2>
 * 所有字段都可为 {@code null}，表示「此项不覆盖，沿用下层」。
 * <b>框架负责合并</b>（见 {@code AgentNodeDefinition#resolveTimeout}），
 * 沙箱实现拿到的是合并后的值。
 *
 * <h2>不该放什么</h2>
 * <ul>
 *     <li>沙箱身份（{@code image} / {@code containerId} / 沙箱名）—— 那是
 *         {@code @Sandbox(box = ...)} 的职责，或某个实现的私有配置</li>
 *     <li>若把各类私有字段并进本类，会退化为「所有沙箱私有配置的并集」，
 *         那是典型的抽象泄漏</li>
 * </ul>
 */
public final class ExecOptions {

    /** 本次执行的超时；{@code null} = 沿用下层来源 */
    private final Duration timeout;

    /** 本次的输出上限（字节）；{@code null} = 沿用下层来源 */
    private final Integer maxOutputBytes;

    /** 工作目录；{@code null} = 继承当前进程 */
    private final Path workDir;

    /** 追加的环境变量（不覆盖环境时用空 Map） */
    private final Map<String, String> env;

    /** 是否继承父进程环境变量；默认 {@code true} */
    private final Boolean inheritEnv;

    /** 输出解码字符集；{@code null} = 由沙箱实现决定默认（推荐 UTF-8） */
    private final Charset charset;

    private ExecOptions(Duration timeout, Integer maxOutputBytes, Path workDir,
                        Map<String, String> env, Boolean inheritEnv, Charset charset) {
        this.timeout = timeout;
        this.maxOutputBytes = maxOutputBytes;
        this.workDir = workDir;
        this.env = env;
        this.inheritEnv = inheritEnv;
        this.charset = charset;
    }

    /** 取一个空配置：全部沿用下层来源 */
    public static ExecOptions none() {
        return new ExecOptions(null, null, null, Map.of(), null, null);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Duration timeout() {
        return timeout;
    }

    public Integer maxOutputBytes() {
        return maxOutputBytes;
    }

    public Path workDir() {
        return workDir;
    }

    public Map<String, String> env() {
        return env;
    }

    /**
     * 是否继承父进程环境变量。<br>
     * 未显式设置时返回 {@code true} —— 保持与 {@code ProcessBuilder} 默认一致。
     */
    public boolean inheritEnv() {
        return inheritEnv == null || inheritEnv;
    }

    public Charset charset() {
        return charset;
    }

    public static final class Builder {

        private Duration timeout;
        private Integer maxOutputBytes;
        private Path workDir;
        private Map<String, String> env = Map.of();
        private Boolean inheritEnv;
        private Charset charset;

        /** 本次超时。建议显式给出，避免无限等待 */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder timeoutMillis(long millis) {
            this.timeout = Duration.ofMillis(millis);
            return this;
        }

        /** 输出上限（字节）；超出即截断并置 {@code truncated = true} */
        public Builder maxOutputBytes(int bytes) {
            this.maxOutputBytes = bytes;
            return this;
        }

        public Builder workDir(Path workDir) {
            this.workDir = workDir;
            return this;
        }

        /** 追加/覆盖的环境变量 */
        public Builder env(Map<String, String> env) {
            this.env = env == null ? Map.of() : Map.copyOf(env);
            return this;
        }

        /**
         * 是否继承父进程环境变量。<br>
         * 置 {@code false} 时子进程只拿到 {@link #env} —— 可减少密钥经环境变量泄漏的面，
         * 但多数命令会因此缺少 PATH 而失败，慎用。
         */
        public Builder inheritEnv(boolean inherit) {
            this.inheritEnv = inherit;
            return this;
        }

        /** 输出解码字符集。Windows 控制台默认非 UTF-8，中文乱码时应显式指定 */
        public Builder charset(Charset charset) {
            this.charset = charset;
            return this;
        }

        public ExecOptions build() {
            return new ExecOptions(timeout, maxOutputBytes, workDir,
                    Collections.unmodifiableMap(env), inheritEnv, charset);
        }
    }
}
