package com.taixu.tiangong.boot;

import lombok.extern.slf4j.Slf4j;
import com.taixu.tiangong.annotations.scanner.TianGongScanner;
import com.taixu.tiangong.common.exception.SystemException;
import com.taixu.tiangong.common.resource.ResourceLoader;
import com.taixu.tiangong.core.config.ApplicationArgs;
import com.taixu.tiangong.core.config.ConfigLoader;
import com.taixu.tiangong.core.container.FrameworkContainer;
import com.taixu.tiangong.core.container.context.application.TianGongApplicationContext;

import java.util.function.Consumer;

/**
 * 单机自运行启动入口。<br>
 * <br>
 * <b>为什么在 tiangong-boot 而不是 core</b>：core 是引擎（容器 / 扫描 / 节点执行），
 * 宿主形态是另一层关注点 —— 单机启动（读资源、读 agent.yml、阻塞跑 AgentRuntime）
 * 与"被别的宿主触发"（如 Spring 集成，由宿主接管生命周期）是不同的启动路径。
 * 拆开后，集成模块只依赖 core，物理上不会带上单机启动行为。
 */
@Slf4j
public class TianGongApplication {

    public static TianGongApplicationContext run(Class<?> mainClass) throws SystemException {
        return run(mainClass, s -> {
        }, c -> {
        });
    }

    public static TianGongApplicationContext run(Class<?> mainClass, String... args) throws SystemException {
        return run(mainClass, s -> {
        }, c -> {
        }, args);
    }

    public static TianGongApplicationContext run(Class<?> mainClass,
                                              Consumer<TianGongScanner> scannerConfig,
                                              Consumer<FrameworkContainer> containerConfig,
                                              String... args) throws SystemException {
        log.debug("资源加载器加载");
        ResourceLoader.load(mainClass);

        log.debug("配置加载");
        ApplicationArgs appArgs = new ApplicationArgs(args);
        ConfigLoader.load(appArgs.getConfigPath(), appArgs.getProfile());

        log.debug("创建应用上下文");
        return TianGongApplicationContext.create(
                s -> {
                    s.acceptPackages(mainClass.getPackageName());   // 自动扫描启动类所在包（外部用户包）
                    scannerConfig.accept(s);
                },
                containerConfig);
    }

}
