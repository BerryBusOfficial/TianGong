package org.wutong.tiangong.spring;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.beans.Introspector;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code @ServiceAgent} 接口扫描器：把契约接口注册为 Spring Bean（JDK 代理）。<br>
 * <br>
 * <b>为什么在 BDRPP 阶段做</b>：代理必须作为 BeanDefinition 注册（而不是事后 registerSingleton），
 * 否则 {@code @Autowired ContractType} 在单例实例化期间解析不到 —— BDRPP 在所有单例创建之前运行，
 * 是唯一正确的位置。<br>
 * <br>
 * 代理的调用处理在<b>调用时</b>才解析天工运行时（{@link TianGongRuntime}），
 * 因此"Spring Bean 先建、天工后自举"的顺序不会造成注入问题。
 */
@Slf4j
public class ServiceAgentRegistrar implements BeanDefinitionRegistryPostProcessor {

    private final Environment environment;
    private volatile ConfigurableListableBeanFactory beanFactory;

    public ServiceAgentRegistrar(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        if (!environment.getProperty("tiangong.enabled", Boolean.class, Boolean.TRUE)) {
            log.info("tiangong.enabled=false，跳过 @ServiceAgent 代理注册");
            return;
        }
        List<String> packages = scanPackages();
        if (packages.isEmpty()) {
            log.warn("未解析到扫描包（@EnableTianGong 启动类未登记且未配置 tiangong.packages），跳过 @ServiceAgent 扫描");
            return;
        }

        Set<Class<?>> contracts = scanContracts(packages);
        for (Class<?> contract : contracts) {
            ServiceAgentProxyFactory.validate(contract);
            String beanName = Introspector.decapitalize(contract.getSimpleName());
            if (registry.containsBeanDefinition(beanName)) {
                log.warn("Bean 名 [{}] 已存在，跳过 @ServiceAgent 代理注册（契约 {}）—— 同名 Bean 优先",
                        beanName, contract.getName());
                continue;
            }
            RootBeanDefinition definition = new RootBeanDefinition(contract);
            definition.setTargetType(contract);
            definition.setInstanceSupplier(() ->
                    ServiceAgentProxyFactory.create(contract, this::runtime));
            registry.registerBeanDefinition(beanName, definition);
            log.info("注册 @ServiceAgent 代理: {} → Bean [{}]", contract.getName(), beanName);
        }
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    // ==================== 内部 ====================

    /**
     * 扫描包：启动类所在包 + {@code @EnableTianGong(packages=...)} + {@code tiangong.packages}。
     */
    private List<String> scanPackages() {
        Set<String> packages = new LinkedHashSet<>();
        Class<?> appClass = TianGongAppInfo.appClass();
        if (appClass != null) {
            packages.add(appClass.getPackageName());
        }
        packages.addAll(TianGongAppInfo.packages());
        String extra = environment.getProperty("tiangong.packages");
        if (StringUtils.hasText(extra)) {
            for (String piece : extra.split("[,;]")) {
                if (StringUtils.hasText(piece)) {
                    packages.add(piece.trim());
                }
            }
        }
        return new ArrayList<>(packages);
    }

    private Set<Class<?>> scanContracts(List<String> packages) {
        Class<?> appClass = TianGongAppInfo.appClass();
        ClassLoader classLoader = appClass != null
                ? appClass.getClassLoader()
                : Thread.currentThread().getContextClassLoader();

        Set<Class<?>> contracts = new LinkedHashSet<>();
        try (ScanResult scan = new ClassGraph()
                .acceptPackages(packages.toArray(String[]::new))
                .enableAllInfo()
                .scan()) {
            for (ClassInfo info : scan.getAllClasses()) {
                if (!info.isInterface() || info.isAnnotation()) {
                    continue;
                }
                Class<?> candidate = info.loadClass();
                if (ServiceAgentProxyFactory.isServiceAgentContract(candidate)) {
                    contracts.add(candidate);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "@ServiceAgent 扫描失败（包: " + packages + "）: " + e.getMessage(), e);
        }
        if (log.isDebugEnabled() && classLoader != null) {
            log.debug("@ServiceAgent 扫描完成：{} 个契约（包 {}）", contracts.size(), packages);
        }
        return contracts;
    }

    /**
     * 惰性取运行时 Bean：代理调用时才会走到这里（自举完成后）。
     */
    private TianGongRuntime runtime() {
        ConfigurableListableBeanFactory factory = this.beanFactory;
        if (factory == null) {
            throw new IllegalStateException("TianGong 桥尚未完成初始化（beanFactory 未装配）");
        }
        return factory.getBean(TianGongRuntime.class);
    }
}
