package com.taixu.tiangong.spring.annotation;

import org.springframework.context.annotation.Import;
import com.taixu.tiangong.annotations.annotation.framework.enable.EnableAsync;
import com.taixu.tiangong.annotations.annotation.framework.enable.EnableAutoConfiguration;
import com.taixu.tiangong.annotations.annotation.framework.enable.EnableExceptionListener;
import com.taixu.tiangong.annotations.annotation.framework.enable.EnableTrace;
import com.taixu.tiangong.spring.TianGongImportSelector;

import java.lang.annotation.*;

/**
 * 启用天工集成（标在 Spring Boot 启动类上）。<br>
 * <br>
 * 语义：<b>由 Spring 侧触发天工自举，而不是托管</b> —— 天工按自己的流程完成
 * 容器构建与运行时启动；Spring 只负责触发与在关闭时通知它收尾。<br>
 * <br>
 * 自举行为（见 {@code TianGongRuntime}）：
 * <ul>
 *     <li>API 禁用模式：Spring 宿主提供 HTTP 能力，{@code @EnableApi} 会启动期报错，
 *         {@code @ApiEntranceNode} 默认报错（可配退化）</li>
 *     <li>AgentRuntime 后台启动（不阻塞 Spring 启动）</li>
 *     <li>不注册 JVM 关机钩子：关闭由 Spring 生命周期驱动</li>
 *     <li>配置：{@code agent.yaml}（可选基础层）+ Spring {@code application.yml}
 *         白名单命名空间（覆盖层，见 {@code tiangong.config-namespaces}）</li>
 * </ul>
 * <br>
 * 组合的开关对齐单机 {@code @TianGongApplicationStarter}，<b>唯独不含 API</b>
 * （宿主模式的硬约束）；{@code @EnableMcp} 需要时由应用自行标注（连接外部工具服务器有副作用，
 * 不默认开启）。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Inherited
@EnableAutoConfiguration
@EnableAsync
@EnableTrace
@EnableExceptionListener
@Import(TianGongImportSelector.class)
public @interface EnableTianGong {

    /**
     * 额外扫描包（天工组件与 {@code @ServiceAgent} 接口）。<br>
     * 默认取标注本注解的启动类所在包；需要把天工组件放到别的包时在此补充。
     */
    String[] packages() default {};
}
