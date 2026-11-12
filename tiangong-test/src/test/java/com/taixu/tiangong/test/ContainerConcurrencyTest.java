package com.taixu.tiangong.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.taixu.tiangong.annotations.annotation.definition.BeanDefinition;
import com.taixu.tiangong.annotations.annotation.definition.Lifecycle;
import com.taixu.tiangong.annotations.processor.registry.BeanDefinitionRegistry;
import com.taixu.tiangong.core.container.FrameworkContainer;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 容器在<b>并发</b>下建 Bean 的行为。<br>
 * <br>
 * <b>这条用例是从真实应用里捞回来的缺陷</b>：循环依赖的「正在创建中」标记原先是一个
 * 全局共享集合。于是「两个线程各自创建同一个 PROTOTYPE Bean」会被误判成循环依赖 ——
 * 多 START 工作流同时启动时，第二条链在 {@code WorkflowScheduler.doStart} 里取上下文
 * 直接抛「检测到循环依赖」而启动失败，只能靠触发器下一轮轮询碰运气重试。<br>
 * 循环依赖只可能发生在<b>同一个线程的建栈</b>里（A 的注入触发 B，B 又回到 A），
 * 判定集合因此必须按线程隔离。
 */
class ContainerConcurrencyTest {

    private FrameworkContainer containerFor(BeanDefinition... defs) {
        BeanDefinitionRegistry registry = new BeanDefinitionRegistry();
        for (BeanDefinition def : defs) {
            registry.register(def);
        }
        return FrameworkContainer.create(registry, c -> {});
    }

    private static BeanDefinition prototype(String name, Class<?> clazz) {
        return BeanDefinition.builder()
                .beanName(name)
                .beanClass(clazz)
                .lifecycle(Lifecycle.PROTOTYPE)
                .lazyInit(false)
                .build();
    }

    @Test
    @DisplayName("多线程同时创建同一个原型 Bean：不应被误判为循环依赖")
    void concurrentPrototypeCreationIsNotACycle() throws Exception {
        FrameworkContainer container = containerFor(prototype("ctx", Payload.class));
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            Set<Future<Payload>> futures = new HashSet<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> container.getBean("ctx", Payload.class)));
            }
            Set<Payload> created = new HashSet<>();
            for (Future<Payload> future : futures) {
                created.add(future.get(10, TimeUnit.SECONDS));
            }
            assertEquals(threads, created.size(),
                    "并发取原型 Bean 应各自拿到新实例；误判循环依赖会让其中一部分直接抛异常");
        } finally {
            pool.shutdownNow();
        }
    }

    public static class Payload {
    }
}
