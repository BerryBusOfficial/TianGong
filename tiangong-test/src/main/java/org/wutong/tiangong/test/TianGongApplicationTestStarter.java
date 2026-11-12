package org.wutong.tiangong.test;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.*;

/**
 * 标记测试类：自动启动 TianGong 应用上下文并注入依赖（对标 Spring @SpringBootTest）。<br>
 * 由同模块的 {@link TianGongExtension} 处理：加载配置、创建上下文、按 {@code @Inject} 注入依赖。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ExtendWith(TianGongExtension.class)
public @interface TianGongApplicationTestStarter {
}
