package com.taixu.tiangong.spring;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import com.taixu.tiangong.spring.bridge.SharedInstanceSpace;
import com.taixu.tiangong.spring.bridge.TianGongWriteBeanPostProcessor;

/**
 * {@code @EnableTianGong} 导入的装配类（由 {@link TianGongImportSelector} 引入）。<br>
 * <br>
 * 三个 Bean：
 * <ul>
 *     <li>{@link TianGongProperties}：{@code tiangong.*} 配置</li>
 *     <li>{@link TianGongRuntime}：自举触发 + 生命周期持有（SmartInitializingSingleton / DisposableBean）</li>
 *     <li>{@link ServiceAgentRegistrar}：{@code @ServiceAgent} 接口 → Spring Bean 代理
 *         （static @Bean：BeanDefinitionRegistryPostProcessor 必须在单例创建前就绪）</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class TianGongConfiguration {

    @Bean
    public TianGongProperties tianGongProperties(Environment environment) {
        return TianGongProperties.from(environment);
    }

    /**
     * 共享实例空间：桥的中枢（归属登记 / 延迟回填 / 服务端点视图）。
     */
    @Bean
    public SharedInstanceSpace sharedInstanceSpace(ApplicationContext applicationContext) {
        return new SharedInstanceSpace(applicationContext);
    }

    @Bean
    public TianGongRuntime tianGongRuntime(Environment environment, TianGongProperties properties,
                                           ApplicationContext applicationContext,
                                           SharedInstanceSpace space) {
        return new TianGongRuntime(environment, properties, applicationContext, space);
    }

    /**
     * {@code @TianGongWrite} 注入处理器（Spring BeanPostProcessor）。<br>
     * 必须是 static：BeanPostProcessor 在普通单例之前就要注册好；依赖的
     * beanFactory 用于惰性解析 TianGongRuntime（代理调用时才需要）。
     */
    @Bean
    public static TianGongWriteBeanPostProcessor tianGongWriteBeanPostProcessor(
            SharedInstanceSpace space, ConfigurableListableBeanFactory beanFactory) {
        return new TianGongWriteBeanPostProcessor(space, () -> beanFactory.getBean(TianGongRuntime.class));
    }

    /**
     * 必须是 static：BeanDefinitionRegistryPostProcessor 在配置类实例化之前就要被创建，
     * 非 static 的工厂方法会挂在配置类实例上，注册时机被推迟到单例创建阶段（太晚）。
     */
    @Bean
    public static ServiceAgentRegistrar serviceAgentRegistrar(Environment environment) {
        return new ServiceAgentRegistrar(environment);
    }
}
